package app.quietmap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import app.quietmap.ui.PlatformBridge
import app.quietmap.ui.SettingItem

/**
 * Android side of the shared screens. [context] should be the activity, so the share sheet opens
 * over it. Photo picking and QR scanning go through launchers the activity registers.
 */
class AndroidPlatform(
    private val context: Context,
    private val photoPicker: ((String?) -> Unit) -> Unit,
    private val qrScanner: ((String?) -> Unit) -> Unit,
    private val fileSaver: (name: String, text: String, onDone: (Boolean) -> Unit) -> Unit,
    private val fileOpener: ((String?) -> Unit) -> Unit,
    private val requestBackgroundLocation: () -> Unit,
) : PlatformBridge {
    private val app = QuietmapApp.from(context)

    override fun showMessage(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    override fun copyToClipboard(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    override fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    override fun ensureSharingService() = app.ensureSharingService()

    override fun pickProfilePhoto(onResult: (String?) -> Unit) = photoPicker(onResult)

    override fun scanQrCode(onResult: (String?) -> Unit) = qrScanner(onResult)

    override fun saveTextFile(fileName: String, text: String, onDone: (Boolean) -> Unit) = fileSaver(fileName, text, onDone)

    override fun openTextFile(onResult: (String?) -> Unit) = fileOpener(onResult)

    /** Settings → Sharing rows: background location and battery optimisation. */
    fun sharingSettings(hasBackgroundPermission: Boolean): List<SettingItem> {
        val ignoringBattery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        return listOf(
            SettingItem(
                "Background location",
                if (hasBackgroundPermission) "On. Sharing keeps going in the background and after a restart."
                else "Off. Tap Turn on, then choose “Allow all the time” on the next screen.",
                if (hasBackgroundPermission) null else "Turn on",
            ) {
                requestBackgroundLocation()
            },
            SettingItem(
                "Battery saver for Quietmap",
                if (ignoringBattery) "Off. Updates stay reliable." else "On, so some phones pause updates. Tap Turn off, then choose “Allow”.",
                if (ignoringBattery) null else "Turn off",
            ) {
                try {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                } catch (_: Throwable) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )
    }
}
