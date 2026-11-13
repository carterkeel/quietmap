package app.quietmap

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.quietmap.core.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class QuietmapApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: Engine by lazy {
        Engine(
            store = SecureStore(this),
            scope = scope,
            now = { System.currentTimeMillis() / 1000 },
            log = { Log.d("Quietmap", it) },
        ).also { it.start() }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)!!.createNotificationChannel(
            NotificationChannel(CHANNEL_SHARING, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Quietmap shares your location with your circles"
            },
        )
        engine // start relays
        // Start sharing the moment you're let into a circle (or create one), not the next time
        // the app comes to the front.
        scope.launch {
            engine.state.map { it.sharing && it.circles.isNotEmpty() }.distinctUntilChanged().collect { if (it) ensureSharingService() }
        }
    }

    /** Starts the sharing service if sharing is on and location access was granted. */
    fun ensureSharingService() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val s = engine.state.value
        if (granted && s.sharing && s.circles.isNotEmpty()) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
            } catch (t: Throwable) {
                Log.w("Quietmap", "could not start sharing service: ${t.message}")
            }
        }
    }

    companion object {
        const val CHANNEL_SHARING = "sharing"
        fun from(context: Context): QuietmapApp = context.applicationContext as QuietmapApp
    }
}
