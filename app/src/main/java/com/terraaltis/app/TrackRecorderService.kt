package com.terraaltis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Registra una caminata con ubicación GPS y, cuando existe, altímetro barométrico.
 * El desnivel es una estimación filtrada de sensores del dispositivo, no una medición topográfica certificada.
 */
class TrackRecorderService : Service(), LocationListener, SensorEventListener {
    companion object {
        const val ACTION_START = "com.terraaltis.app.TRACK_START"
        const val ACTION_STOP = "com.terraaltis.app.TRACK_STOP"
        const val ACTION_UPDATE = "com.terraaltis.app.TRACK_UPDATE"
        const val EXTRA_TRACK = "track"
        const val EXTRA_KM = "km"
        const val EXTRA_ASCENT = "ascent"
        const val EXTRA_RECORDING = "recording"
        private const val CHANNEL_ID = "terraaltis_tracking"
        private const val NOTIFICATION_ID = 42
    }

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private val track = JSONArray()
    private var recording = false
    private var firstPressure: Float? = null
    private var firstGpsAltitude: Double? = null
    private var filteredAltitude: Double? = null
    private var lastAcceptedAltitude: Double? = null
    private var ascentReference: Double? = null
    private var ascentMeters = 0.0
    private var distanceMeters = 0.0
    private var lastLocation: Location? = null
    private var lastBroadcastMs = 0L
    private var hasBarometer = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBarometer = pressureSensor != null
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recording) return
        resetTrack()
        recording = true
        startForeground(NOTIFICATION_ID, buildNotification("Registrando caminata"))
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 1f, this, Looper.getMainLooper()
            )
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            broadcastUpdate(force = true)
        } catch (e: SecurityException) {
            recording = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName)
                .putExtra(EXTRA_TRACK, "[]").putExtra(EXTRA_KM, 0.0)
                .putExtra(EXTRA_ASCENT, 0.0).putExtra(EXTRA_RECORDING, false)
                .putExtra("error", "Permiso de ubicación denegado"))
        }
    }

    private fun stopRecording() {
        if (!recording) {
            stopSelf()
            return
        }
        recording = false
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        broadcastUpdate(force = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetTrack() {
        while (track.length() > 0) track.remove(track.length() - 1)
        firstPressure = null
        firstGpsAltitude = null
        filteredAltitude = null
        lastAcceptedAltitude = null
        ascentReference = null
        ascentMeters = 0.0
        distanceMeters = 0.0
        lastLocation = null
        lastBroadcastMs = 0L
    }

    override fun onLocationChanged(location: Location) {
        if (!recording || !location.hasAccuracy() || location.accuracy > 25f) return
        val gpsAltitude = altitudeOf(location)
        if (firstGpsAltitude == null && gpsAltitude != null) {
            firstGpsAltitude = gpsAltitude
            if (filteredAltitude == null) filteredAltitude = gpsAltitude
        }

        val previous = lastLocation
        if (previous != null) {
            val step = previous.distanceTo(location).toDouble()
            // Evita sumar ruido de GPS cuando la persona está quieta o la señal fluctúa.
            if (step in 1.5..30.0) distanceMeters += step
        }
        lastLocation = Location(location)

        val altitude = filteredAltitude ?: gpsAltitude ?: Double.NaN
        if (altitude.isFinite()) {
            updateAscent(altitude)
            track.put(JSONArray().put(location.latitude).put(location.longitude).put(altitude))
        } else {
            track.put(JSONArray().put(location.latitude).put(location.longitude).put(JSONObject.NULL))
        }

        val now = System.currentTimeMillis()
        if (now - lastBroadcastMs >= 1000L) broadcastUpdate()
        updateNotification()
    }

    private fun altitudeOf(location: Location): Double? {
        val available = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.hasAltitude()
        } else {
            location.altitude != 0.0
        }
        return if (available) location.altitude else null
    }

    private fun updateAscent(altitude: Double) {
        val last = lastAcceptedAltitude
        if (last == null) {
            lastAcceptedAltitude = altitude
            ascentReference = altitude
            return
        }
        val delta = altitude - last
        // Umbral de 1,5 m para reducir pequeñas oscilaciones del barómetro/GPS.
        val threshold = if (hasBarometer) 1.5 else 4.0
        if (abs(delta) >= threshold) {
            if (delta > 0) ascentMeters += delta
            lastAcceptedAltitude = altitude
            ascentReference = altitude
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording || event.sensor.type != Sensor.TYPE_PRESSURE) return
        val pressure = event.values.firstOrNull() ?: return
        if (firstPressure == null) {
            firstPressure = pressure
            return
        }
        val baseAltitude = firstGpsAltitude ?: 0.0
        val pressureAltitude = baseAltitude + 44330.0 * (1.0 - Math.pow(
            (pressure / firstPressure!!).toDouble(), 0.190294957
        ))
        filteredAltitude = filteredAltitude?.let { it * 0.8 + pressureAltitude * 0.2 } ?: pressureAltitude
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER && recording) {
            sendError("GPS desactivado; el registro puede perder precisión")
        }
    }

    override fun onProviderEnabled(provider: String) = Unit

    private fun broadcastUpdate(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastBroadcastMs < 1000L) return
        lastBroadcastMs = System.currentTimeMillis()
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName)
            .putExtra(EXTRA_TRACK, track.toString())
            .putExtra(EXTRA_KM, distanceMeters / 1000.0)
            .putExtra(EXTRA_ASCENT, ascentMeters)
            .putExtra(EXTRA_RECORDING, recording)
            .putExtra("barometer", hasBarometer))
    }

    private fun sendError(message: String) {
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName)
            .putExtra(EXTRA_TRACK, track.toString())
            .putExtra(EXTRA_KM, distanceMeters / 1000.0)
            .putExtra(EXTRA_ASCENT, ascentMeters)
            .putExtra(EXTRA_RECORDING, recording)
            .putExtra("error", message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Registro de caminatas", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TerraAltis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(recording)
            .build()

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(
            "Distancia %.2f km · D+ %d m".format(distanceMeters / 1000.0, ascentMeters.toInt())
        ))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

}
