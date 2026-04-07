package com.photogrammetry

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.photogrammetry.rendering.MainRenderer

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: MainRenderer
    private lateinit var statusText: TextView

    private var session: Session? = null

    // Tracks whether we already requested an ARCore install so we don't loop
    private var installRequested = false

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while scanning
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.gl_surface_view)
        statusText  = findViewById(R.id.status_text)

        renderer = MainRenderer(this) { state, failureReason, pointCount, fps ->
            runOnUiThread {
                val reasonStr = if (failureReason.isNotEmpty()) " ($failureReason)" else ""
                statusText.text =
                    "State: $state$reasonStr | Points: $pointCount | FPS: ${"%.1f".format(fps)}"
            }
        }

        surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        try {
            // Request ARCore installation if needed
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return // onResume will fire again after install
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            if (session == null) {
                session = Session(this).apply {
                    configure(Config(this).apply {
                        focusMode   = Config.FocusMode.AUTO
                        updateMode  = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    })
                    renderer.setSession(this)
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

        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun showFatalError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    /** Exposed so the renderer can read display rotation on the GL thread. */
    fun getDisplayRotation(): Int {
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay.rotation
    }
}
