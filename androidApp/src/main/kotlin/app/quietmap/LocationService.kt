package app.quietmap

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * The persistent notification is required by Android, and doubles as an anti-stalkerware
 * signal: you can always see that Quietmap is sharing, and with how many people.
 */
class LocationService : LifecycleService(), LocationListener {
    private val policy = MotionPolicy()
    private lateinit var locationManager: LocationManager
    private var highAccuracy: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)!!
        startInForeground(0)
        val app = QuietmapApp.from(this)

        lifecycleScope.launch {
            app.engine.state
                .map { s -> Triple(s.sharing, s.circles.isEmpty(), s.circles.flatMap { c -> c.members.map { it.pubkey } }.toSet().size - 1) }
                .distinctUntilChanged()
                .collect { (sharing, noCircles, people) ->
                    if (!sharing || noCircles) {
                        stopSelf()
                    } else {
                        startInForeground(people.coerceAtLeast(0))
                    }
                }
        }

        lifecycleScope.launch {
            requestUpdates(highAccuracy = true)
            // Publish soon after the first fix, then on the moving/still rhythm.
            delay(15_000)
            while (isActive) {
                adjustAccuracy()
                val battery = batteryPercent()
                val status = if (battery != null && battery <= 5) "low battery" else null
                policy.snapshot(battery, status)?.let { app.engine.publishLocation(it) }
                delay(policy.nextIntervalSeconds() * 1000)
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
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        policy.add(
            LocationSample(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = location.accuracy,
                speed = if (location.hasSpeed()) location.speed else null,
                heading = if (location.hasBearing()) location.bearing else null,
                time = location.time / 1000,
            ),
        )
    }

    @Deprecated("Required on older Android versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    /** GPS while moving; cheap network location while still. */
    private fun adjustAccuracy() {
        val wantHigh = policy.isMoving() || policy.latest == null
        if (wantHigh != highAccuracy) requestUpdates(wantHigh)
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(highAccuracy: Boolean) {
        this.highAccuracy = highAccuracy
        try {
            locationManager.removeUpdates(this)
            val providers = locationManager.getProviders(true)
            val hasNetwork = LocationManager.NETWORK_PROVIDER in providers
            if (highAccuracy || !hasNetwork) {
                if (LocationManager.GPS_PROVIDER in providers) {
                    val interval = if (highAccuracy) 10_000L else 120_000L
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f, this, Looper.getMainLooper())
                }
            }
            if (hasNetwork) {
                val interval = if (highAccuracy) 30_000L else 60_000L
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, interval, 0f, this, Looper.getMainLooper())
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

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.quietmap.STOP"

        fun stop(context: Context) = context.stopService(Intent(context, LocationService::class.java))
    }
}
