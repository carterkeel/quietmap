package app.quietmap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resumes sharing after a reboot or an app update, if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            QuietmapApp.from(context).ensureSharingService()
        }
    }
}
