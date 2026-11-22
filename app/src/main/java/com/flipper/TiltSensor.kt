package com.flipper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import kotlin.math.abs

/**
 * 姿态判决：加速度计阈值方案（flipper-v1 真机验证过的公式，原样保留）。
 *
 * 手机坐标系：x=右，y=屏幕上方向，z=出屏。静止时加速度计读数为重力反作用：
 * 竖持头朝上 y≈+9.8，倒持 y≈-9.8；头朝左横持 x≈+9.8（ROTATION_90），头朝右
 * x≈-9.8（ROTATION_270）。
 * 阈值 6 m/s² 对应约 37°，比系统判决更钝一点，躺着看视频不会误翻。
 * 两道去抖：连续 3 帧同一目标才上报；平放（|x|、|y| 都小）不上报。
 */
class TiltSensor(
    context: Context,
    private val onTilt: (Int) -> Unit,
) {

    private val manager = context.getSystemService(SensorManager::class.java)
    private var registered = false

    private var streakTarget = -1
    private var streak = 0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val phys = when {
                abs(x) < THRESHOLD && abs(y) < THRESHOLD -> NO_TILT // 平放/近水平，保持
                abs(y) >= abs(x) && y > 0 -> Surface.ROTATION_0
                abs(y) >= abs(x) && y < 0 -> Surface.ROTATION_180
                x < 0 -> Surface.ROTATION_270
                else -> Surface.ROTATION_90
            }
            if (phys == NO_TILT) {
                streak = 0
                streakTarget = -1
                return
            }
            if (phys == streakTarget) {
                streak++
            } else {
                streakTarget = phys
                streak = 1
            }
            if (streak >= CONFIRM_FRAMES) {
                onTilt(phys)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun register() {
        if (registered) return
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        if (!registered) Log.w(TAG, "accelerometer register failed")
    }

    fun unregister() {
        if (!registered) return
        manager?.unregisterListener(listener)
        registered = false
        streak = 0
        streakTarget = -1
    }

    private companion object {
        const val TAG = "flipper"
        const val NO_TILT = -1
        const val THRESHOLD = 6f
        const val CONFIRM_FRAMES = 3
    }
}
