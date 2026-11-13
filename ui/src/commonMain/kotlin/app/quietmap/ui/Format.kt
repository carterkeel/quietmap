package app.quietmap.ui

import app.quietmap.core.Engine
import app.quietmap.core.MotionPolicy
import app.quietmap.core.model.Activity
import app.quietmap.core.model.MemberLocation

object Format {
    private val usesMiles = regionUsesMiles()

    fun ago(seconds: Long): String = when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }

    fun isOffline(loc: MemberLocation?, now: Long): Boolean = loc == null || now - loc.time > Engine.OFFLINE_AFTER_SECONDS

    fun speed(mps: Float): String =
        if (usesMiles) "${(mps * 2.23694f).toInt()} mph" else "${(mps * 3.6f).toInt()} km/h"

    fun activity(a: Activity): String = when (a) {
        Activity.STILL -> "Still"
        Activity.WALKING -> "Walking"
        Activity.CYCLING -> "Cycling"
        Activity.DRIVING -> "Driving"
        Activity.UNKNOWN -> "Moving"
    }

    private fun isMovingActivity(a: Activity) = a == Activity.WALKING || a == Activity.CYCLING || a == Activity.DRIVING

    /**
     * "Driving · 42 mph · just now", "Still · 64% · 3 min ago", or "Offline · last seen 2 h ago".
     *
     * A moving update older than about two send intervals is shown as "Was driving" without a
     * speed, since the person may well have stopped since.
     */
    fun status(loc: MemberLocation?, now: Long, showBattery: Boolean = true): String {
        if (loc == null) return "No location yet"
        val age = (now - loc.time).coerceAtLeast(0)
        if (isOffline(loc, now)) {
            val why = loc.status?.let { " ($it)" } ?: ""
            return "Offline$why · last seen ${ago(age)}"
        }
        val stale = isMovingActivity(loc.activity) && age > 2 * MotionPolicy.MOVING_INTERVAL + 30
        val parts = mutableListOf<String>()
        if (stale) {
            parts += "Was ${activity(loc.activity).lowercase()}"
        } else {
            parts += activity(loc.activity)
            val s = loc.speed
            if (s != null && (loc.activity == Activity.DRIVING || loc.activity == Activity.CYCLING)) parts += speed(s)
        }
        if (showBattery) loc.battery?.let { parts += "$it%" }
        parts += ago(age)
        return parts.joinToString(" · ")
    }

    fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
