package app.quietmap.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
}

/**
 * The whole app UI, shared by Android and iOS. The host app owns the engine, permissions and
 * incoming invite links, and passes their current state in.
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
    var selectedCircle by remember { mutableStateOf<String?>(null) }
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    LaunchedEffect(incomingLink) {
        val l = incomingLink ?: return@LaunchedEffect
        onLinkHandled()
        push(Route.Join(InviteLinks.decode(l)))
    }

    // Ask once as soon as there's a circle to share with.
    LaunchedEffect(state.circles.isNotEmpty()) {
        if (state.circles.isNotEmpty() && state.sharing && !hasLocationPermission) onRequestLocation()
    }

    PlatformBackHandler(enabled = stack.size > 1) { pop() }

    CompositionLocalProvider(LocalPlatform provides platform) {
        QuietmapTheme {
            Surface(Modifier.fillMaxSize()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (val route = stack.last()) {
                        Route.Home -> HomeScreen(
                            state = state,
                            me = me,
                            selectedCircleId = selectedCircle,
                            hasLocationPermission = hasLocationPermission,
                            actions = actions,
                            onSelectCircle = { selectedCircle = it },
                            onOpenProfile = { c, p -> push(Route.Profile(c, p)) },
                            onAdd = { push(Route.Add(it)) },
                            onSettings = { push(Route.Settings) },
                            onRequestLocation = onRequestLocation,
                            onJoinLink = { push(Route.Join(InviteLinks.decode(it))) },
                        )
                        is Route.Profile -> ProfileScreen(state, me, route.circleId, route.pubkey, actions, ::pop)
                        is Route.Add -> AddScreen(state, me, route.circleId, actions, ::pop) { push(Route.Join(InviteLinks.decode(it))) }
                        is Route.Join -> JoinScreen(state, route.invite, actions) { stack.clear(); stack.add(Route.Home) }
                        Route.Settings -> SettingsScreen(state, me, relayStatus, sharingSettings, actions, ::pop)
                    }
                }
            }
        }
    }
}
