package app.quietmap.core

import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.model.Activity
import app.quietmap.core.model.LocationSample
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.model.Point
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides how often to publish. Only two rates exist (moving / still), plus jitter, so relays
 * can tell at most whether someone is moving, never whether they are walking or driving.
 * The actual activity travels inside the encrypted update.
 */
class MotionPolicy {
    private val buffer = ArrayDeque<LocationSample>()

    fun add(sample: LocationSample) {
        buffer.addLast(sample)
        val cutoff = sample.time - 5 * 60
        while (buffer.isNotEmpty() && buffer.first().time < cutoff) buffer.removeFirst()
    }

    val latest: LocationSample? get() = buffer.lastOrNull()

    /** Speed in m/s: the fix's own speed when present, otherwise distance over the last minute. */
    fun speed(): Float? {
        val last = buffer.lastOrNull() ?: return null
        last.speed?.let { return it }
        val earlier = buffer.lastOrNull { it.time <= last.time - 45 } ?: return null
        val dt = (last.time - earlier.time).toFloat()
        if (dt <= 0f) return null
        return (distanceMeters(earlier.lat, earlier.lon, last.lat, last.lon) / dt).toFloat()
    }

    fun activity(): Activity {
        val s = speed() ?: return Activity.UNKNOWN
        return when {
            s < 0.6f -> Activity.STILL
            s < 2.5f -> Activity.WALKING
            s < 7.5f -> Activity.CYCLING
            else -> Activity.DRIVING
        }
    }

    fun isMoving(): Boolean = activity().let { it != Activity.STILL && it != Activity.UNKNOWN }

    /** Seconds until the next publish: ~60 s while moving, ~5 min while still, ±15% jitter. */
    fun nextIntervalSeconds(): Long {
        val base = if (isMoving()) MOVING_INTERVAL else STILL_INTERVAL
        val r = (PlatformCrypto.randomBytes(1)[0].toInt() and 0xff) / 255.0 // 0..1
        return (base * (0.85 + 0.3 * r)).toLong()
    }

    /** Builds the update payload, including up to six recent points so viewers can replay the path. */
    fun snapshot(battery: Int?, status: String? = null): MemberLocation? {
        val last = buffer.lastOrNull() ?: return null
        val points = buffer.filter { it.time >= last.time - 60 }.takeLast(6).map { Point(round6(it.lat), round6(it.lon), it.time) }
        return MemberLocation(
            lat = round6(last.lat),
            lon = round6(last.lon),
            accuracy = last.accuracy,
            speed = speed(),
            heading = last.heading,
            activity = activity(),
            battery = battery,
            time = last.time,
            points = points,
            status = status,
        )
    }

    companion object {
        const val MOVING_INTERVAL = 60L
        const val STILL_INTERVAL = 300L

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
