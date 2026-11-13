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

    private fun run(after: (() -> Unit)? = null, block: suspend () -> Unit) {
        app.scope.launch {
            try {
                block()
                if (after != null) withContext(Dispatchers.Main) { after() }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { Toast.makeText(context, t.message ?: "Something went wrong", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun setName(name: String) = run { engine.setMyName(name) }
    fun setSharing(on: Boolean) = run(after = { if (on) app.ensureSharingService() }) { engine.setSharing(on) }
    fun setRelays(relays: List<String>) = run { engine.setRelays(relays) }
    fun setLabel(pubkey: String, label: String?) = run { engine.setLabel(pubkey, label) }
    fun setVerified(pubkey: String, verified: Boolean) = run { engine.setVerified(pubkey, verified) }

    fun createCircle(name: String, onCreated: (String) -> Unit) {
        var id = ""
        run(after = { onCreated(id); app.ensureSharingService() }) { id = engine.createCircle(name).id }
    }

    fun createInvite(circleId: String, onLink: (String) -> Unit) {
        var link = ""
        run(after = { onLink(link) }) { link = engine.createInvite(circleId) }
    }

    fun acceptInvite(invite: InviteData, onDone: () -> Unit) = run(after = onDone) { engine.acceptInvite(invite) }
    fun approve(requestId: String) = run(after = { app.ensureSharingService() }) { engine.approve(requestId) }
    fun deny(requestId: String) = run { engine.deny(requestId) }
    fun removeMember(circleId: String, pubkey: String) = run { engine.removeMember(circleId, pubkey) }
    fun leaveCircle(circleId: String) = run { engine.leaveCircle(circleId) }
}
