package app.quietmap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.quietmap.core.model.AppState
import app.quietmap.core.nostr.RelayStatus
import app.quietmap.core.protocol.DeviceCode
import app.quietmap.core.protocol.InviteData
import app.quietmap.ui.Palette.Green

@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

// ---------------------------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------------------------

@Composable
fun ProfileScreen(state: AppState, me: String, circleId: String, pubkey: String, actions: Actions, onBack: () -> Unit) {
    val circle = state.circles.firstOrNull { it.id == circleId }
    val member = circle?.members?.firstOrNull { it.pubkey == pubkey }
    if (circle == null || member == null) {
        ScreenScaffold("Profile", onBack) { Text("This person is no longer in the circle.") }
        return
    }
    val now = rememberNow()
    val contact = state.contacts[pubkey]
    val isMe = pubkey == me
    val name = displayName(state, pubkey, member.name, me)
    val loc = state.locations[circleId]?.get(pubkey)
    var renaming by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    ScreenScaffold(if (isMe) "You" else "Profile", onBack) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(name, size = 88.dp, ring = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isMe) state.myName.ifBlank { "You" } else name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (!isMe) IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
            }
            if (!isMe && contact?.label != null && member.name.isNotBlank() && member.name != contact.label) {
                Text("Calls themself “${member.name}”", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (pubkey == circle.adminPubkey) {
                Text("Admin of ${circle.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Device code", Modifier.weight(1f))
                if (!isMe) {
                    if (contact?.verified == true) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Green, modifier = Modifier.size(16.dp))
                        Text(" Verified", color = Green, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Not verified", color = Palette.Amber, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Mono(DeviceCode.full(pubkey), size = 22, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.SemiBold)
            Text(
                if (isMe) "Show this to people you add so they can check it matches."
                else "Compare this with the code on their phone, in person or on a call.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isMe) {
                OutlinedButton(onClick = { actions.setVerified(pubkey, contact?.verified != true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (contact?.verified == true) "Mark as not verified" else "Codes match: mark verified")
                }
            }
        }

        Column {
            KeyValue("Status", if (isMe && !state.sharing) "Sharing paused" else Format.status(loc, now))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            loc?.let {
                KeyValue("Accuracy", "±${it.accuracy.toInt()} m")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                it.battery?.let { b -> KeyValue("Battery", "$b%"); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            }
            KeyValue("Circle", circle.name)
        }

        if (!isMe && circle.adminPubkey == me) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Remove from ${circle.name}") }
        }
    }

    if (renaming) {
        TextPrompt("Rename", "Name shown on your phone", contact?.label ?: member.name, "Save", { renaming = false }) {
            actions.setLabel(pubkey, it); renaming = false
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove $name?") },
            text = { Text("They'll stop seeing ${circle.name}, and the circle gets a new key so they can't read anything new.") },
            confirmButton = {
                Button(onClick = { actions.removeMember(circleId, pubkey); confirmRemove = false; onBack() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Add someone (invite link)
// ---------------------------------------------------------------------------------------------

@Composable
fun AddScreen(state: AppState, me: String, circleId: String, actions: Actions, onBack: () -> Unit, onJoinLink: (String) -> Unit) {
    val context = LocalContext.current
    val circle = state.circles.firstOrNull { it.id == circleId }
    var link by remember { mutableStateOf<String?>(null) }
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    var paste by remember { mutableStateOf("") }

    ScreenScaffold("Add someone", onBack) {
        if (circle != null && circle.adminPubkey == me) {
            Text("Invite to ${circle.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "The link works once and expires in 24 hours. When they open it you'll be asked to approve them, and then you'll see each other.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val current = link
            if (current == null) {
                Button(onClick = { actions.createInvite(circle.id) { link = it } }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Create invite link")
                }
            } else {
                InfoCard { Mono(current, size = 12) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shareLink(context, current, circle.name) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Share") }
                    OutlinedButton(onClick = { copy(context, current) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Copy") }
                }
                Text(
                    "Tip: send it in an end-to-end encrypted chat like Signal. The app that carries the link can read it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (circle != null) {
            val admin = circle.members.firstOrNull { it.pubkey == circle.adminPubkey }
            InfoCard { Text("Only ${admin?.let { displayName(state, it.pubkey, it.name, me) } ?: "the circle admin"} can invite people to ${circle.name}.") }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Your name (shown to people you add)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (name != state.myName && name.isNotBlank()) IconButton(onClick = { actions.setName(name) }) { Icon(Icons.Filled.Check, contentDescription = "Save name") }
            },
        )
        InfoCard {
            SectionLabel("Your device code")
            Mono(DeviceCode.full(me), size = 18, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.SemiBold)
            Text("They should see the same code on their screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Join someone else's circle")
        OutlinedTextField(value = paste, onValueChange = { paste = it }, label = { Text("Paste an invite link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { onJoinLink(paste) }, enabled = paste.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Open invite") }
    }
}

private fun shareLink(context: Context, link: String, circleName: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Join my circle \"$circleName\" on Quietmap: $link")
    }
    context.startActivity(Intent.createChooser(send, "Share invite link"))
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("Quietmap invite", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------------------------------------
// Join (consent before sharing anything)
// ---------------------------------------------------------------------------------------------

@Composable
fun JoinScreen(state: AppState, invite: InviteData?, actions: Actions, onDone: () -> Unit) {
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    ScreenScaffold("Join a circle", onDone) {
        val now = System.currentTimeMillis() / 1000
        when {
            invite == null -> Text("That doesn't look like a Quietmap invite link. Ask for a new one.")
            invite.e < now -> Text("This invite has expired. Ask ${invite.n.ifBlank { "them" }} for a new link.")
            invite.k == actions.me -> Text("This is your own invite link. Send it to the person you want to add.")
            state.circles.any { it.id == invite.c } -> Text("You're already in ${invite.cn}.")
            else -> {
                Text("Join ${invite.cn}?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${invite.n.ifBlank { "Someone" }} invited you. Once they approve, everyone in ${invite.cn} will see your location, and you'll see theirs.",
                )
                InfoCard {
                    SectionLabel("${invite.n.ifBlank { "Inviter" }}'s device code")
                    Mono(DeviceCode.full(invite.k), size = 18, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.SemiBold)
                    Text("Check it matches the code on their phone if you can.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        actions.setName(name)
                        actions.acceptInvite(invite, onDone)
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Join and share my location") }
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cancel") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    state: AppState,
    me: String,
    relayStatus: Map<String, RelayStatus>,
    hasBackgroundPermission: Boolean,
    actions: Actions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var newRelay by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf<String?>(null) }
    val ignoringBattery = remember { context.getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(context.packageName) }

    ScreenScaffold("Settings", onBack) {
        SectionLabel("Sharing")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Share my location", fontWeight = FontWeight.Medium)
                Text("With everyone in your circles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.sharing, onCheckedChange = { actions.setSharing(it) })
        }
        SettingRow(
            "Allow all the time",
            if (hasBackgroundPermission) "Granted. Sharing can resume after a restart." else "Needed to resume sharing after your phone restarts.",
            if (hasBackgroundPermission) null else "Open",
        ) { openAppSettings(context) }
        SettingRow(
            "Battery optimization",
            if (ignoringBattery) "Off for Quietmap. Updates stay reliable." else "On. Some phones may pause updates in the background.",
            if (ignoringBattery) null else "Fix",
        ) {
            try {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            } catch (_: Throwable) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Relays")
        Text(
            "Public Nostr relays carry your encrypted updates. They can't read them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.relays.forEach { url ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val st = relayStatus[url]
                Box(
                    Modifier.size(10.dp).background(
                        when (st) {
                            RelayStatus.CONNECTED -> Green
                            RelayStatus.CONNECTING -> Palette.Amber
                            else -> MaterialTheme.colorScheme.outline
                        },
                        CircleShape,
                    ),
                )
                Text(url.removePrefix("wss://"), modifier = Modifier.weight(1f).padding(start = 10.dp))
                IconButton(onClick = { actions.setRelays(state.relays - url) }, enabled = state.relays.size > 1) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove $url")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newRelay,
                onValueChange = { newRelay = it.trim() },
                label = { Text("wss://relay.example") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { actions.setRelays(state.relays + newRelay); newRelay = "" },
                enabled = newRelay.startsWith("wss://") && newRelay.length > 8,
            ) { Text("Add") }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Circles")
        state.circles.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${c.members.size} people · key #${c.epoch}" + if (c.adminPubkey == me) " · you're the admin" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (c.adminPubkey != me) TextButton(onClick = { confirmLeave = c.id }) { Text("Leave") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("This device")
        InfoCard {
            SectionLabel("Your device code")
            Mono(DeviceCode.full(me), size = 18, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.SemiBold)
            Text(
                "Your key never leaves this phone and isn't backed up. A new phone means a new device code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.sharing) {
            Button(
                onClick = { actions.setSharing(false) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Stop sharing with everyone") }
        }
        Text("Quietmap 0.1.0 · open source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    confirmLeave?.let { id ->
        val c = state.circles.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { confirmLeave = null },
            title = { Text("Leave ${c?.name ?: "circle"}?") },
            text = { Text("You'll stop seeing this circle on this phone. Ask the admin to remove you too, so the circle gets a new key.") },
            confirmButton = { Button(onClick = { actions.leaveCircle(id); confirmLeave = null }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingRow(title: String, detail: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}
