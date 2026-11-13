package app.quietmap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
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
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quietmap.core.model.AppState
import app.quietmap.core.model.Circle
import app.quietmap.core.model.JoinRequest
import app.quietmap.core.protocol.DeviceCode
import kotlinx.coroutines.delay

@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(epochSeconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = epochSeconds()
        }
    }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppState,
    me: String,
    selectedCircleId: String?,
    hasLocationPermission: Boolean,
    actions: Actions,
    onSelectCircle: (String) -> Unit,
    onOpenProfile: (circleId: String, pubkey: String) -> Unit,
    onAdd: (circleId: String) -> Unit,
    onSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onRequestLocation: () -> Unit,
    onJoinLink: (String) -> Unit,
    onScan: () -> Unit,
) {
    val circle = state.circles.firstOrNull { it.id == selectedCircleId } ?: state.circles.firstOrNull()
    var showCreate by remember { mutableStateOf(false) }
    var showCircles by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf<JoinRequest?>(null) }

    if (circle == null) {
        Welcome(state, me, actions, onCreate = { showCreate = true }, onJoinLink = onJoinLink, onScan = onScan, onSettings = onSettings, onEditProfile = onEditProfile)
    } else {
        val now = rememberNow()
        var recenter by remember { mutableIntStateOf(0) }
        val locs = state.locations[circle.id].orEmpty()
        val markers = remember(circle.members, locs, state.profiles, state.contacts, state.myColor, now) {
            circle.members.mapNotNull { m ->
                val l = locs[m.pubkey] ?: return@mapNotNull null
                val info = avatarFor(state, m.pubkey, me, m.name)
                val kind = when {
                    m.pubkey == me -> MapMarker.Kind.ME
                    Format.isOffline(l, now) -> MapMarker.Kind.OFFLINE
                    else -> MapMarker.Kind.ONLINE
                }
                MapMarker(m.pubkey, l.lat, l.lon, Format.initial(info.name), markerColorHex(info), kind)
            }
        }
        val sheetState = rememberBottomSheetScaffoldState()

        BottomSheetScaffold(
            scaffoldState = sheetState,
            sheetPeekHeight = 300.dp,
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetShadowElevation = 8.dp,
            sheetContent = {
                PeopleSheet(
                    state = state,
                    me = me,
                    circle = circle,
                    now = now,
                    hasLocationPermission = hasLocationPermission,
                    onRequestLocation = onRequestLocation,
                    onReview = { reviewing = it },
                    onOpenProfile = { onOpenProfile(circle.id, it) },
                    onInvite = { onAdd(circle.id) },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                MapPane(markers, focusKey = circle.id, recenterRequest = recenter, modifier = Modifier.fillMaxSize())

                // Floating controls over the map.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = { showCircles = true },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 3.dp,
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(circle.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Switch circle")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    SharingPill(state.sharing, onClick = onSettings)
                    Spacer(Modifier.width(8.dp))
                    MapButton(Icons.Filled.Settings, "Settings", onSettings)
                }

                Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp)) {
                    MapButton(AppIcons.MyLocation, "Center on me") { recenter++ }
                }
            }
        }
    }

    if (showCircles) {
        CirclePicker(
            state = state,
            selected = circle?.id,
            onSelect = { showCircles = false; onSelectCircle(it) },
            onNew = { showCircles = false; showCreate = true },
            onDismiss = { showCircles = false },
        )
    }

    if (showCreate) {
        TextPrompt(
            title = "New circle",
            label = "Circle name",
            initial = if (state.circles.isEmpty()) "Family" else "",
            confirm = "Create",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                actions.createCircle(name) { onSelectCircle(it) }
            },
        )
    }

    reviewing?.let { req ->
        val info = AvatarInfo(req.pubkey, req.name.ifBlank { "?" }, null, null, false)
        AlertDialog(
            onDismissRequest = { reviewing = null },
            icon = { Avatar(info, size = 56.dp) },
            title = { Text("Let ${req.name.ifBlank { "this person" }} join?", textAlign = TextAlign.Center) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("They opened your invite link. If you approve, they'll see everyone in this circle and everyone will see them.", textAlign = TextAlign.Center)
                    InfoCard {
                        Caption("Their device code")
                        Mono(DeviceCode.full(req.pubkey), size = 17, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
                    }
                    Caption("If you can, check it matches the code on their screen.")
                }
            },
            confirmButton = { Button(onClick = { actions.approve(req.id); reviewing = null }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { actions.deny(req.id); reviewing = null }) { Text("Deny") } },
        )
    }
}

