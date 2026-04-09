package com.photogrammetry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.photogrammetry.rendering.MainRenderer
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView : GLSurfaceView
    private lateinit var renderer    : MainRenderer
    private lateinit var statusText  : TextView
    private lateinit var btnScan     : Button
    private lateinit var btnExport   : Button
    private lateinit var btnFiles    : Button
    private lateinit var btnRecord   : Button
    private lateinit var chkDepth    : CheckBox

    private var isScanning   = false
    private var session      : Session? = null
    private var installRequested = false
    // Single-thread executor for file export (replaces raw Thread)
    private val exportExecutor = Executors.newSingleThreadExecutor()

    // Scan stats: AABB updated when scan stops
    private var statsText: TextView? = null
    private var lastScanStats: String = ""

    // Record stats every 30 frames
    private var frameCountSinceStats = 0
    // Pixel 9 Pro â€” hardware subsystem managers
    private lateinit var thermalManager : ThermalManager
    private lateinit var seekDepthOpacity: SeekBar
    private lateinit var seekAccumOpacity: SeekBar
    private lateinit var txtThermalBanner    : TextView
    private lateinit var txtDepthOpacityLabel: TextView
    private lateinit var txtAccumOpacityLabel: TextView
    // Rate-limit UI status bar updates: updated from GL thread, no sync needed
    private var lastUiUpdateMs = 0L
    // Scan elapsed-time tracking (main thread only)
    private var scanStartMs   = 0L
    private val timerHandler  = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isScanning) return
            val elapsed = (android.os.SystemClock.elapsedRealtime() - scanStartMs) / 1000L
            val mins = elapsed / 60L; val secs = elapsed % 60L
            val pts  = renderer.accumulator.size + renderer.depthAccumulator.size
            statsText?.text = "\u25cf Scanning  \u2022  $pts pts  \u2022  $mins:${"%02d".format(secs)}"
            timerHandler.postDelayed(this, 1_000L)
        }
    }
    // Tracks last thermal setpoint for accumulation to detect resume events
    private var thermalAccumulate = true
    // Scan name entered by user on stop (used to prefix the exported filename)
    private var pendingScanName: String? = null
    // OOM cap warning — shown at most once per scan
    private var hasShownCapWarning = false

    // -------------------------------------------------------------------------
    // Permission launcher
    // -------------------------------------------------------------------------

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // Enable Pixel 9 Pro LTPO high-framerate display
        PixelCameraConfigurator.enableHighFramerateDisplay(window)

        surfaceView = findViewById(R.id.gl_surface_view)
        statusText  = findViewById(R.id.status_text)
        statsText   = findViewById(R.id.stats_text)
        btnScan     = findViewById(R.id.btn_scan)
        btnExport   = findViewById(R.id.btn_export)
        btnFiles    = findViewById(R.id.btn_files)
        btnRecord   = findViewById(R.id.btn_record)
        chkDepth             = findViewById(R.id.chk_depth)
        txtThermalBanner     = findViewById(R.id.txt_thermal_banner)
        txtDepthOpacityLabel = findViewById(R.id.txt_depth_opacity_label)
        txtAccumOpacityLabel = findViewById(R.id.txt_accum_opacity_label)

        renderer = MainRenderer(this) { state, failureReason, liveCount, accumCount, fps ->
            // OOM cap warning: show once when accumulation approaches the limit
            val totalCap = com.photogrammetry.rendering.PointCloudAccumulator.MAX_POINTS +
                           com.photogrammetry.rendering.DepthAccumulator.MAX_POINTS
            if (!hasShownCapWarning && accumCount >= totalCap - 10_000) {
                hasShownCapWarning = true
                runOnUiThread {
                    Toast.makeText(this,
                        "\u26A0 Approaching scan point limit \u2014 consider stopping soon",
                        Toast.LENGTH_LONG).show()
                }
            }
            // Rate-limit UI text updates to \u2264 150 ms (avoids 60 Runnables/sec on main Looper)
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (nowMs - lastUiUpdateMs < 150L) return@MainRenderer
            lastUiUpdateMs = nowMs
            runOnUiThread {
                val reasonStr = if (failureReason.isNotEmpty()) " ($failureReason)" else ""
                statusText.text = buildString {
                    append("$state$reasonStr  \u2022  Live: $liveCount  \u2022  Acc: $accumCount")
                    append("  \u2022  ${"%.0f".format(fps)} fps")
                    if (SessionRecorder.isRecording) append("  \u23fa")
                }
                if (lastScanStats.isNotEmpty()) statsText?.text = lastScanStats
            }
        }

        // Wire frame-ready callback for SessionRecorder (Item 11: avoids retaining Frame across frames)
        renderer.onFrameReady = { frame ->
            frameCountSinceStats++
            if (frameCountSinceStats >= 30) {
                frameCountSinceStats = 0
                SessionRecorder.recordPointStats(frame,
                    renderer.accumulator.size, renderer.depthAccumulator.size)
            }
        }

        surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // ── Thermal manager — adapts scan quality to Tensor chip temperature ─
        thermalManager = ThermalManager(
            context = this,
            onThrottleChanged = { fps, accumulate, depthStride, dirtyBatchSize ->
                renderer.performanceOptimizer.setTargetFps(fps)
                renderer.depthStride     = depthStride
                renderer.dirtyBatchSize  = dirtyBatchSize
                val wasPaused = !thermalAccumulate
                thermalAccumulate = accumulate
                if (!accumulate && isScanning) {
                    renderer.isAccumulating = false
                } else if (wasPaused && accumulate && isScanning) {
                    // Thermal recovered — re-enable accumulation
                    renderer.isAccumulating = true
                }
                // Determine tier from dirtyBatchSize thresholds set by ThermalManager
                val tier = when {
                    dirtyBatchSize >= 20_000 -> 2   // CRITICAL / EMERGENCY
                    dirtyBatchSize >= 10_000 -> 1   // SEVERE
                    else                     -> 0
                }
                runOnUiThread {
                    when (tier) {
                        2 -> {
                            txtThermalBanner.visibility = View.VISIBLE
                            txtThermalBanner.text = "\uD83D\uDD25 Device hot \u2014 accumulation paused to prevent overheating"
                            if (!wasPaused) Toast.makeText(this, "\u26A0 Device too hot \u2014 accumulation paused", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            txtThermalBanner.visibility = View.VISIBLE
                            txtThermalBanner.text = "\uD83C\uDF21 Device warm \u2014 scan quality reduced"
                        }
                        else -> {
                            if (txtThermalBanner.visibility == View.VISIBLE) {
                                txtThermalBanner.visibility = View.GONE
                                if (wasPaused) Toast.makeText(this, "\u2713 Device cooled \u2014 full quality resumed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
            onRenderModeChange = { continuous ->
                surfaceView.renderMode =
                    if (continuous) GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    else            GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        )

        // Log Pixel 9 Pro physical camera intrinsics (once, for diagnostics)
        exportExecutor.submit {
            PixelCameraConfigurator.readPhysicalIntrinsics(this)?.let { intrinsics ->
                android.util.Log.i("Photogrammetry", "Physical intrinsics: $intrinsics")
            }
        }

        bindButtons()
    }

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            if (session == null) {
                session = Session(this).also { s ->
                    // â”€â”€ Pixel 9 Pro: select 60 fps + depth camera config â”€â”€â”€â”€â”€â”€
                    PixelCameraConfigurator.configure(s)

                    val config = Config(s).apply {
                        focusMode  = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                        }
                        // Instant placement lets ARCore anchor points before a
                        // full plane is detected â€” useful for sparse environments.
                        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    }
                    s.configure(config)
                    renderer.setSession(s)
                }
            }

            session!!.resume()
        } catch (e: UnavailableArcoreNotInstalledException) {
            showFatalError("Please install Google Play Services for AR")
            return
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showFatalError("This device does not support AR")
            return
        } catch (e: UnavailableSdkTooOldException) {
            showFatalError("Please update the app to use AR")
            return
        } catch (e: Exception) {
            showFatalError("Failed to create AR session: ${e.message}")
            return
        }

        thermalManager.register()
        surfaceView.onResume()
        // Register the export executor thread with the Tensor G4 hint session.
        // queueEvent ensures onSurfaceCreated (which calls initialize()) has run first.
        surfaceView.queueEvent {
            exportExecutor.submit { renderer.performanceOptimizer.addThreadId() }
        }
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(timerRunnable)
        thermalManager.unregister()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        android.util.Log.w("Photogrammetry", "Low memory — clearing depth accumulator")
        renderer.depthAccumulator.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop recording if still active
        session?.let { SessionRecorder.stop(it) }
        renderer.performanceOptimizer.release()
        exportExecutor.shutdown()
        session?.close()
        session = null
    }

    // -------------------------------------------------------------------------
    // Button wiring
    // -------------------------------------------------------------------------

    private fun bindButtons() {

        // â”€â”€ Scan â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        btnScan.setOnClickListener {
            isScanning = !isScanning
            // Respect thermal setpoint: don't re-enable accumulation if throttled
            renderer.isAccumulating = isScanning && thermalAccumulate

            if (isScanning) {
                renderer.accumulator.clear()
                renderer.depthAccumulator.clear()
                renderer.performanceOptimizer.startScan()
                btnScan.text = "\u25a0 Stop Scan"
                btnScan.setTextColor(Color.parseColor("#FF8800"))  // amber while scanning
                btnExport.isEnabled = false
                statsText?.text = ""
                hasShownCapWarning = false   // reset per-scan OOM warning
                pendingScanName = null
                scanStartMs = android.os.SystemClock.elapsedRealtime()
                timerHandler.post(timerRunnable)
            } else {
                timerHandler.removeCallbacks(timerRunnable)
                renderer.performanceOptimizer.stopScan()
                renderer.flushAccumulatedCloud()    // push final cloud to GPU
                btnScan.text = "\u25b6 Start Scan"
                btnScan.setTextColor(Color.WHITE)
                val featCount  = renderer.accumulator.size
                val depthCount = renderer.depthAccumulator.size
                val total      = featCount + depthCount
                btnExport.isEnabled = total > 0
                // Immediate feedback while AABB is computed in background
                statsText?.text = "\u2713 Complete  \u2022  $total pts captured"
                computeAndShowScanStats(featCount, depthCount)
                // Ask the user for a name to tag the exported file
                if (total > 0) {
                    val input = android.widget.EditText(this).apply {
                        hint = "e.g. living_room"
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        maxLines = 1
                        setSingleLine(true)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Name this scan")
                        .setMessage("Optional: enter a label for the exported file")
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            val entered = input.text.toString().trim()
                            if (entered.isNotEmpty()) pendingScanName = entered
                        }
                        .setNegativeButton("Skip", null)
                        .show()
                }
            }
        }

        // Long-press scan button to clear the cloud without stopping the scan
        btnScan.setOnLongClickListener {
            if (isScanning) {
                renderer.accumulator.clear()
                renderer.depthAccumulator.clear()
                surfaceView.queueEvent { renderer.flushAccumulatedCloud() }
                Toast.makeText(this, "Point cloud cleared", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // â”€â”€ Export (PLY / STL) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        btnExport.setOnClickListener { view ->
            val (featBuf, featCount)   = renderer.accumulator.snapshot()
            val (depthBuf, depthCount) = renderer.depthAccumulator.snapshot()
            val total = featCount + depthCount

            if (total == 0) {
                Toast.makeText(this, "No accumulated points to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            PopupMenu(this, view).apply {
                menu.add(0, 1, 0, "Export as PLY  ($total pts)")
                menu.add(0, 2, 0, "Export as STL  ($total pts)")
                menu.add(0, 3, 0, "Export as OBJ  ($total pts)")
                setOnMenuItemClickListener { item ->
                    val fmt = when (item.itemId) { 1 -> "PLY"; 2 -> "STL"; else -> "OBJ" }
                    runExport(featBuf, featCount, depthBuf, depthCount, fmt)
                    true
                }
                show()
            }
        }

        // â”€â”€ File manager â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        btnFiles.setOnClickListener {
            FileManagerDialog.newInstance().show(supportFragmentManager, "files")
        }

        // â”€â”€ ARCore Session Recording â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        btnRecord.setOnClickListener {
            val s = session ?: return@setOnClickListener
            if (SessionRecorder.isRecording) {
                SessionRecorder.stop(s)
                btnRecord.text = "\u23fa Record"
                btnRecord.setTextColor(Color.WHITE)
                Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
            } else {
                val file = SessionRecorder.start(this, s)
                if (file != null) {
                    btnRecord.text = "\u23f9 Stop Rec"
                    btnRecord.setTextColor(Color.parseColor("#FF4444"))  // red while recording
                    Toast.makeText(this, "Recording â†’ ${file.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // â”€â”€ Depth overlay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        chkDepth.setOnCheckedChangeListener { _, checked ->
            renderer.isDepthOverlayEnabled = checked
            val vis = if (checked) View.VISIBLE else View.GONE
            seekDepthOpacity.visibility     = vis
            txtDepthOpacityLabel.visibility = vis
        }

        // Depth overlay opacity slider (0..100 = 0.0..1.0)
        seekDepthOpacity = findViewById(R.id.seek_depth_opacity)
        seekDepthOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                renderer.setDepthOpacity(progress / 100f)
                txtDepthOpacityLabel.text = "Depth: $progress%"
                if (renderer.isDepthOverlayEnabled) surfaceView.requestRender()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Accumulated cloud opacity slider (0..100 = 0.0..1.0, default 85%)
        seekAccumOpacity = findViewById(R.id.seek_accum_opacity)
        seekAccumOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                renderer.setAccumAlpha(progress / 100f)
                txtAccumOpacityLabel.text = "Cloud: $progress%"
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        // Sync renderer to the XML default slider value (85%)
        renderer.setAccumAlpha(seekAccumOpacity.progress / 100f)
    }

    // -------------------------------------------------------------------------
    // Export helpers
    // -------------------------------------------------------------------------

    /**
     * Merges feature and depth point buffers, then writes the combined cloud
     * on a background thread.
     */
    private fun runExport(
        featBuf:    FloatArray, featCount:  Int,
        depthBuf:   FloatArray, depthCount: Int,
        format: String
    ) {
        btnExport.isEnabled = false
        val total = featCount + depthCount
        statsText?.text = "\u23F3 Exporting $total pts as $format\u2026"

        exportExecutor.submit {
            renderer.performanceOptimizer.boostForExport()
            try {
                val merged = FloatArray(total * 4)
                System.arraycopy(featBuf,  0, merged, 0,            featCount  * 4)
                System.arraycopy(depthBuf, 0, merged, featCount * 4, depthCount * 4)

                val file = when (format) {
                    "STL" -> StlExporter.export(this, merged, total)
                    "OBJ" -> ObjExporter.export(this, merged, total)
                    else  -> PlyExporter.export(this, merged, total)
                }
                // Apply user-supplied scan name as filename prefix (item 10)
                val finalFile = pendingScanName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { name ->
                        val safeName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                        val dest = java.io.File(file.parentFile ?: file, "${safeName}_${file.name}")
                        if (file.renameTo(dest)) dest else file
                    } ?: file
                pendingScanName = null
                // Release excess accumulator memory after a large export
                renderer.accumulator.trimToSize()
                runOnUiThread {
                    if (lastScanStats.isNotEmpty()) statsText?.text = lastScanStats
                    AlertDialog.Builder(this)
                        .setTitle("\u2713 Export complete")
                        .setMessage("$total pts saved as ${finalFile.name}")
                        .setPositiveButton("OK") { _, _ -> btnExport.isEnabled = true }
                        .setNeutralButton("Share") { _, _ ->
                            btnExport.isEnabled = true
                            shareExportedFile(finalFile)
                        }
                        .setCancelable(false)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (lastScanStats.isNotEmpty()) statsText?.text = lastScanStats
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    btnExport.isEnabled = true
                }
            } finally {
                renderer.performanceOptimizer.restoreExportBoost()
            }
        }
    }

    /** Launches the system share sheet for [file] using FileProvider. */
    private fun shareExportedFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val mimeType = when (file.extension.lowercase()) {
            "ply" -> "application/octet-stream"
            "stl" -> "model/stl"
            "obj" -> "model/obj"
            else  -> "*/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Improvement #16 — compute AABB + density stats when scan stops and
     * display them in the secondary stats TextView.
     */
    private fun computeAndShowScanStats(featCount: Int, depthCount: Int) {
        if (featCount + depthCount == 0) return
        exportExecutor.submit {
            val (depthBuf, depthCnt) = renderer.depthAccumulator.exportMerged()
            val (featBuf,  featCnt)  = renderer.accumulator.snapshot()
            val totalCnt = depthCnt + featCnt
            if (totalCnt == 0) return@submit

            // Seed AABB from whichever buffer has the first point
            val seedBuf = if (depthCnt > 0) depthBuf else featBuf
            var minX = seedBuf[0]; var maxX = minX
            var minY = seedBuf[1]; var maxY = minY
            var minZ = seedBuf[2]; var maxZ = minZ

            // Include depth points in AABB
            var i = 0
            while (i < depthCnt * 4) {
                val x = depthBuf[i]; val y = depthBuf[i + 1]; val z = depthBuf[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 4
            }
            // Include ARCore feature points in AABB
            i = 0
            while (i < featCnt * 4) {
                val x = featBuf[i]; val y = featBuf[i + 1]; val z = featBuf[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 4
            }

            val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
            val vol = dx * dy * dz
            val density = if (vol > 1e-6f) (featCount + depthCount) / vol else 0f
            // Adapt the accumulated-cloud depth gradient to the actual scan size
            renderer.setAccumDepthRange(maxOf(dx, dy, dz))
            lastScanStats = "BBox: %.2fm x %.2fm x %.2fm  |  ".format(dx, dy, dz) +
                            "Vol: %.2fm\u00b3  |  Density: %.0f pts/m\u00b3".format(vol, density)
            runOnUiThread { statsText?.text = lastScanStats }
        }
    }

    private fun showFatalError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    /** Exposed so the GL renderer can read display rotation safely. */
    fun getDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
}
