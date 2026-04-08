package com.photogrammetry

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.util.Log

/**
 * Drives the Tensor G4's CPU scheduler via Android's Performance Hint API
 * (introduced in API 31 and fully tuned for Tensor on Pixel 8+/9 Pro).
 *
 * How it works
 * ─────────────
 * The Tensor G4 has three CPU cluster tiers (Cortex-X4, A720, A520) plus a
 * dedicated TPU.  The standard scheduler rarely boosts the GL thread above the
 * efficiency cores because it can't predict frame deadlines.
 *
 * [PerformanceHintManager.Session] lets us report:
 *   • The DESIRED frame duration  (updateTargetWorkDuration)
 *   • The ACTUAL frame duration   (reportActualWorkDuration)
 *
 * The Tensor scheduler uses these to pre-heat the correct CPU cluster BEFORE
 * the next frame starts — eliminating the ramp-up latency that causes jank.
 *
 * Usage
 * ──────
 * 1. Call [initialize] from the GL thread inside [GLSurfaceView.Renderer.onSurfaceCreated].
 *    This registers the GL thread ID with the hint session.
 * 2. Call [frameStart] at the very top of [onDrawFrame].
 * 3. Call [frameEnd] just before returning from [onDrawFrame].
 * 4. Call [setTargetFps] when the target changes (thermal throttle etc.).
 * 5. Call [release] from [Activity.onDestroy].
 */
class PerformanceOptimizer(private val context: Context) {

    companion object {
        private const val TAG              = "PerformanceOptimizer"
        private const val TARGET_60_NS     = 16_666_666L  // 60 fps in nanoseconds
        private const val TARGET_30_NS     = 33_333_333L  // 30 fps in nanoseconds
    }

    @Volatile private var targetNs = TARGET_60_NS
    @Volatile private var isBoosted = false  // prevents double-boost / double-restore

    // API 31 — Tensor G4 fully supported
    private var hintSession: PerformanceHintManager.Session? = null
    private var frameStartNs = 0L
    private val registeredTids = mutableListOf<Int>()  // all TIDs in this session

    // Partial wake lock so the background scan thread is never throttled by
    // the OS idle policy even when the proximity sensor turns the screen off.
    private val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photogrammetry:scan")

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Must be called from the GL thread (so [android.os.Process.myTid] returns
     * the correct thread ID for the hint session).
     */
    fun initialize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(PerformanceHintManager::class.java)
            if (mgr != null) {
                val glTid = android.os.Process.myTid()
                registeredTids.clear()
                registeredTids.add(glTid)
                hintSession = mgr.createHintSession(registeredTids.toIntArray(), targetNs)
                Log.i(TAG, "PerformanceHintSession created for GL thread $glTid @ ${targetNs}ns target")
            }
        } else {
            Log.d(TAG, "PerformanceHintManager not available (API < 31)")
        }
    }

    /**
     * Registers an additional thread (e.g. the ARCore update thread) with the
     * Tensor G4 performance hint session so that thread also gets scheduled on
     * big cores during active frames.
     *
     * On API 34+ uses [PerformanceHintManager.Session.setThreads] to update
     * the session in-place.  On API 31–33 recreates the session with all TIDs.
     *
     * Call from the target thread so that [android.os.Process.myTid] yields
     * the correct ID, or pass the TID directly.
     */
    fun addThreadId(tid: Int = android.os.Process.myTid()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (tid in registeredTids) return
        registeredTids.add(tid)
        val session = hintSession ?: return
        val tids = registeredTids.toIntArray()
        if (Build.VERSION.SDK_INT >= 34) {
            // API 34: update session threads in-place
            session.setThreads(tids)
            Log.d(TAG, "Added thread $tid to hint session (${tids.size} total)")
        } else {
            // API 31–33: recreate session with new TID list
            session.close()
            val mgr = context.getSystemService(PerformanceHintManager::class.java)
            hintSession = mgr?.createHintSession(tids, targetNs)
            Log.d(TAG, "Recreated hint session with ${tids.size} threads")
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.close()
            hintSession = null
        }
        if (wakeLock.isHeld) wakeLock.release()
    }

    // -------------------------------------------------------------------------
    // Scan-state hooks (main thread)
    // -------------------------------------------------------------------------

    /** Acquire wake lock to keep GL thread running at full speed during scanning. */
    fun startScan() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(30L * 60 * 1_000)  // 30-minute safety timeout
        }
    }

    /** Release wake lock when scanning stops. */
    fun stopScan() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    // -------------------------------------------------------------------------
    // Per-frame hooks (GL thread)
    // -------------------------------------------------------------------------

    /** Call at the top of [GLSurfaceView.Renderer.onDrawFrame]. */
    fun frameStart() {
        frameStartNs = System.nanoTime()
    }

    /**
     * Call just before returning from [GLSurfaceView.Renderer.onDrawFrame].
     * Reports the actual work duration to the Tensor scheduler so it can
     * pre-boost for the next frame.
     */
    fun frameEnd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val actualNs = System.nanoTime() - frameStartNs
            hintSession?.reportActualWorkDuration(actualNs)
        }
    }

    // -------------------------------------------------------------------------
    // Dynamic FPS target (called from ThermalManager)
    // -------------------------------------------------------------------------

    fun setTargetFps(fps: Int) {
        targetNs = if (fps >= 60) TARGET_60_NS else TARGET_30_NS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.updateTargetWorkDuration(targetNs)
            Log.d(TAG, "Target updated → $fps fps ($targetNs ns)")
        }
    }
    /**
     * Boosts the Tensor G4 scheduler for a CPU-intensive export.
     * Sets target duration to TARGET_60_NS / 4 (= 4 ms) so the scheduler
     * pre-warms the big cores for the export thread.
     * Call just before submitting an export task; call [restoreExportBoost]
     * when the task finishes.
     */
    fun boostForExport() {
        if (isBoosted) return   // guard against double-boost
        isBoosted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.updateTargetWorkDuration(TARGET_60_NS / 4)
            Log.d(TAG, "Export boost ON (4 ms target)")
        }
    }

    /** Restores the hint-session target to the current FPS target after an export. */
    fun restoreExportBoost() {
        if (!isBoosted) return  // guard against double-restore
        isBoosted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.updateTargetWorkDuration(targetNs)
            Log.d(TAG, "Export boost OFF (restored to $targetNs ns)")
        }
    }}
