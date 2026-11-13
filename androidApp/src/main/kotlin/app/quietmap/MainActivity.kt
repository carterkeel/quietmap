package app.quietmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.quietmap.ui.QuietmapScreens
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Invite links opened while the app is running arrive here. */
    private val incomingLink = MutableStateFlow<String?>(null)

    private var onPhoto: ((String?) -> Unit)? = null
    private var onScan: ((String?) -> Unit)? = null

    /** System photo picker: no storage permission needed. */
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val callback = onPhoto ?: return@registerForActivityResult
        onPhoto = null
        if (uri == null) return@registerForActivityResult callback(null)
        val app = QuietmapApp.from(this)
        app.scope.launch {
            val photo = PhotoProcessor.process(this@MainActivity, uri)
            withContext(Dispatchers.Main) {
                if (photo == null) Toast.makeText(this@MainActivity, "Couldn't use that photo", Toast.LENGTH_LONG).show()
                callback(photo)
            }
        }
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val callback = onScan ?: return@registerForActivityResult
        onScan = null
        callback(result.contents)
    }

    val platform: AndroidPlatform by lazy {
        AndroidPlatform(
            context = this,
            photoPicker = { cb ->
                onPhoto = cb
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            qrScanner = { cb ->
                onScan = cb
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan a Quietmap invite")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        incomingLink.value = intent?.dataString
        setContent { Root(incomingLink, platform) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { incomingLink.value = it }
    }
}

/** Android host for the shared screens: permissions, settings rows and invite links. */
@Composable
private fun Root(incomingLink: MutableStateFlow<String?>, platform: AndroidPlatform) {
    val context = LocalContext.current
    val app = QuietmapApp.from(context)
    val link by incomingLink.collectAsStateWithLifecycle()

    // Permission state, rechecked whenever the app comes back to the foreground.
    var hasLocation by remember { mutableStateOf(false) }
    var hasBackground by remember { mutableStateOf(false) }
    var resumeCount by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasLocation = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            hasBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            resumeCount++
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
    // Battery optimisation can change in system settings, so recheck it on every resume too.
    val sharingSettings = remember(hasBackground, resumeCount) { platform.sharingSettings(hasBackground) }

    QuietmapScreens(
        engine = app.engine,
        scope = app.scope,
        platform = platform,
        hasLocationPermission = hasLocation,
        onRequestLocation = requestLocation,
        sharingSettings = sharingSettings,
        incomingLink = link,
        onLinkHandled = { incomingLink.value = null },
    )
}

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
