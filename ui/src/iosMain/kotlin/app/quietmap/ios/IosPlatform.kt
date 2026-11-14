package app.quietmap.ios

import app.quietmap.ui.PlatformBridge
import app.quietmap.ui.SettingItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/** iOS side of the shared screens. */
class IosPlatform(private val location: IosLocationService) : PlatformBridge {
    private val _message = MutableStateFlow<String?>(null)
    /** The message to show at the bottom of the screen, if any; iOS has no toasts. */
    val message: StateFlow<String?> = _message.asStateFlow()

    override fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage(text: String) {
        if (_message.value == text) _message.value = null
    }

    override fun copyToClipboard(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
        showMessage("Copied")
    }

    override fun shareText(text: String, title: String) {
        val presenter = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        // iPad shows the share sheet as a popover, which needs an anchor.
        sheet.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(sheet, animated = true, completion = null)
    }

    override fun ensureSharingService() = location.update()

    private val photoPicker = IosPhotoPicker()

    override fun pickProfilePhoto(onResult: (String?) -> Unit) {
        val presenter = topViewController() ?: return onResult(null)
        photoPicker.pick(presenter, onResult)
    }

    private val qrScanner = IosQrScanner(::showMessage)

    override fun scanQrCode(onResult: (String?) -> Unit) {
        val presenter = topViewController() ?: return onResult(null)
        qrScanner.scan(presenter, onResult)
    }

    // iOS has no persistent notification; the system location arrow is the sign that sharing is on.
    override val sharingIndicatorText = "The location arrow shows at the top of the screen while you're sharing"

    private val filePicker = IosFilePicker(::showMessage)

    override fun saveTextFile(fileName: String, text: String, onDone: (Boolean) -> Unit) {
        val presenter = topViewController() ?: return onDone(false)
        filePicker.save(presenter, fileName, text, onDone)
    }

    override fun openTextFile(onResult: (String?) -> Unit) {
        val presenter = topViewController() ?: return onResult(null)
        filePicker.open(presenter, onResult)
    }

    /** Settings → Sharing row: "Always" access keeps sharing going in the background. */
    fun sharingSettings(hasAlways: Boolean): List<SettingItem> = listOf(
        SettingItem(
            "Allow all the time",
            if (hasAlways) "Granted. Sharing continues in the background and after a restart."
            else "Needed to keep sharing while Quietmap is in the background. Choose Location → Always.",
            if (hasAlways) null else "Open",
        ) {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null)
            }
        },
    )

    private fun topViewController(): UIViewController? {
        val window = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() }
        var top = window?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
