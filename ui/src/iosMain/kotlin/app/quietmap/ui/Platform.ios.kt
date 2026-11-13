package app.quietmap.ui

import androidx.compose.runtime.Composable
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.timeIntervalSince1970

actual fun epochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

actual fun regionUsesMiles(): Boolean = NSLocale.currentLocale.countryCode in MILES_REGIONS

/** iOS has no system back button; back navigation is the on-screen back arrow (swipe-back may come later). */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
