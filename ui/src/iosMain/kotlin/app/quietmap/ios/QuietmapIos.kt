package app.quietmap.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import app.quietmap.core.Engine
import app.quietmap.ui.QuietmapScreens
import app.quietmap.ui.epochSeconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.UIKit.UIViewController
import platform.darwin.NSObjectProtocol

/**
 * The iOS app's Kotlin side. Swift calls three things:
 * - `QuietmapIos.shared.start()` from `application(_:didFinishLaunchingWithOptions:)`, including
 *   background relaunches by significant-change monitoring;
 * - `QuietmapIos.shared.handleUrl(url:)` for `quietmap://join` links;
 * - `QuietmapIosKt.MainViewController()` for the screen.
 */
object QuietmapIos {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: Engine by lazy {
        Engine(
            store = IosStateStore(),
            scope = scope,
            now = ::epochSeconds,
            // A Kotlin String passed through C varargs isn't bridged to NSString and crashes; cast it.
            log = { NSLog("Quietmap: %@", it as NSString) },
        ).also { it.start() }
    }

    /** Created on first use, on the main thread, as CoreLocation requires. */
    val location: IosLocationService by lazy { IosLocationService(engine, scope) }

    internal val platform: IosPlatform by lazy { IosPlatform(location) }

    internal val incomingLink = MutableStateFlow<String?>(null)
    private var unlockObserver: NSObjectProtocol? = null

    /**
     * Starts relays and, if sharing is on, location updates. After a reboot iOS can relaunch the
     * app before the phone's first unlock, when saved state can't be read yet; then this waits
     * for the unlock instead of starting with an empty state. Call on the main thread.
     */
    fun start() {
        if (!UIApplication.sharedApplication.isProtectedDataAvailable()) {
            if (unlockObserver == null) {
                unlockObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                    UIApplicationProtectedDataDidBecomeAvailable,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                ) { _ ->
                    unlockObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
                    unlockObserver = null
                    start()
                }
            }
            return
        }
        location.update()
    }

    /** Handles a `quietmap://join…` link. Returns false for links that aren't Quietmap's. */
    fun handleUrl(url: String): Boolean {
        if (!url.startsWith("quietmap://")) return false
        incomingLink.value = url
        return true
    }
}

/** The app's only screen: the shared Compose UI. */
@Suppress("FunctionName", "unused") // Called from Swift.
fun MainViewController(): UIViewController = ComposeUIViewController { IosRoot() }

@Composable
private fun IosRoot() {
    val app = QuietmapIos
    val platform = app.platform
    val location = app.location
    val link by app.incomingLink.collectAsState()
    val authorization by location.authorization.collectAsState()
    val message by platform.message.collectAsState()
    val showIndicator by location.showIndicator.collectAsState()
    val sharingSettings = remember(authorization, showIndicator) { platform.sharingSettings(location.hasAlways, showIndicator) }

    Box(Modifier.fillMaxSize()) {
        QuietmapScreens(
            engine = app.engine,
            scope = app.scope,
            platform = platform,
            hasLocationPermission = location.hasPermission,
            onRequestLocation = location::requestPermission,
            sharingSettings = sharingSettings,
            incomingLink = link,
            onLinkHandled = { app.incomingLink.value = null },
        )
        message?.let { text ->
            LaunchedEffect(text) {
                delay(3_000)
                platform.clearMessage(text)
            }
            Surface(
                color = Color(0xEE1F1F1F),
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
            ) {
                Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}
