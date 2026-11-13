package app.quietmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.quietmap.core.protocol.InviteData
import app.quietmap.core.protocol.InviteLinks
import app.quietmap.ui.Actions
import app.quietmap.ui.AddScreen
import app.quietmap.ui.HomeScreen
import app.quietmap.ui.JoinScreen
import app.quietmap.ui.ProfileScreen
import app.quietmap.ui.QuietmapTheme
import app.quietmap.ui.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface Route {
    data object Home : Route
    data class Profile(val circleId: String, val pubkey: String) : Route
    data class Add(val circleId: String) : Route
    data class Join(val invite: InviteData?) : Route
    data object Settings : Route
}

class MainActivity : ComponentActivity() {
    /** Invite links opened while the app is running arrive here. */
    private val incomingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        incomingLink.value = intent?.dataString
        setContent {
            QuietmapTheme {
                Surface(Modifier.fillMaxSize()) {
                    Root(incomingLink)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { incomingLink.value = it }
    }
}

@Composable
private fun Root(incomingLink: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val app = QuietmapApp.from(context)
    val actions = remember { Actions(context) }
    val state by app.engine.state.collectAsStateWithLifecycle()
    val relayStatus by app.engine.relayStatus.collectAsStateWithLifecycle()
    val link by incomingLink.collectAsStateWithLifecycle()
    val me = app.engine.myPubkey

    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    var selectedCircle by remember { mutableStateOf<String?>(null) }
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        incomingLink.value = null
        push(Route.Join(InviteLinks.decode(l)))
    }

    // Permission state, rechecked whenever the app comes back to the foreground.
    var hasLocation by remember { mutableStateOf(false) }
    var hasBackground by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasLocation = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            hasBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            app.ensureSharingService()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        app.ensureSharingService()
    }
    val requestLocation = {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }
    // Ask once as soon as there's a circle to share with.
    LaunchedEffect(state.circles.isNotEmpty()) {
        if (state.circles.isNotEmpty() && state.sharing && !hasLocation) requestLocation()
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val route = stack.last()) {
            Route.Home -> HomeScreen(
                state = state,
                me = me,
                selectedCircleId = selectedCircle,
                hasLocationPermission = hasLocation,
                actions = actions,
                onSelectCircle = { selectedCircle = it },
                onOpenProfile = { c, p -> push(Route.Profile(c, p)) },
                onAdd = { push(Route.Add(it)) },
                onSettings = { push(Route.Settings) },
                onRequestLocation = requestLocation,
                onJoinLink = { push(Route.Join(InviteLinks.decode(it))) },
            )
            is Route.Profile -> ProfileScreen(state, me, route.circleId, route.pubkey, actions, ::pop)
            is Route.Add -> AddScreen(state, me, route.circleId, actions, ::pop) { push(Route.Join(InviteLinks.decode(it))) }
            is Route.Join -> JoinScreen(state, route.invite, actions) { stack.clear(); stack.add(Route.Home) }
            Route.Settings -> SettingsScreen(state, me, relayStatus, hasBackground, actions, ::pop)
        }
    }
}

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
