package app.quietmap.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quietmap.core.model.AppState
import app.quietmap.core.nostr.RelayStatus
import app.quietmap.core.protocol.DeviceCode
import app.quietmap.core.protocol.InviteData
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

private val Gutter = Modifier.padding(horizontal = 24.dp)

/** Big centered avatar with name and a line under it, as at the top of a Signal profile. */
@Composable
private fun ProfileHeader(info: AvatarInfo, title: String, subtitle: String?, onAvatarClick: (() -> Unit)? = null, editBadge: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(if (onAvatarClick != null) Modifier.clip(CircleShape).clickable(onClick = onAvatarClick) else Modifier) {
            Avatar(info, size = 112.dp)
            if (editBadge) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).size(36.dp).border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
                ) { Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Camera, contentDescription = "Change photo", modifier = Modifier.size(18.dp)) } }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Gutter)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Gutter)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Someone's profile
// -------------------------------------------------------------------------------------------------

@Composable
fun ProfileScreen(state: AppState, me: String, circleId: String, pubkey: String, actions: Actions, onBack: () -> Unit) {
    val circle = state.circles.firstOrNull { it.id == circleId }
    val member = circle?.members?.firstOrNull { it.pubkey == pubkey }
    if (circle == null || member == null) {
        ScreenScaffold("", onBack) { Text("This person is no longer in the circle.", modifier = Gutter) }
        return
    }
    val now = rememberNow()
    val isMe = pubkey == me
    val info = avatarFor(state, pubkey, me, member.name)
    val contact = state.contacts[pubkey]
    val published = state.profiles[pubkey]?.name?.ifBlank { null } ?: member.name
    val loc = state.locations[circleId]?.get(pubkey)
    var renaming by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    ScreenScaffold("", onBack) {
        val subtitle = when {
            isMe -> "This is you"
            contact?.label != null && published.isNotBlank() && published != contact.label -> "Calls themself “$published”"
            else -> null
        }
        ProfileHeader(info, info.name, subtitle)

        SettingsRow(
            title = if (isMe && !state.sharing) "Sharing paused" else Format.status(loc, now),
            subtitle = loc?.let { "Accurate to about ${it.accuracy.toInt()} m" },
            icon = Icons.Filled.LocationOn,
        )
        if (pubkey == circle.adminPubkey) SettingsRow("Admin of ${circle.name}", icon = Icons.Filled.Person)
        if (!isMe) SettingsRow("Rename", "Only changes the name on your phone", Icons.Filled.Edit, onClick = { renaming = true })

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionHeader(if (isMe) "Your device code" else "Device code")
        Column(Gutter, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono(DeviceCode.full(pubkey), size = 22, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
            Caption(
                if (isMe) "Show this to people you add so they can check it matches."
                else "Compare this with the code on their phone, in person or on a call. Verified people get a green check on their photo.",
            )
        }
        if (!isMe) {
            val verified = contact?.verified == true
            SettingsRow(
                title = if (verified) "Verified" else "Mark as verified",
                subtitle = if (verified) "Tap to clear" else "Do this once the codes match",
                icon = AppIcons.Shield,
                titleColor = if (verified) verifiedGreen() else MaterialTheme.colorScheme.onSurface,
                onClick = { actions.setVerified(pubkey, !verified) },
            ) {
                AnimatedVisibility(verified, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = verifiedGreen())
                }
            }
        }

        if (!isMe && circle.adminPubkey == me) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow("Remove from ${circle.name}", icon = Icons.Filled.Delete, titleColor = MaterialTheme.colorScheme.error, onClick = { confirmRemove = true })
        }
    }

    if (renaming) {
        TextPrompt("Rename", "Name shown on your phone", contact?.label ?: published, "Save", { renaming = false }) {
            actions.setLabel(pubkey, it); renaming = false
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${info.name}?") },
            text = { Text("They'll stop seeing ${circle.name}, and the circle gets a new key so they can't read anything new.") },
            confirmButton = { Button(onClick = { actions.removeMember(circleId, pubkey); confirmRemove = false; onBack() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Your profile: name, photo or default avatar color
// -------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(state: AppState, me: String, actions: Actions, onBack: () -> Unit) {
    val platform = LocalPlatform.current
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    var photoSheet by remember { mutableStateOf(false) }
    val info = avatarFor(state, me, me, name)
    val selectedColor = state.myColor ?: AvatarColors.defaultIndex(me)

    ScreenScaffold("Your profile", onBack) {
        ProfileHeader(info.copy(name = name.ifBlank { "?" }), name.ifBlank { "Add your name" }, "Only people in your circles can see this", onAvatarClick = { photoSheet = true }, editBadge = true)

        Column(Gutter, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Your name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                trailingIcon = {
                    AnimatedVisibility(name != state.myName && name.isNotBlank(), enter = scaleIn() + fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { actions.setName(name) }) { Icon(Icons.Filled.Check, contentDescription = "Save name", tint = MaterialTheme.colorScheme.primary) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionHeader("Photo")
        SettingsRow(
            title = if (state.myPhoto == null) "Choose a photo" else "Change photo",
            subtitle = "Cropped to a square and kept small",
            icon = AppIcons.Camera,
            onClick = { platform.pickProfilePhoto { if (it != null) actions.setAvatar(state.myColor, it) } },
        )
        AnimatedVisibility(state.myPhoto != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SettingsRow("Use the default avatar instead", icon = Icons.Filled.Close, onClick = { actions.setAvatar(state.myColor, null) })
        }

        SectionHeader("Default avatar color")
        ColorGrid(name, me, selectedColor, photoSet = state.myPhoto != null) { index -> actions.setAvatar(index, null) }
        Caption("Picking a color uses your initial instead of a photo.", Gutter.padding(top = 8.dp))

        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Gutter, verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Caption("Your name and photo are end-to-end encrypted and sent only to people in your circles. Relays can't see them.")
        }
    }

    if (photoSheet) {
        ModalBottomSheet(onDismissRequest = { photoSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SettingsRow("Choose a photo", icon = AppIcons.Camera, onClick = {
                photoSheet = false
                platform.pickProfilePhoto { if (it != null) actions.setAvatar(state.myColor, it) }
            })
            if (state.myPhoto != null) {
                SettingsRow("Use the default avatar", icon = Icons.Filled.Close, onClick = { photoSheet = false; actions.setAvatar(state.myColor, null) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColorGrid(name: String, me: String, selected: Int, photoSet: Boolean, onPick: (Int) -> Unit) {
    Column(Gutter, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarColors.all.indices.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { index ->
                    val isSelected = !photoSet && index == selected
                    val ring by animateDpAsState(if (isSelected) 3.dp else 0.dp, label = "ring")
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .border(ring, MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(if (isSelected) 5.dp else 0.dp)
                            .clip(CircleShape)
                            .clickable { onPick(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(AvatarInfo(me, name.ifBlank { "?" }, index, null, false), size = 44.dp, showVerified = false)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Invite: QR code and link
// -------------------------------------------------------------------------------------------------

@Composable
fun AddScreen(state: AppState, me: String, circleId: String, actions: Actions, onBack: () -> Unit, onJoinLink: (String) -> Unit, onScan: () -> Unit) {
    val platform = LocalPlatform.current
    val circle = state.circles.firstOrNull { it.id == circleId }
    var link by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf("") }

    ScreenScaffold("Invite to ${circle?.name ?: "circle"}", onBack) {
        if (circle != null && circle.adminPubkey == me) {
            AnimatedContent(targetState = link, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)).togetherWith(fadeOut()) }, label = "invite") { current ->
                Column(Gutter, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (current == null) {
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(AppIcons.QrCode, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
                        }
                        Text(
                            "Create a QR code and link. They work once, expire in 24 hours, and you approve whoever uses them.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        PrimaryButton("Create invite", { actions.createInvite(circle.id) { link = it } })
                    } else {
                        Text("Have them scan this with Quietmap", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // QR codes need dark modules on a light background, in both themes.
                        Surface(color = Color.White, shape = RoundedCornerShape(24.dp), shadowElevation = 2.dp) {
                            Image(
                                painter = rememberQrCodePainter(current),
                                contentDescription = "Invite QR code",
                                modifier = Modifier.padding(20.dp).size(232.dp),
                            )
                        }
                        Caption("Works once · expires in 24 hours")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TonalButton("Share link", { platform.shareText("Join my circle \"${circle.name}\" on Quietmap: $current", "Share invite link") }, Modifier.weight(1f), icon = Icons.Filled.Share)
                            TonalButton("Copy", { platform.copyToClipboard("Quietmap invite", current) }, Modifier.weight(1f))
                        }
                        Caption("Tip: send links in an end-to-end encrypted chat. The app that carries a link can read it.")
                    }
                }
            }
        } else if (circle != null) {
            val admin = circle.members.firstOrNull { it.pubkey == circle.adminPubkey }
            InfoCard(Gutter) {
                Text("Only ${admin?.let { avatarFor(state, it.pubkey, me, it.name).name } ?: "the circle admin"} can invite people to ${circle.name}.")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionHeader("Join someone else's circle")
        SettingsRow("Scan a QR code", icon = AppIcons.ScanQr, onClick = onScan)
        Column(Gutter.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = paste, onValueChange = { paste = it }, label = { Text("Or paste an invite link") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            AnimatedVisibility(paste.isNotBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                PrimaryButton("Open invite", { onJoinLink(paste) })
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Join (consent before sharing anything)
// -------------------------------------------------------------------------------------------------

@Composable
fun JoinScreen(state: AppState, invite: InviteData?, actions: Actions, onDone: () -> Unit) {
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    ScreenScaffold("Join a circle", onDone) {
        val now = epochSeconds()
        when {
            invite == null -> Text("That isn't a Quietmap invite. Ask for a new link or QR code.", modifier = Gutter)
            invite.e < now -> Text("This invite has expired. Ask ${invite.n.ifBlank { "them" }} for a new one.", modifier = Gutter)
            invite.k == actions.me -> Text("This is your own invite. Show it to the person you want to add.", modifier = Gutter)
            state.circles.any { it.id == invite.c } -> Text("You're already in ${invite.cn}.", modifier = Gutter)
            else -> {
                ProfileHeader(
                    AvatarInfo(invite.k, invite.n.ifBlank { "?" }, null, null, false),
                    "Join ${invite.cn}?",
                    "${invite.n.ifBlank { "Someone" }} invited you. Once they approve, everyone in ${invite.cn} sees your location, and you see theirs.",
                )
                Column(Gutter, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    InfoCard {
                        Caption("${invite.n.ifBlank { "Inviter" }}'s device code")
                        Mono(DeviceCode.full(invite.k), size = 18, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
                        Caption("Check it matches the code on their phone if you can.")
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Your name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton("Join and share my location", { actions.setName(name); actions.acceptInvite(invite, onDone) }, enabled = name.isNotBlank())
                    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Settings
// -------------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    state: AppState,
    me: String,
    relayStatus: Map<String, RelayStatus>,
    sharingSettings: List<SettingItem>,
    actions: Actions,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
) {
    var newRelay by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf<String?>(null) }
    var showRelays by remember { mutableStateOf(false) }

    ScreenScaffold("Settings", onBack) {
        // Profile row, like the top of Signal's settings.
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onEditProfile).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(avatarFor(state, me, me, state.myName), size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(state.myName.ifBlank { "Add your name" }, style = MaterialTheme.typography.titleMedium)
                Caption(DeviceCode.full(me))
            }
            Icon(Icons.Filled.Edit, contentDescription = "Edit profile", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionHeader("Sharing")
        SwitchRow("Share my location", "With everyone in your circles", state.sharing, Icons.Filled.LocationOn) { actions.setSharing(it) }
        SwitchRow("Share battery level", "Shown next to your status", state.shareBattery, AppIcons.Battery) { actions.setShareBattery(it) }
        sharingSettings.forEach { item ->
            SettingsRow(item.title, item.detail, Icons.Filled.Info, onClick = if (item.action != null) item.onAction else null) {
                if (item.action != null) TextButton(onClick = item.onAction) { Text(item.action) }
            }
        }

        SectionHeader("Circles")
        state.circles.forEach { c ->
            SettingsRow(
                c.name,
                "${c.members.size} ${if (c.members.size == 1) "person" else "people"} · key #${c.epoch}" + if (c.adminPubkey == me) " · you're the admin" else "",
                AppIcons.Circles,
            ) {
                if (c.adminPubkey != me) TextButton(onClick = { confirmLeave = c.id }) { Text("Leave") }
            }
        }

        SectionHeader("Privacy")
        val connected = state.relays.count { relayStatus[it] == RelayStatus.CONNECTED }
        SettingsRow("Relays", "$connected of ${state.relays.size} connected · they only see encrypted data", AppIcons.Relay, onClick = { showRelays = !showRelays })
        AnimatedVisibility(showRelays, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                state.relays.forEach { url ->
                    Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).background(
                                when (relayStatus[url]) {
                                    RelayStatus.CONNECTED -> verifiedGreen()
                                    RelayStatus.CONNECTING -> Palette.Amber
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                CircleShape,
                            ),
                        )
                        Text(url.removePrefix("wss://"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        IconButton(onClick = { actions.setRelays(state.relays - url) }, enabled = state.relays.size > 1) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $url", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Row(Modifier.padding(start = 72.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newRelay,
                        onValueChange = { newRelay = it.trim() },
                        label = { Text("wss://relay.example") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { actions.setRelays(state.relays + newRelay); newRelay = "" }, enabled = newRelay.startsWith("wss://") && newRelay.length > 8) {
                        Icon(Icons.Filled.Add, contentDescription = "Add relay")
                    }
                }
            }
        }
        SettingsRow("Your key stays on this phone", "It isn't backed up. A new phone means a new device code.", Icons.Filled.Lock)

        AnimatedVisibility(state.sharing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow("Stop sharing with everyone", icon = Icons.AutoMirrored.Filled.ExitToApp, titleColor = MaterialTheme.colorScheme.error, onClick = { actions.setSharing(false) })
            }
        }
        Caption("Quietmap · open source", Gutter.padding(top = 16.dp))
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
