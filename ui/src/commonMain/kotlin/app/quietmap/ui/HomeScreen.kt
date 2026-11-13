package app.quietmap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quietmap.core.model.AppState
import app.quietmap.core.model.Circle
import app.quietmap.core.model.JoinRequest
import app.quietmap.core.protocol.DeviceCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
    onJoin: () -> Unit,
    onSettings: () -> Unit,
    onRequestLocation: () -> Unit,
) {
    val circle = state.circles.firstOrNull { it.id == selectedCircleId }
        ?: state.circles.firstOrNull { it.id == state.lastCircleId }
        ?: state.circles.firstOrNull()
    var showCreate by remember { mutableStateOf(false) }
    var showCircles by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf<JoinRequest?>(null) }
    val platform = LocalPlatform.current

    if (circle == null) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            NoCircles(state, onCreate = { showCreate = true }, onJoin = onJoin, onSettings = onSettings)
        }
    } else {
        val now = rememberNow()
        var focus by remember { mutableStateOf<MapFocus?>(null) }
        var focusSeq by remember { mutableIntStateOf(0) }
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
        fun flyTo(pubkey: String): Boolean {
            val l = locs[pubkey] ?: return false
            focusSeq++
            focus = MapFocus(l.lat, l.lon, focusSeq)
            return true
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val fullH = constraints.maxHeight.toFloat()
            // The map and sheet run under the status and navigation bars; controls stay clear of them.
            val statusTop = WindowInsets.safeDrawing.getTop(density).toFloat()
            val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
            // Sheet top positions (y from the top of the screen): handle only, a third of the screen, full.
            val topGap = statusTop + with(density) { 76.dp.toPx() }
            val collapsedY = fullH - navBottom - with(density) { 92.dp.toPx() }
            val defaultY = fullH * (2f / 3f)
            val anchors = remember(fullH, topGap, collapsedY) { listOf(topGap, defaultY, collapsedY) }
            var sheetY by remember { mutableFloatStateOf(defaultY) }
            if (sheetY > collapsedY || sheetY < topGap) sheetY = sheetY.coerceIn(topGap, collapsedY)
            val scope = rememberCoroutineScope()
            var settleJob by remember { mutableStateOf<Job?>(null) }

            fun dragBy(dy: Float): Float {
                settleJob?.cancel()
                val next = (sheetY + dy).coerceIn(topGap, collapsedY)
                val used = next - sheetY
                sheetY = next
                return used
            }
            fun settle(velocity: Float, target: Float? = null) {
                settleJob?.cancel()
                val goal = target ?: when {
                    velocity < -1200f -> anchors.filter { it < sheetY - 1f }.maxOrNull() ?: topGap
                    velocity > 1200f -> anchors.filter { it > sheetY + 1f }.minOrNull() ?: collapsedY
                    else -> anchors.minBy { abs(it - sheetY) }
                }
                settleJob = scope.launch {
                    animate(sheetY, goal, velocity, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                        sheetY = v
                    }
                }
            }
            // The list scrolls only once the sheet is fully open; before that, swipes move the sheet.
            val nested = remember(anchors) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                        if (available.y < 0 && sheetY > topGap) Offset(0f, dragBy(available.y)) else Offset.Zero

                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                        if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, dragBy(available.y)) else Offset.Zero

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (anchors.none { abs(it - sheetY) < 1f }) {
                            settle(available.y)
                            return available
                        }
                        return Velocity.Zero
                    }
                }
            }

            MapPane(
                markers = markers,
                focusKey = circle.id,
                focus = focus,
                bottomInset = with(density) { (fullH - defaultY).toDp() },
                modifier = Modifier.fillMaxSize(),
            )

            // A soft fade behind the status bar keeps the clock and battery readable over the map.
            val scrim = MaterialTheme.colorScheme.background
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { statusTop.toDp() } + 28.dp)
                    .background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0.72f), scrim.copy(alpha = 0.35f), scrim.copy(alpha = 0f)))),
            )

            // Floating controls over the map.
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                        Icon(AppIcons.ChevronDown, contentDescription = "Switch circle")
                    }
                }
                Spacer(Modifier.weight(1f))
                SharingToggle(state.sharing) {
                    val on = !state.sharing
                    actions.setSharing(on)
                    platform.showMessage(if (on) "Sharing your location" else "Sharing paused")
                    if (on && !hasLocationPermission) onRequestLocation()
                }
                Spacer(Modifier.width(8.dp))
                MapButton(AppIcons.Settings, "Settings", onSettings)
            }

            // Recenter button and map credits ride just above the sheet, and fade out as it nears the top.
            val buttonSize = with(density) { 44.dp.toPx() }
            val gap = with(density) { 16.dp.toPx() }
            val minY = statusTop + with(density) { 72.dp.toPx() }
            val floatY = (sheetY - buttonSize - gap).coerceAtLeast(minY)
            val floatAlpha = ((sheetY - topGap) / (defaultY - topGap) * 1.6f - 0.3f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-gap.roundToInt(), floatY.roundToInt()) }
                    .alpha(floatAlpha),
            ) {
                MapButton(AppIcons.MyLocation, "Center on me") { flyTo(me) || run { platform.showMessage("Your location isn't known yet"); true } }
            }
            Text(
                MAP_ATTRIBUTION,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(gap.roundToInt(), (sheetY - with(density) { 18.dp.toPx() }).roundToInt()) }
                    .alpha(floatAlpha),
            )

            // The people sheet.
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .offset { IntOffset(0, sheetY.roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { (fullH - topGap).toDp() }),
            ) {
                Column(Modifier.nestedScroll(nested).padding(bottom = with(density) { navBottom.toDp() })) {
                    // Drag handle and header: dragging here always moves the sheet; tapping cycles it.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { dragBy(it) },
                                onDragStopped = { v -> settle(v) },
                            )
                            .clickable(onClickLabel = "Resize the people list") {
                                settle(0f, if (abs(sheetY - defaultY) < 2f) topGap else defaultY)
                            },
                    ) {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(36.dp).height(4.dp)) {}
                        }
                        SheetHeader(circle, me, actions, onInvite = { onAdd(circle.id) })
                    }
                    PeopleList(
                        state = state,
                        me = me,
                        circle = circle,
                        now = now,
                        hasLocationPermission = hasLocationPermission,
                        onRequestLocation = onRequestLocation,
                        onReview = { reviewing = it },
                        onPerson = { pubkey ->
                            if (flyTo(pubkey)) {
                                if (sheetY < defaultY - 2f) settle(0f, defaultY)
                            } else {
                                onOpenProfile(circle.id, pubkey)
                            }
                        },
                        onProfile = { onOpenProfile(circle.id, it) },
                    )
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
            onJoin = { showCircles = false; onJoin() },
            onDismiss = { showCircles = false },
        )
    }

    if (showCreate) {
        TextSheet(
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
        ConfirmSheet(
            title = "Let ${req.name.ifBlank { "this person" }} join?",
            text = "They used your invite. If you approve, they'll see everyone in this circle and everyone will see them.",
            confirm = "Approve",
            dismiss = "Deny",
            onConfirm = { actions.approve(req.id); reviewing = null },
            onDismiss = { actions.deny(req.id); reviewing = null },
            header = { Avatar(info, size = 72.dp) },
        ) {
            InfoCard {
                Caption("Their device code")
                Mono(DeviceCode.full(req.pubkey), size = 17, color = MaterialTheme.colorScheme.onSurface, weight = FontWeight.Medium)
                Caption("If you can, check it matches the code on their screen.")
            }
        }
    }
}

