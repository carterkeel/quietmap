package app.quietmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The few things the shared screens need from the phone they run on. Android implements this in
 * `androidApp`; iOS implements it in the iOS app.
 */
interface PlatformBridge {
    /** A short, non-blocking message (a toast on Android). */
    fun showMessage(text: String)

    /** Copies [text] and lets the user know it was copied. */
    fun copyToClipboard(label: String, text: String)

    /** Opens the system share sheet for [text]. */
    fun shareText(text: String, title: String)

    /** Starts background sharing if sharing is on and location access was granted. */
    fun ensureSharingService()
}

/** A platform-specific row under Settings → Sharing, such as background access or battery optimisation. */
data class SettingItem(
    val title: String,
    val detail: String,
    /** Button label, or null when nothing needs fixing. */
    val action: String?,
    val onAction: () -> Unit = {},
)

val LocalPlatform = staticCompositionLocalOf<PlatformBridge> { error("No PlatformBridge provided") }

/** Seconds since the Unix epoch. */
expect fun epochSeconds(): Long

/** Whether to show speeds in mph rather than km/h, from the phone's region. */
expect fun regionUsesMiles(): Boolean

/** Handles the system back gesture or button while [enabled]. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Regions that show road speeds in mph. */
internal val MILES_REGIONS = setOf("US", "GB", "LR", "MM")
