package app.quietmap.ui

import android.content.Context
import android.widget.Toast
import app.quietmap.QuietmapApp
import app.quietmap.core.protocol.InviteData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thin bridge from UI events to the engine; shows errors as toasts. */
class Actions(private val context: Context) {
    private val app = QuietmapApp.from(context)
    private val engine = app.engine
    val me: String get() = engine.myPubkey

    private fun launchAction(after: (() -> Unit)? = null, block: suspend () -> Unit) {
        app.scope.launch {
            try {
                block()
                if (after != null) withContext(Dispatchers.Main) { after() }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { Toast.makeText(context, t.message ?: "Something went wrong", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun setName(name: String) = launchAction { engine.setMyName(name) }
    fun setSharing(on: Boolean) = launchAction(after = { if (on) app.ensureSharingService() }) { engine.setSharing(on) }
    fun setRelays(relays: List<String>) = launchAction { engine.setRelays(relays) }
    fun setLabel(pubkey: String, label: String?) = launchAction { engine.setLabel(pubkey, label) }
    fun setVerified(pubkey: String, verified: Boolean) = launchAction { engine.setVerified(pubkey, verified) }

    fun createCircle(name: String, onCreated: (String) -> Unit) {
        var id = ""
        launchAction(after = { onCreated(id); app.ensureSharingService() }) { id = engine.createCircle(name).id }
    }

    fun createInvite(circleId: String, onLink: (String) -> Unit) {
        var link = ""
        launchAction(after = { onLink(link) }) { link = engine.createInvite(circleId) }
    }

    fun acceptInvite(invite: InviteData, onDone: () -> Unit) = launchAction(after = onDone) { engine.acceptInvite(invite) }
    fun approve(requestId: String) = launchAction(after = { app.ensureSharingService() }) { engine.approve(requestId) }
    fun deny(requestId: String) = launchAction { engine.deny(requestId) }
    fun removeMember(circleId: String, pubkey: String) = launchAction { engine.removeMember(circleId, pubkey) }
    fun leaveCircle(circleId: String) = launchAction { engine.leaveCircle(circleId) }
}