@Composable
private fun SheetHeader(circle: Circle, me: String, actions: Actions, onInvite: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(circle.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Caption("${circle.members.size} ${if (circle.members.size == 1) "person" else "people"}")
        }
        if (circle.adminPubkey == me || me in circle.managers) TonalButton("Invite", onInvite, icon = AppIcons.Add)
    }
}

/** Tap to turn sharing on or off. */
@Composable
private fun SharingToggle(sharing: Boolean, onToggle: () -> Unit) {
    val container by animateColorAsState(if (sharing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, label = "pill")
    val content by animateColorAsState(if (sharing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "pillText")
    Surface(onClick = onToggle, shape = RoundedCornerShape(50), color = container, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = content, modifier = Modifier.size(8.dp)) {}
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
// People list
// -------------------------------------------------------------------------------------------------

@Composable
private fun PeopleList(
    state: AppState,
    me: String,
    circle: Circle,
    now: Long,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    onReview: (JoinRequest) -> Unit,
    onPerson: (String) -> Unit,
    onProfile: (String) -> Unit,
) {
    val requests = state.joinRequests.filter { it.circleId == circle.id }
    val locs = state.locations[circle.id].orEmpty()
    LazyColumn(Modifier.fillMaxWidth()) {
        item(key = "banners") {
            Column {
                Banner(visible = state.sharing && !hasLocationPermission, text = "Allow location access so your circle can see you.", action = "Allow", onAction = onRequestLocation)
                requests.forEach { req ->
                    Banner(visible = true, text = "${req.name.ifBlank { "Someone" }} wants to join ${circle.name}", action = "Review") { onReview(req) }
                }
                state.pendingJoins.forEach { p ->
                    Banner(visible = true, text = "Waiting for ${p.inviterName.ifBlank { "the inviter" }} to let you into ${p.circleName}.", action = null, onAction = null)
                }
            }
        }
        items(circle.members.sortedBy { if (it.pubkey == me) 0 else 1 }, key = { it.pubkey }) { m ->
            val info = avatarFor(state, m.pubkey, me, m.name)
            val loc = locs[m.pubkey]
            val offline = m.pubkey != me && Format.isOffline(loc, now)
            Row(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Show on map") { onPerson(m.pubkey) }
                    .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(info, size = 48.dp, dimmed = offline)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (m.pubkey == me) "You" else info.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        val role = when (m.pubkey) {
                            circle.adminPubkey -> "Admin"
                            in circle.managers -> "Manager"
                            else -> null
                        }
                        if (role != null) {
                            Spacer(Modifier.width(8.dp))
                            Caption(role)
                        }
                    }
                    Text(
                        if (m.pubkey == me && !state.sharing) "Sharing paused" else Format.status(loc, now),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onProfile(m.pubkey) }) {
                    Icon(AppIcons.Info, contentDescription = "Profile", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
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
private fun CirclePicker(state: AppState, selected: String?, onSelect: (String) -> Unit, onNew: () -> Unit, onJoin: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Your circles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        state.circles.forEach { c ->
            SettingsRow(
                title = c.name,
                subtitle = "${c.members.size} ${if (c.members.size == 1) "person" else "people"}",
                icon = AppIcons.Circles,
                onClick = { onSelect(c.id) },
            ) {
                if (c.id == selected) Icon(AppIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(title = "Join a circle", subtitle = "Scan a QR code or open an invite link", icon = AppIcons.ScanQr, titleColor = MaterialTheme.colorScheme.primary, onClick = onJoin)
        SettingsRow(title = "New circle", icon = AppIcons.Add, titleColor = MaterialTheme.colorScheme.primary, onClick = onNew)
        Spacer(Modifier.height(24.dp))
    }
}

/** Set up, but not in any circle yet (left one, or waiting to be let in). */
@Composable
private fun NoCircles(state: AppState, onCreate: () -> Unit, onJoin: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Quietmap") { IconButton(onClick = onSettings) { Icon(AppIcons.Settings, contentDescription = "Settings") } }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(24.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Circles, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
            }
            if (state.pendingJoins.isNotEmpty()) {
                state.pendingJoins.forEach { p ->
                    Text("Waiting for ${p.inviterName.ifBlank { "the inviter" }} to let you into ${p.circleName}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                }
                Caption("You'll see everyone on the map as soon as they approve.")
            } else {
                Text("You're not in a circle yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Caption("Create one and invite people, or join someone else's.")
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Create a circle", onCreate)
            TonalButton("Join a circle", onJoin, Modifier.fillMaxWidth(), icon = AppIcons.ScanQr)
        }
    }
}
