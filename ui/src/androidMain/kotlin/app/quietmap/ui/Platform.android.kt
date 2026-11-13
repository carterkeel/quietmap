package app.quietmap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import java.util.Locale

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000

actual fun regionUsesMiles(): Boolean = Locale.getDefault().country in MILES_REGIONS

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
