package app.quietmap.ios

import app.quietmap.core.crypto.toBase64
import app.quietmap.core.protocol.CircleCrypto
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.min

/**
 * The system photo picker (no photo-library permission needed), then the same processing as
 * Android's PhotoProcessor: crop to the centre square, scale down and compress until it fits
 * [CircleCrypto.MAX_PHOTO_CHARS] as base64. Re-encoding drops EXIF data such as GPS tags.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPhotoPicker {
    /** PHPicker holds its delegate weakly; keep the current one alive until it finishes. */
    private var active: Delegate? = null

    fun pick(presenter: UIViewController, onResult: (String?) -> Unit) {
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration = config)
        val delegate = Delegate { result ->
            active = null
            onResult(result)
        }
        active = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    private class Delegate(private val done: (String?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, completion = null)
            val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
            if (provider == null) return done(null)
            provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                // Called on a background queue; process here, report on the main thread.
                val result = data?.let { UIImage.imageWithData(it) }?.let(::process)
                dispatch_async(dispatch_get_main_queue()) { done(result) }
            }
        }
    }

    companion object {
        fun process(image: UIImage): String? = try {
            // imageWithData uses scale 1, so size is in pixels, already rotated per the photo's orientation.
            val (w, h) = image.size.useContents { width to height }
            val side = min(w, h)
            val maxBytes = CircleCrypto.MAX_PHOTO_CHARS * 3 / 4 - 64
            var result: ByteArray? = null
            loop@ for (size in intArrayOf(320, 256, 192, 144)) {
                val target = min(size.toDouble(), side)
                val scale = target / side
                val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
                    this.scale = 1.0
                    opaque = true
                }
                val scaled = UIGraphicsImageRenderer(size = CGSizeMake(target, target), format = format).imageWithActions { _ ->
                    image.drawInRect(CGRectMake(-(w - side) / 2 * scale, -(h - side) / 2 * scale, w * scale, h * scale))
                }
                for (quality in doubleArrayOf(0.88, 0.80, 0.70, 0.60, 0.50, 0.40)) {
                    val jpeg = UIImageJPEGRepresentation(scaled, quality) ?: continue
                    if (jpeg.length.toInt() <= maxBytes) {
                        result = jpeg.toByteArray()
                        break@loop
                    }
                }
            }
            result?.toBase64()
        } catch (_: Throwable) {
            null
        }
    }
}
