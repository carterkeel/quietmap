package app.quietmap.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Locale

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000

actual fun regionUsesMiles(): Boolean = Locale.getDefault().country in MILES_REGIONS

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
