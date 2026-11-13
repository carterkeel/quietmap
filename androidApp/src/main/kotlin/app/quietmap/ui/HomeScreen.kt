package app.quietmap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quietmap.core.model.AppState
import app.quietmap.core.model.Circle
import app.quietmap.core.model.JoinRequest
import app.quietmap.core.protocol.DeviceCode
import kotlinx.coroutines.delay

@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now
}

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
    onRequestLocation: () -> Unit,
    onJoinLink: (String) -> Unit,
) {
    val now = rememberNow()
    val circle = state.circles.firstOrNull { it.id == selectedCircleId } ?: state.circles.firstOrNull()
    var showCreate by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf<JoinRequest?>(null) }

    if (circle == null) {
        Welcome(state, actions, onCreate = { showCreate = true }, onJoinLink = onJoinLink, onSettings = onSettings)
    } else {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, circle, onSelectCircle, onCreate = { showCreate = true }, onAdd = { onAdd(circle.id) }, onSettings = onSettings)

            if (state.sharing && !hasLocationPermission) {
                Banner("Allow location access so your circle can see where you are.", "Allow", onRequestLocation)
            }
            state.joinRequests.filter { it.circleId == circle.id }.forEach { req ->
                Banner("${req.name.ifBlank { "Someone" }} wants to join ${circle.name}", "Review") { reviewing = req }
            }
            state.pendingJoins.forEach { p ->
                Banner("Waiting for ${p.inviterName.ifBlank { "the inviter" }} to approve you joining ${p.circleName}.", null, null)
            }

            val locs = state.locations[circle.id].orEmpty()
            val markers = circle.members.mapNotNull { m ->
                val l = locs[m.pubkey] ?: return@mapNotNull null
                val kind = when {
                    m.pubkey == me -> MapMarker.Kind.ME
                    Format.isOffline(l, now) -> MapMarker.Kind.OFFLINE
                    else -> MapMarker.Kind.ONLINE
                }
                MapMarker(m.pubkey, l.lat, l.lon, Format.initial(displayName(state, m.pubkey, m.name, me)), kind)
            }
            MapPane(markers, focusKey = circle.id, modifier = Modifier.fillMaxWidth().weight(1f))

            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("People in ${circle.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${circle.members.size} ${if (circle.members.size == 1) "person" else "people"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn {
                    items(circle.members.sortedBy { if (it.pubkey == me) 0 else 1 }, key = { it.pubkey }) { m ->
                        val loc = locs[m.pubkey]
                        val name = displayName(state, m.pubkey, m.name, me)
                        val offline = m.pubkey != me && Format.isOffline(loc, now)
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenProfile(circle.id, m.pubkey) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Avatar(name, ring = if (m.pubkey == me) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, dimmed = offline)
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (m.pubkey == me && !state.sharing) "Sharing paused" else Format.status(loc, now),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Mono(DeviceCode.short(m.pubkey), size = 12)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
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
        AlertDialog(
            onDismissRequest = { reviewing = null },
            title = { Text("Let ${req.name.ifBlank { "this person" }} join?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("They opened your invite link. If you approve, they'll see everyone in this circle and everyone will see them.")
                    SectionLabel("Their device code")
                    Mono(DeviceCode.full(req.pubkey), size = 16, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.SemiBold)
                    Text(
                        "If you can, check this matches the code on their screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = { actions.approve(req.id); reviewing = null }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { actions.deny(req.id); reviewing = null }) { Text("Deny") } },
        )
    }
}

@Composable
private fun TopBar(state: AppState, circle: Circle, onSelect: (String) -> Unit, onCreate: () -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { open = true }) {
                Text(circle.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch circle", tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.circles.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { open = false; onSelect(c.id) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("New circle…") }, onClick = { open = false; onCreate() })
            }
        }
        Spacer(Modifier.weight(1f))
        AssistChip(
            onClick = onSettings,
            label = { Text(if (state.sharing) "Sharing on" else "Paused") },
            colors = if (state.sharing) {
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                AssistChipDefaults.assistChipColors()
            },
        )
        IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add someone") }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun Welcome(state: AppState, actions: Actions, onCreate: () -> Unit, onJoinLink: (String) -> Unit, onSettings: () -> Unit) {
    var name by remember(state.myName) { mutableStateOf(state.myName) }
    var link by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quietmap", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        Text(
            "Share your location with the people you choose. No account, no phone number. Your location is encrypted on this phone before it goes anywhere.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Your name (shown to people you add)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { actions.setName(name); onCreate() },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Create a circle") }

        Spacer(Modifier.height(8.dp))
        SectionLabel("Or join someone's circle")
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Paste an invite link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { actions.setName(name); onJoinLink(link) },
            enabled = link.isNotBlank() && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Open invite") }

        state.pendingJoins.forEach { p ->
            InfoCard {
                Text("Waiting for ${p.inviterName.ifBlank { "the inviter" }} to approve you joining ${p.circleName}.")
            }
        }
        Spacer(Modifier.weight(1f))
        InfoCard {
            SectionLabel("Your device code")
            Mono(DeviceCode.full(actions.me), size = 16, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.SemiBold)
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
            OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = { Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun displayName(state: AppState, pubkey: String, rosterName: String, me: String): String {
    if (pubkey == me) return "You"
    return state.contacts[pubkey]?.label ?: rosterName.ifBlank { "Unnamed" }
}
