package com.photogrammetry

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Monitors the Tensor G4's thermal status and adapts scan quality in real-time
 * to prevent sustained CPU/GPU throttling on the Pixel 9 Pro.
 *
 * Pixel 9 Pro thermal tiers → scan behaviour:
 * ┌──────────────────┬───────┬───────────────┬──────────────────────────────────────────────┐
 * │ Thermal status   │  FPS  │  depthStride  │ Notes                                        │
 * ├──────────────────┼───────┼───────────────┼──────────────────────────────────────────────┤
 * │ NONE / LIGHT     │  60   │      2        │ Full scan quality, depth overlay enabled      │
 * │ MODERATE         │  60   │      2        │ Normal operation; chip is warm but stable     │
 * │ SEVERE           │  30   │      4        │ Drop FPS, sample every 4th depth pixel        │
 * │ CRITICAL         │  30   │      6        │ Pause accumulation; coarse depth sampling     │
 * │ EMERGENCY        │  30   │      6        │ Stop all accumulation; display only           │
 * └──────────────────┴───────┴───────────────┴──────────────────────────────────────────────┘
 *
 * Improvement #10 — render-mode callback:
 *   [onRenderModeChange] is called with [continuousRender]=true under normal
 *   conditions and [continuousRender]=false at SEVERE+ heat.  The caller should
 *   switch the GLSurfaceView to RENDERMODE_WHEN_DIRTY accordingly to cut GPU
 *   idle power consumption.
 *
 * Both [onThrottleChanged] and [onRenderModeChange] are dispatched on the main
 * thread via [Context.mainExecutor].
 */
class ThermalManager(
    private val context:            Context,
    private val onThrottleChanged:  (fps: Int, accumulate: Boolean, depthStride: Int, dirtyBatchSize: Int) -> Unit,
    private val onRenderModeChange: ((continuousRender: Boolean) -> Unit)? = null
) : PowerManager.OnThermalStatusChangedListener {

    companion object {
        private const val TAG = "ThermalManager"
    }

    private data class Throttle(val fps: Int, val accumulate: Boolean,
                                val depthStride: Int, val dirtyBatchSize: Int)

    private val pm = context.getSystemService(PowerManager::class.java)

    fun register() {
        pm.addThermalStatusListener(context.mainExecutor, this)
        // Apply the current thermal status immediately on registration
        onThermalStatusChanged(pm.currentThermalStatus)
        Log.i(TAG, "Thermal monitoring active. Current status: ${pm.currentThermalStatus}")
    }

    fun unregister() {
        pm.removeThermalStatusListener(this)
    }

    override fun onThermalStatusChanged(status: Int) {
        val t = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT     -> Throttle(60, true,  2, 5_000)
            PowerManager.THERMAL_STATUS_MODERATE  -> Throttle(60, true,  2, 5_000)
            PowerManager.THERMAL_STATUS_SEVERE    -> Throttle(30, true,  4, 10_000)
            PowerManager.THERMAL_STATUS_CRITICAL  -> Throttle(30, false, 6, 20_000)
            PowerManager.THERMAL_STATUS_EMERGENCY -> Throttle(30, false, 6, 20_000)
            else                                  -> Throttle(60, true,  2, 5_000)
        }
        val continuous = status < PowerManager.THERMAL_STATUS_SEVERE
        Log.w(TAG, "Thermal status $status → fps=${t.fps} accumulate=${t.accumulate} stride=${t.depthStride} batchSize=${t.dirtyBatchSize} continuous=$continuous")
        onThrottleChanged(t.fps, t.accumulate, t.depthStride, t.dirtyBatchSize)
        onRenderModeChange?.invoke(continuous)
    }
}
