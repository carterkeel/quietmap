package app.quietmap

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.quietmap.core.MotionPolicy
import app.quietmap.core.model.LocationSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that collects location fixes and publishes encrypted updates.
 *
 * While still it uses cheap network location and the phone's low-power significant-motion sensor.
 * When that sensor fires (or network fixes jump), it switches to GPS within seconds, and an update
 * goes out as soon as movement is confirmed instead of waiting out the 5-minute still interval.
 *
 * The persistent notification is required by Android, and doubles as an anti-stalkerware
 * signal: you can always see that Quietmap is sharing, and with how many people.
 */
class LocationService : LifecycleService(), LocationListener {
    private val policy = MotionPolicy()
    private lateinit var locationManager: LocationManager
    private var sensorManager: SensorManager? = null
    private var significantMotion: Sensor? = null
    private var highAccuracy: Boolean? = null

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            policy.motionHint(nowSeconds())
            adjustAccuracy()
            armMotionSensor() // one-shot sensor: re-arm for the next time
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)!!
        sensorManager = getSystemService(SensorManager::class.java)
        significantMotion = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        startInForeground(0)
        val app = QuietmapApp.from(this)

        lifecycleScope.launch {
            app.engine.state
                .map { s -> Triple(s.sharing, s.circles.isEmpty(), s.circles.flatMap { c -> c.members.map { it.pubkey } }.toSet().size - 1) }
                .distinctUntilChanged()
                .collect { (sharing, noCircles, people) ->
                    if (!sharing || noCircles) stopSelf() else startInForeground(people.coerceAtLeast(0))
                }
        }

        // Joined a circle, or someone new arrived: send our location now instead of at the next
        // interval (up to 5 minutes away while still).
        lifecycleScope.launch {
            app.engine.state
                .map { s -> s.circles.map { c -> c.id to c.members.size } }
                .distinctUntilChanged()
                .collect { policy.publishSoon() }
        }

        armMotionSensor()
        requestUpdates(highAccuracy = true)
        lifecycleScope.launch {
            while (isActive) {
                val now = nowSeconds()
                adjustAccuracy()
                if (policy.shouldPublish(now)) {
                    val battery = batteryPercent()
                    val status = if (battery != null && battery <= 5) "low battery" else null
                    policy.snapshot(battery, status)?.let {
                        app.engine.publishLocation(it)
                        policy.markPublished(now)
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch { QuietmapApp.from(this@LocationService).engine.setSharing(false) }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        significantMotion?.let { sensorManager?.cancelTriggerSensor(motionListener, it) }
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        policy.add(
            LocationSample(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy else 100f,
                speed = if (location.hasSpeed() && location.provider == LocationManager.GPS_PROVIDER) location.speed else null,
                heading = if (location.hasBearing()) location.bearing else null,
                time = location.time / 1000,
            ),
        )
        adjustAccuracy()
    }

    @Deprecated("Required on older Android versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun armMotionSensor() {
        significantMotion?.let { sensorManager?.requestTriggerSensor(motionListener, it) }
    }

    /** GPS while moving (or just woken by the motion sensor); cheap network location while still. */
    private fun adjustAccuracy() {
        val wantHigh = policy.wantsHighAccuracy(nowSeconds())
        if (wantHigh != highAccuracy) requestUpdates(wantHigh)
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(highAccuracy: Boolean) {
        this.highAccuracy = highAccuracy
        try {
            locationManager.removeUpdates(this)
            val providers = locationManager.getProviders(true)
            val hasNetwork = LocationManager.NETWORK_PROVIDER in providers
            val hasGps = LocationManager.GPS_PROVIDER in providers
            if (hasGps && (highAccuracy || !hasNetwork)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, if (highAccuracy) 5_000L else 60_000L, 0f, this, Looper.getMainLooper())
            }
            if (hasNetwork) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, if (highAccuracy) 15_000L else 30_000L, 0f, this, Looper.getMainLooper())
            }
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun batteryPercent(): Int? =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun startInForeground(people: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(people), type)
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    private fun buildNotification(people: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (people) {
            0 -> "Sharing is on. Nobody else is in your circles yet."
            1 -> "Sharing your location with 1 person"
            else -> "Sharing your location with $people people"
        }
        return NotificationCompat.Builder(this, QuietmapApp.CHANNEL_SHARING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Quietmap")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.quietmap.STOP"
        private const val TICK_MS = 5_000L
    }
}
