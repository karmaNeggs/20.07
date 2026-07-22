package org.offlinemesh.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Heading in degrees from magnetic north, smoothed with a short moving average since raw
 * magnetometer readings are jittery — especially in exactly the kind of environment this app
 * targets (metal barricades, vehicles, structures near a protest/crowd). Used to rotate the
 * radar to "forward is up" so a panicked, moving person can just walk toward the dot instead
 * of mentally translating a north-up map — worth the tradeoff even though the sensor itself
 * is the least reliable part of this feature; see lowAccuracy below.
 */
class CompassTracker(context: Context) {
    private val _headingDegrees = MutableStateFlow(0f)
    val headingDegrees: StateFlow<Float> = _headingDegrees

    private val _lowAccuracy = MutableStateFlow(false)
    val lowAccuracy: StateFlow<Boolean> = _lowAccuracy

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val recent = ArrayDeque<Float>()
    private val smoothingWindow = 5

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (degrees < 0) degrees += 360f

            recent.addLast(degrees)
            if (recent.size > smoothingWindow) recent.removeFirst()
            _headingDegrees.value = circularMean(recent)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            _lowAccuracy.value = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        }
    }

    private fun circularMean(values: Collection<Float>): Float {
        var sinSum = 0.0
        var cosSum = 0.0
        for (v in values) {
            val rad = Math.toRadians(v.toDouble())
            sinSum += Math.sin(rad)
            cosSum += Math.cos(rad)
        }
        var mean = Math.toDegrees(Math.atan2(sinSum, cosSum))
        if (mean < 0) mean += 360.0
        return mean.toFloat()
    }

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