@Composable
private fun SharingPill(sharing: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(if (sharing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, label = "pill")
    val content by animateColorAsState(if (sharing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "pillText")
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = container, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).padding(0.dp)) {
                Surface(shape = CircleShape, color = content, modifier = Modifier.fillMaxSize()) {}
            }
            Spacer(Modifier.width(8.dp))
            Text(if (sharing) "Sharing" else "Paused", color = content, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun MapButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp)) }
    }
}

// -------------------------------------------------------------------------------------------------
// The people sheet: Signal-style list under the map
// -------------------------------------------------------------------------------------------------

@Composable
private fun PeopleSheet(
    state: AppState,
    me: String,
    circle: Circle,
    now: Long,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    onReview: (JoinRequest) -> Unit,
    onOpenProfile: (String) -> Unit,
    onInvite: () -> Unit,
) {
    val requests = state.joinRequests.filter { it.circleId == circle.id }
    Column(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(circle.name, style = MaterialTheme.typography.titleLarge)
                Caption("${circle.members.size} ${if (circle.members.size == 1) "person" else "people"}")
            }
            TonalButton("Invite", onInvite, icon = Icons.Filled.Add)
        }

        Banner(visible = state.sharing && !hasLocationPermission, text = "Allow location access so your circle can see you.", action = "Allow", onAction = onRequestLocation)
        requests.forEach { req ->
            Banner(visible = true, text = "${req.name.ifBlank { "Someone" }} wants to join ${circle.name}", action = "Review") { onReview(req) }
        }
        state.pendingJoins.forEach { p ->
            Banner(visible = true, text = "Waiting for ${p.inviterName.ifBlank { "the inviter" }} to let you into ${p.circleName}.", action = null, onAction = null)
        }

        val locs = state.locations[circle.id].orEmpty()
        LazyColumn(Modifier.fillMaxWidth()) {
            items(circle.members.sortedBy { if (it.pubkey == me) 0 else 1 }, key = { it.pubkey }) { m ->
                val info = avatarFor(state, m.pubkey, me, m.name)
                val loc = locs[m.pubkey]
                val offline = m.pubkey != me && Format.isOffline(loc, now)
                Row(
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .clickable { onOpenProfile(m.pubkey) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(info, size = 48.dp, dimmed = offline)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (m.pubkey == me) "You" else info.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (m.pubkey == me && !state.sharing) "Sharing paused" else Format.status(loc, now),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (m.pubkey == circle.adminPubkey) {
                        Spacer(Modifier.width(8.dp))
                        Caption("Admin")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Banner(visible: Boolean, text: String, action: String?, onAction: (() -> Unit)?) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CirclePicker(state: AppState, selected: String?, onSelect: (String) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Your circles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        state.circles.forEach { c ->
            SettingsRow(
                title = c.name,
                subtitle = "${c.members.size} ${if (c.members.size == 1) "person" else "people"}",
                icon = AppIcons.Circles,
                onClick = { onSelect(c.id) },
            ) {
                if (c.id == selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(title = "New circle", icon = Icons.Filled.Add, titleColor = MaterialTheme.colorScheme.primary, onClick = onNew)
        Spacer(Modifier.height(24.dp))
    }
}

// -------------------------------------------------------------------------------------------------
// First run
// -------------------------------------------------------------------------------------------------

@Composable
private fun Welcome(
    state: AppState,
    me: String,
    actions: Actions,
    onCreate: () -> Unit,
    onJoinLink: (String) -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onEditProfile: () -> Unit,
) {
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    var joining by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Quietmap") { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") } }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clickable(onClick = onEditProfile)) {
                Avatar(avatarFor(state, me, me, name), size = 96.dp)
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.align(Alignment.BottomEnd).size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Camera, contentDescription = "Set profile photo", modifier = Modifier.size(18.dp)) }
                }
            }
            Text("Share your location with people you choose", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "No account and no phone number. Your location is encrypted on this phone before it goes anywhere.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Your name") },
                supportingText = { Text("Shown to people you add") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("Create a circle", { actions.setName(name); onCreate() }, enabled = name.isNotBlank())
            TextButton(onClick = { joining = !joining }) { Text(if (joining) "Hide" else "I have an invite") }
            AnimatedVisibility(joining, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text("Paste an invite link") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TonalButton("Scan QR code", { actions.setName(name); onScan() }, Modifier.weight(1f), icon = AppIcons.ScanQr)
                        TonalButton("Open link", { actions.setName(name); onJoinLink(link) }, Modifier.weight(1f), enabled = link.isNotBlank())
                    }
                }
            }
            state.pendingJoins.forEach { p ->
                InfoCard { Text("Waiting for ${p.inviterName.ifBlank { "the inviter" }} to let you into ${p.circleName}.") }
            }
            Spacer(Modifier.height(16.dp))
            InfoCard {
                Caption("Your device code")
                Mono(DeviceCode.full(me), size = 17, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun TextPrompt(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp))
        },
        confirmButton = { Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
