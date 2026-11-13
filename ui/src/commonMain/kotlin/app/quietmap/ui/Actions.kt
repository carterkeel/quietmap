package app.quietmap.ui

import app.quietmap.core.Engine
import app.quietmap.core.protocol.InviteData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thin bridge from UI events to the engine; shows errors as platform messages. */
class Actions(
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val platform: PlatformBridge,
) {
    val me: String get() = engine.myPubkey

    private fun launchAction(after: (() -> Unit)? = null, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                if (after != null) withContext(Dispatchers.Main) { after() }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { platform.showMessage(t.message ?: "Something went wrong") }
            }
        }
    }

    fun setName(name: String) = launchAction { engine.setMyName(name) }
    fun setAvatar(color: Int?, photo: String?) = launchAction { engine.setAvatar(color, photo) }
    fun setSharing(on: Boolean) = launchAction(after = { if (on) platform.ensureSharingService() }) { engine.setSharing(on) }
    fun setShareBattery(on: Boolean) = launchAction { engine.setShareBattery(on) }
    fun setShowPhotos(on: Boolean) = launchAction { engine.setShowPhotos(on) }
    fun setLastCircle(circleId: String) = launchAction { engine.setLastCircle(circleId) }
    fun setSetupDone() = launchAction(after = { platform.ensureSharingService() }) { engine.setSetupDone() }
    fun setRelays(relays: List<String>) = launchAction { engine.setRelays(relays) }
    fun setLabel(pubkey: String, label: String?) = launchAction { engine.setLabel(pubkey, label) }
    fun setVerified(pubkey: String, verified: Boolean) = launchAction { engine.setVerified(pubkey, verified) }

    fun createCircle(name: String, onCreated: (String) -> Unit) {
        var id = ""
        launchAction(after = { onCreated(id); platform.ensureSharingService() }) { id = engine.createCircle(name).id }
    }

    fun createInvite(circleId: String, onLink: (String) -> Unit) {
        var link = ""
        launchAction(after = { onLink(link) }) { link = engine.createInvite(circleId) }
    }

    fun acceptInvite(invite: InviteData, onDone: () -> Unit) = launchAction(after = onDone) {
        engine.acceptInvite(invite)
        engine.setSetupDone()
    }
    fun approve(requestId: String) = launchAction(after = { platform.ensureSharingService() }) { engine.approve(requestId) }
    fun deny(requestId: String) = launchAction { engine.deny(requestId) }
    fun removeMember(circleId: String, pubkey: String) = launchAction { engine.removeMember(circleId, pubkey) }
    fun leaveCircle(circleId: String) = launchAction { engine.leaveCircle(circleId) }
    fun deleteCircle(circleId: String) = launchAction { engine.deleteCircle(circleId) }
    fun setManager(circleId: String, pubkey: String, manager: Boolean) = launchAction { engine.setManager(circleId, pubkey, manager) }
    fun transferAdmin(circleId: String, pubkey: String) = launchAction { engine.transferAdmin(circleId, pubkey) }

    /** Builds an encrypted backup off the main thread (PBKDF2 takes a moment) and hands back the text. */
    fun exportBackup(passphrase: String, onReady: (String) -> Unit) {
        var text = ""
        launchAction(after = { onReady(text) }) { text = engine.exportBackup(passphrase) }
    }

    /** Restores a backup; [onDone] gets null on success or the error to show. */
    fun importBackup(text: String, passphrase: String, onDone: (String?) -> Unit) {
        scope.launch {
            val error = try {
                engine.importBackup(text, passphrase)
                null
            } catch (t: Throwable) {
                t.message ?: "Couldn't restore that backup"
            }
            withContext(Dispatchers.Main) {
                if (error == null) platform.ensureSharingService()
                onDone(error)
            }
        }
    }
}
