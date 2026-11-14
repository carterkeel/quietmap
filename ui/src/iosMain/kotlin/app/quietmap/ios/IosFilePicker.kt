package app.quietmap.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.darwin.NSObject

/**
 * Saving and opening backup files with the system document picker (Files, iCloud Drive, other
 * apps' storage), the counterpart of Android's CreateDocument / OpenDocument.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosFilePicker(private val showMessage: (String) -> Unit) {
    /** The document picker holds its delegate weakly; keep the current one alive until it finishes. */
    private var active: Delegate? = null

    /** Writes [text] to a temporary file, then lets the user choose where to put a copy. */
    fun save(presenter: UIViewController, fileName: String, text: String, onDone: (Boolean) -> Unit) {
        val tmp = NSURL.fileURLWithPath(NSTemporaryDirectory() + fileName)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val written = (text as NSString).writeToURL(tmp, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!written) {
            showMessage("Couldn't save the backup file")
            return onDone(false)
        }
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(tmp), asCopy = true)
        present(presenter, picker) { urls ->
            // The picker copied the file where the user chose; don't leave the original behind.
            NSFileManager.defaultManager.removeItemAtURL(tmp, error = null)
            onDone(urls != null)
        }
    }

    /** Lets the user pick a backup file and returns its text, or null if cancelled or unreadable. */
    fun open(presenter: UIViewController, onResult: (String?) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypePlainText, UTTypeData), asCopy = true)
        present(presenter, picker) { urls ->
            val url = urls?.firstOrNull() as? NSURL ?: return@present onResult(null)
            val text = readSmallText(url)
            if (text == null) showMessage("Couldn't open that file")
            onResult(text)
        }
    }

    private fun present(presenter: UIViewController, picker: UIDocumentPickerViewController, done: (List<*>?) -> Unit) {
        val delegate = Delegate { urls ->
            active = null
            done(urls)
        }
        active = delegate
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    /** Backups are a few tens of KB; refuse anything huge rather than reading it all. */
    private fun readSmallText(url: NSURL): String? {
        val data = NSData.dataWithContentsOfURL(url) ?: return null
        if (data.length > MAX_BYTES) return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    private class Delegate(private val done: (List<*>?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) = done(didPickDocumentsAtURLs)
        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = done(null)
    }

    private companion object {
        const val MAX_BYTES: ULong = 524_288uL
    }
}
