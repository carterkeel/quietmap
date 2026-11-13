package app.quietmap.core

import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.model.Activity
import app.quietmap.core.model.LocationSample
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.model.Point
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides when to publish and what activity to report.
 *
 * Only two steady rates exist (moving / still) plus jitter, so relays can tell at most whether
 * someone is moving. On top of that, one extra update goes out when someone starts or stops
 * moving, so the circle never sees "Driving" for minutes after you've parked, or waits five
 * minutes to see you've left. The activity itself travels inside the encrypted update.
 */
class MotionPolicy {
    private val buffer = ArrayDeque<LocationSample>()
    private var activity = Activity.UNKNOWN
    private var motionHintAt: Long? = null

    private var lastPublishAt: Long? = null
    private var lastPublishedMoving: Boolean? = null
    private var jitter = 1.0

    fun add(sample: LocationSample) {
        if (buffer.isNotEmpty() && sample.time < buffer.last().time) return
        buffer.addLast(sample)
        val cutoff = sample.time - BUFFER_SECONDS
        while (buffer.isNotEmpty() && buffer.first().time < cutoff) buffer.removeFirst()
        activity = classify(estimateSpeed(), activity)
    }

    /** The phone's motion sensor says it started moving; treat as moving until fixes say otherwise. */
    fun motionHint(now: Long) {
        motionHintAt = now
    }

    val latest: LocationSample? get() = buffer.lastOrNull()

    fun activity(): Activity = activity

    fun isMoving(now: Long): Boolean = when (activity) {
        Activity.WALKING, Activity.CYCLING, Activity.DRIVING -> true
        Activity.STILL -> false
        Activity.UNKNOWN -> motionHintAt?.let { now - it < HINT_SECONDS } == true
    }

    /** Whether location should be collected with GPS (moving, just woke up, or no fix yet). */
    fun wantsHighAccuracy(now: Long): Boolean = latest == null || isMoving(now) || motionHintAt?.let { now - it < HINT_SECONDS } == true

    fun shouldPublish(now: Long): Boolean {
        if (latest == null) return false
        val last = lastPublishAt ?: return true
        val moving = isMoving(now)
        val elapsed = now - last
        if (lastPublishedMoving != null && moving != lastPublishedMoving && elapsed >= MIN_GAP_SECONDS) return true
        val base = if (moving) MOVING_INTERVAL else STILL_INTERVAL
        return elapsed >= (base * jitter).toLong()
    }

    fun markPublished(now: Long) {
        lastPublishAt = now
        lastPublishedMoving = isMoving(now)
        val r = (PlatformCrypto.randomBytes(1)[0].toInt() and 0xff) / 255.0 // 0..1
        jitter = 0.85 + 0.3 * r
    }

    /**
     * Speed in m/s. Uses only fixes accurate to 30 m: the median of the fixes' own speeds over the
     * last 30 s, otherwise distance over time, counting movement only when it beats the GPS error.
     */
    fun estimateSpeed(): Float? {
        val last = buffer.lastOrNull() ?: return null
        val good = buffer.filter { it.accuracy <= GOOD_ACCURACY && it.time >= last.time - SPEED_WINDOW }
        val reported = good.mapNotNull { it.speed }
        if (reported.size >= 2 || (reported.size == 1 && good.size == 1)) return reported.sorted()[reported.size / 2]
        if (good.size >= 2) {
            val a = good.first()
            val b = good.last()
            val dt = (b.time - a.time).toFloat()
            if (dt >= 10f) {
                val d = distanceMeters(a.lat, a.lon, b.lat, b.lon)
                return if (d <= max(a.accuracy, b.accuracy)) 0f else (d / dt).toFloat()
            }
        }
        // Only coarse fixes (network location): call it movement only for a clear jump.
        val older = buffer.firstOrNull { it.time <= last.time - 45 } ?: return null
        val d = distanceMeters(older.lat, older.lon, last.lat, last.lon)
        val noise = 2 * max(older.accuracy, last.accuracy)
        val dt = (last.time - older.time).toFloat()
        return if (d > max(noise.toDouble(), 75.0)) (d / dt).toFloat() else 0f
    }

    /** Builds the update payload, including up to six recent points so viewers can replay the path. */
    fun snapshot(battery: Int?, status: String? = null): MemberLocation? {
        val last = buffer.lastOrNull() ?: return null
        val points = buffer.filter { it.time >= last.time - 60 }.takeLast(6).map { Point(round6(it.lat), round6(it.lon), it.time) }
        return MemberLocation(
            lat = round6(last.lat),
            lon = round6(last.lon),
            accuracy = last.accuracy,
            speed = estimateSpeed(),
            heading = last.heading,
            activity = activity,
            battery = battery,
            time = last.time,
            points = points,
            status = status,
        )
    }

    companion object {
        const val MOVING_INTERVAL = 60L
        const val STILL_INTERVAL = 300L
        private const val MIN_GAP_SECONDS = 20L
        private const val HINT_SECONDS = 120L
        private const val BUFFER_SECONDS = 5 * 60L
        private const val SPEED_WINDOW = 30L
        private const val GOOD_ACCURACY = 30f

        // Upper speed bounds in m/s: still < 1.8 km/h, walking < 9 km/h, cycling < 25 km/h.
        private val BOUNDS = listOf(Activity.STILL to 0.5f, Activity.WALKING to 2.5f, Activity.CYCLING to 7f)

        /** Classifies speed, with 15% hysteresis so a reading near a boundary doesn't flip back and forth. */
        fun classify(speed: Float?, previous: Activity): Activity {
            if (speed == null) return previous
            fun bucket(s: Float): Activity = BOUNDS.firstOrNull { s < it.second }?.first ?: Activity.DRIVING
            val raw = bucket(speed)
            if (previous == Activity.UNKNOWN || raw == previous) return raw
            val lowered = bucket(speed * 1.15f)
            val raised = bucket(speed * 0.85f)
            return if (lowered == previous || raised == previous) previous else raw
        }

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = (lat2 - lat1) * PI / 180
            val dLon = (lon2 - lon1) * PI / 180
            val a = sin(dLat / 2).pow(2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }

        private fun round6(v: Double): Double = kotlin.math.round(v * 1_000_000) / 1_000_000
    }
}
