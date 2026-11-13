package app.quietmap.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quietmap.core.Engine
import app.quietmap.core.protocol.InviteData
import app.quietmap.core.protocol.InviteLinks
import kotlinx.coroutines.CoroutineScope

sealed interface Route {
    data object Home : Route
    data class Profile(val circleId: String, val pubkey: String) : Route
    data class Add(val circleId: String) : Route
    data class Join(val invite: InviteData?) : Route
    data object Settings : Route
    data object EditProfile : Route
    data object JoinOptions : Route
    data object Backup : Route
    data object Restore : Route
}

/**
 * The whole app UI, shared by Android and iOS. The host app owns the engine, permissions and
 * incoming invite links, and passes their current state in.
 *
 * The home screen (map and people) is always composed. Other screens slide in over it, so the
 * map keeps its position and never reloads when you go back.
 *
 * @param scope long-lived scope for engine actions, so they finish even if the screen goes away.
 * @param sharingSettings platform rows shown under Settings → Sharing.
 * @param incomingLink an invite link the app was opened with; call [onLinkHandled] once shown.
 */
@Composable
fun QuietmapScreens(
    engine: Engine,
    scope: CoroutineScope,
    platform: PlatformBridge,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    sharingSettings: List<SettingItem>,
    incomingLink: String?,
    onLinkHandled: () -> Unit,
) {
    val actions = remember(engine, scope, platform) { Actions(engine, scope, platform) }
    val state by engine.state.collectAsStateWithLifecycle()
    val relayStatus by engine.relayStatus.collectAsStateWithLifecycle()
    val me = engine.myPubkey

    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    // Reopen on the circle you last looked at.
    var selectedCircle by remember { mutableStateOf(state.lastCircleId) }
    val selectCircle: (String) -> Unit = { selectedCircle = it; actions.setLastCircle(it) }
    // First-run setup shows for new installs. Existing installs that already have circles skip it.
    val needsSetup = remember { !state.setupDone && state.circles.isEmpty() }
    LaunchedEffect(Unit) { if (!needsSetup && !state.setupDone) actions.setSetupDone() }
    val showSetup = needsSetup && !state.setupDone
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val openLink: (String) -> Unit = { push(Route.Join(InviteLinks.decode(it))) }
    val scan: () -> Unit = { platform.scanQrCode { text -> if (text != null) openLink(text) } }

    LaunchedEffect(incomingLink) {
        val l = incomingLink ?: return@LaunchedEffect
        onLinkHandled()
        openLink(l)
    }

    // Ask once as soon as there's a circle to share with (setup asks on its own last step).
    LaunchedEffect(state.circles.isNotEmpty(), showSetup) {
        if (!showSetup && state.circles.isNotEmpty() && state.sharing && !hasLocationPermission) onRequestLocation()
    }

    PlatformBackHandler(enabled = stack.size > 1) { pop() }

    CompositionLocalProvider(LocalPlatform provides platform) {
        QuietmapTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (showSetup) {
                        SetupScreen(
                            state = state,
                            me = me,
                            actions = actions,
                            hasLocationPermission = hasLocationPermission,
                            onRequestLocation = onRequestLocation,
                            onJoinLink = openLink,
                            onScan = scan,
                            onRestore = { push(Route.Restore) },
                            onCircleCreated = selectCircle,
                        )
                    } else {
                        HomeScreen(
                            state = state,
                            me = me,
                            selectedCircleId = selectedCircle,
                            hasLocationPermission = hasLocationPermission,
                            actions = actions,
                            onSelectCircle = selectCircle,
                            onOpenProfile = { c, p -> push(Route.Profile(c, p)) },
                            onAdd = { push(Route.Add(it)) },
                            onJoin = { push(Route.JoinOptions) },
                            onSettings = { push(Route.Settings) },
                            onRequestLocation = onRequestLocation,
                        )
                    }

                    AnimatedContent(
                        targetState = stack.toList(),
                        // Full size from the start, so screens slide in rather than grow out of a corner.
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = { screenTransition() },
                        contentKey = { it.last() },
                        label = "screens",
                    ) { routes ->
                        when (val route = routes.last()) {
                            Route.Home -> Box(Modifier.fillMaxSize()) // the always-composed home shows through
                            else -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                when (route) {
                                    is Route.Profile -> ProfileScreen(state, me, route.circleId, route.pubkey, actions, ::pop)
                                    is Route.Add -> AddScreen(state, me, route.circleId, actions, ::pop)
                                    is Route.Join -> JoinScreen(state, route.invite, actions, ::home)
                                    Route.JoinOptions -> JoinOptionsScreen(::pop, scan, openLink)
                                    Route.Settings -> SettingsScreen(
                                        state, me, relayStatus, sharingSettings, actions, ::pop,
                                        onEditProfile = { push(Route.EditProfile) },
                                        onBackup = { push(Route.Backup) },
                                        onRestore = { push(Route.Restore) },
                                    )
                                    Route.EditProfile -> EditProfileScreen(state, me, actions, ::pop)
                                    Route.Backup -> BackupScreen(actions, ::pop)
                                    Route.Restore -> RestoreScreen(actions, ::pop) { selectedCircle = null; home() }
                                    Route.Home -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val slideSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
private val fadeSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)

/** Where a screen comes in from when it opens, and goes back to when it closes. */
private enum class Edge { Right, Left, Bottom }

private fun Route.edge(): Edge = when (this) {
    Route.Settings -> Edge.Left
    is Route.Profile -> Edge.Bottom
    else -> Edge.Right
}

/**
 * Opening a screen slides it in over the current one from its [Edge]; going back slides it out the
 * same way, while the screen underneath shifts back a little.
 */
private fun AnimatedContentTransitionScope<List<Route>>.screenTransition(): ContentTransform {
    val forward = targetState.size >= initialState.size
    val moving = if (forward) targetState.last() else initialState.last()
    val (enter, exit) = when (moving.edge()) {
        Edge.Right ->
            if (forward) slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec, initialAlpha = 0.7f) to
                slideOutHorizontally(slideSpec) { -it / 5 } + fadeOut(fadeSpec, targetAlpha = 0.6f)
            else slideInHorizontally(slideSpec) { -it / 5 } + fadeIn(fadeSpec, initialAlpha = 0.6f) to
                slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec, targetAlpha = 0.7f)
        Edge.Left ->
            if (forward) slideInHorizontally(slideSpec) { -it } + fadeIn(fadeSpec, initialAlpha = 0.7f) to
                slideOutHorizontally(slideSpec) { it / 5 } + fadeOut(fadeSpec, targetAlpha = 0.6f)
            else slideInHorizontally(slideSpec) { it / 5 } + fadeIn(fadeSpec, initialAlpha = 0.6f) to
                slideOutHorizontally(slideSpec) { -it } + fadeOut(fadeSpec, targetAlpha = 0.7f)
        Edge.Bottom ->
            if (forward) slideInVertically(slideSpec) { it } to
                slideOutVertically(slideSpec) { -it / 12 } + fadeOut(fadeSpec, targetAlpha = 0.6f)
            else slideInVertically(slideSpec) { -it / 12 } + fadeIn(fadeSpec, initialAlpha = 0.6f) to
                slideOutVertically(slideSpec) { it }
    }
    return (enter togetherWith exit)
        .apply { targetContentZIndex = if (forward) 1f else -1f }
        .using(null) // no size animation: every screen is full size
}
