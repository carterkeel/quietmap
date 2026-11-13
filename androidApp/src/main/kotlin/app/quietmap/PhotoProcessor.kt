package app.quietmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import app.quietmap.core.crypto.toBase64
import app.quietmap.core.protocol.CircleCrypto
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Turns a picked photo into a small square JPEG for a profile picture. It is cropped to the
 * centre, scaled down and compressed until it fits [CircleCrypto.MAX_PHOTO_CHARS] as base64, so it
 * travels inside one encrypted event. Re-encoding also drops EXIF data such as GPS tags.
 */
object PhotoProcessor {
    fun process(context: Context, uri: Uri): String? = try {
        val source = decode(context, uri) ?: return null
        val side = min(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val maxBytes = CircleCrypto.MAX_PHOTO_CHARS * 3 / 4 - 64
        var result: ByteArray? = null
        loop@ for (size in intArrayOf(320, 256, 192, 144)) {
            val scaled = Bitmap.createScaledBitmap(square, min(size, side), min(size, side), true)
            for (quality in intArrayOf(88, 80, 70, 60, 50, 40)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= maxBytes) {
                    result = out.toByteArray()
                    break@loop
                }
            }
        }
        result?.toBase64()
    } catch (_: Throwable) {
        null
    }

    private fun decode(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the photo's rotation; downsample big photos while decoding.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 1280) decoder.setTargetSampleSize(longest / 1280)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = (longest / 1280).coerceAtLeast(1) }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
}
