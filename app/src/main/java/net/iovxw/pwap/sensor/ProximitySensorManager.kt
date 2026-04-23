package net.iovxw.pwap.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class ProximitySensorManager(
    context: Context,
    private val onTrigger: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var coverCount = 0
    private var lastCoverTime = 0L
    private var wasCovered = false
    private val requiredCovers = 4
    private val windowMs = 5000L

    fun register() {
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return

        val maxRange = event.sensor.maximumRange
        val isCovered = event.values[0] < maxRange

        if (isCovered && !wasCovered) {
            // Sensor just got covered
            val now = System.currentTimeMillis()

            if (now - lastCoverTime > windowMs) {
                coverCount = 0
            }

            coverCount++
            lastCoverTime = now

            if (coverCount >= requiredCovers) {
                coverCount = 0
                onTrigger()
            }
        }

        wasCovered = isCovered
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
