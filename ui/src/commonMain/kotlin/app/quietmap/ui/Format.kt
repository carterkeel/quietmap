package app.quietmap.ui

import app.quietmap.core.Engine
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

    /** "Driving · 42 mph · 2 min ago", or "Offline · last seen 2 h ago". */
    fun status(loc: MemberLocation?, now: Long): String {
        if (loc == null) return "No location yet"
        val age = (now - loc.time).coerceAtLeast(0)
        if (isOffline(loc, now)) {
            val why = loc.status?.let { " ($it)" } ?: ""
            return "Offline$why · last seen ${ago(age)}"
        }
        val parts = mutableListOf(activity(loc.activity))
        val s = loc.speed
        if (s != null && (loc.activity == Activity.DRIVING || loc.activity == Activity.CYCLING)) parts += speed(s)
        loc.battery?.let { parts += "$it%" }
        parts += ago(age)
        return parts.joinToString(" · ")
    }

    fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
