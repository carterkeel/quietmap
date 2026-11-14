package app.quietmap.ios

import app.quietmap.core.Engine
import app.quietmap.core.MotionPolicy
import app.quietmap.core.model.LocationSample
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.darwin.NSObject

/**
 * The iOS counterpart of Android's LocationService: collects fixes and publishes encrypted
 * updates on the same moving/still rhythm.
 *
 * iOS suspends apps freely; Quietmap stays running only while location updates are on. So
 * updates stay on the whole time sharing is on (GPS while moving, coarse while still) with
 * automatic pausing off. iOS shows the blue location indicator while this runs, the
 * counterpart of Android's persistent notification; people can hide it in Settings
 * ([setShowIndicator]), and the status-bar location arrow still shows. Significant-change monitoring makes iOS
 * relaunch the app after a reboot or force-quit once the phone moves about 500 m.
 *
 * Everything here runs on the main thread, where CoreLocation delivers its callbacks.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationService(private val engine: Engine, private val scope: CoroutineScope) {
    private val manager = CLLocationManager()
    private val delegate = Delegate()
    private val policy = MotionPolicy()
    private var publishJob: Job? = null
    private var highAccuracy: Boolean? = null

    private val _authorization = MutableStateFlow(manager.authorizationStatus)
    /** Current location permission; recomposes the UI when the user changes it. */
    val authorization: StateFlow<CLAuthorizationStatus> = _authorization.asStateFlow()

    val hasPermission: Boolean get() = authorization.value.let { it == kCLAuthorizationStatusAuthorizedAlways || it == kCLAuthorizationStatusAuthorizedWhenInUse }
    val hasAlways: Boolean get() = authorization.value == kCLAuthorizationStatusAuthorizedAlways

    private val _showIndicator = MutableStateFlow(
        NSUserDefaults.standardUserDefaults.let { it.objectForKey(SHOW_INDICATOR_KEY) == null || it.boolForKey(SHOW_INDICATOR_KEY) },
    )
    /**
     * Whether to ask iOS for the blue location pill (Dynamic Island or status bar) while sharing
     * in the background. On by default. iOS only honours "off" with Always access; with While
     * Using it shows the pill anyway, and the small location arrow shows either way.
     */
    val showIndicator: StateFlow<Boolean> = _showIndicator.asStateFlow()

    fun setShowIndicator(show: Boolean) {
        _showIndicator.value = show
        NSUserDefaults.standardUserDefaults.setBool(show, SHOW_INDICATOR_KEY)
        if (publishJob?.isActive == true && canRunInBackground) manager.showsBackgroundLocationIndicator = show
    }

    init {
        manager.delegate = delegate
        manager.pausesLocationUpdatesAutomatically = false
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        // Follow sharing on/off and circles coming and going, like Android's service does.
        scope.launch(Dispatchers.Main) {
            engine.state
                .map { it.sharing && it.circles.isNotEmpty() }
                .distinctUntilChanged()
                .collect { update() }
        }
        // Joined a circle, or someone new arrived: send our location now instead of at the next
        // interval (up to 5 minutes away while still), as Android's service does.
        scope.launch(Dispatchers.Main) {
            engine.state
                .map { s -> s.circles.map { c -> c.id to c.members.size } }
                .distinctUntilChanged()
                .collect { policy.publishSoon() }
        }
    }

    /**
     * Asks for location access. iOS only allows "Always" as a second step after "While Using",
     * so the first call asks for While Using and a later call (once sharing is set up) for Always.
     */
    fun requestPermission() {
        when (authorization.value) {
            kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()
            kCLAuthorizationStatusAuthorizedWhenInUse -> manager.requestAlwaysAuthorization()
            else -> Unit
        }
    }

    /** Starts or stops sharing to match the current state and permission. Call on the main thread. */
    fun update() {
        val s = engine.state.value
        if (hasPermission && s.sharing && s.circles.isNotEmpty()) start() else stop()
    }

    private fun start() {
        if (publishJob?.isActive == true) return
        // Second step of the permission flow: iOS shows the "Always" prompt at most once.
        if (authorization.value == kCLAuthorizationStatusAuthorizedWhenInUse) manager.requestAlwaysAuthorization()
        if (canRunInBackground) {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = showIndicator.value
        }
        setAccuracy(high = true)
        manager.startUpdatingLocation()
        if (CLLocationManager.significantLocationChangeMonitoringAvailable()) manager.startMonitoringSignificantLocationChanges()
        // Same loop as Android's LocationService: MotionPolicy decides each tick whether an update
        // is due (steady moving/still rates, plus one extra when someone starts or stops moving).
        publishJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val now = nowSeconds()
                adjustAccuracy()
                if (policy.shouldPublish(now)) {
                    val battery = batteryPercent()
                    val status = if (battery != null && battery <= 5) "low battery" else null
                    policy.snapshot(battery, status)?.let { loc ->
                        policy.markPublished(now)
                        launch(Dispatchers.Default) { runCatching { engine.publishLocation(loc) } }
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stop() {
        publishJob?.cancel()
        publishJob = null
        highAccuracy = null
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
    }

    private fun adjustAccuracy() = setAccuracy(high = policy.wantsHighAccuracy(nowSeconds()))

    private fun nowSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

    /**
     * GPS while moving; coarse location while still, which still keeps the app running. While
     * still, the 25 m distance filter plays the part of Android's significant-motion sensor: the
     * first fix after moving that far shows movement, and the next tick switches to GPS.
     */
    private fun setAccuracy(high: Boolean) {
        if (high == highAccuracy) return
        highAccuracy = high
        manager.desiredAccuracy = if (high) kCLLocationAccuracyBest else kCLLocationAccuracyHundredMeters
        manager.distanceFilter = if (high) kCLDistanceFilterNone else 25.0
    }

    private fun batteryPercent(): Int? =
        UIDevice.currentDevice.batteryLevel.takeIf { it >= 0f }?.let { (it * 100).toInt().coerceIn(0, 100) }

    private fun onLocation(location: CLLocation) {
        if (location.horizontalAccuracy < 0) return // invalid fix
        val (lat, lon) = location.coordinate.useContents { latitude to longitude }
        policy.add(
            LocationSample(
                lat = lat,
                lon = lon,
                accuracy = location.horizontalAccuracy.toFloat(),
                speed = location.speed.takeIf { it >= 0 }?.toFloat(),
                heading = location.course.takeIf { it >= 0 }?.toFloat(),
                time = location.timestamp.timeIntervalSince1970.toLong(),
            ),
        )
        adjustAccuracy()
    }

    private inner class Delegate : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            didUpdateLocations.forEach { (it as? CLLocation)?.let(::onLocation) }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            _authorization.value = manager.authorizationStatus
            update()
        }
    }

    private companion object {
        const val TICK_MS = 5_000L
        const val SHOW_INDICATOR_KEY = "showLocationIndicator"

        /** Background updates crash unless Info.plist declares the `location` background mode. */
        val canRunInBackground: Boolean by lazy {
            (NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>)?.contains("location") == true
        }
    }
}
