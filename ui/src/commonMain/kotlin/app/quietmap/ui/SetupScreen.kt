package app.quietmap.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.quietmap.core.model.AppState

private enum class SetupStep { Name, Photo, Circle, Location }

/**
 * First run, one thing per screen: your name, a profile photo (optional), a circle to create or
 * join, and location access. Then the app opens on the map.
 */
@Composable
fun SetupScreen(
    state: AppState,
    me: String,
    actions: Actions,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    onJoinLink: (String) -> Unit,
    onScan: () -> Unit,
    onRestore: () -> Unit,
    onCircleCreated: (String) -> Unit,
) {
    val platform = LocalPlatform.current
    var step by remember { mutableStateOf(if (state.circles.isNotEmpty()) SetupStep.Location else SetupStep.Name) }
    var forward by remember { mutableStateOf(true) }
    fun go(to: SetupStep) { forward = to.ordinal > step.ordinal; step = to }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Back arrow and progress dots.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp)) {
                androidx.compose.animation.AnimatedVisibility(step.ordinal in 1..2, enter = fadeIn(), exit = fadeOut()) {
                    IconButton(onClick = { go(SetupStep.entries[step.ordinal - 1]) }) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SetupStep.entries.forEach { s ->
                    val width by animateDpAsState(if (s == step) 22.dp else 8.dp, label = "dot")
                    val color by animateColorAsState(if (s.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor")
                    Box(Modifier.height(8.dp).width(width).clip(RoundedCornerShape(50)).background(color))
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }

        val slide = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(slide) { it * dir } + fadeIn()).togetherWith(slideOutHorizontally(slide) { -it * dir / 3 } + fadeOut())
            },
            label = "setup",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (current) {
                    SetupStep.Name -> NameStep(state, actions, onNext = { go(SetupStep.Photo) }, onRestore = onRestore)
                    SetupStep.Photo -> PhotoStep(state, me, actions, platform, onNext = { go(SetupStep.Circle) })
                    SetupStep.Circle -> CircleStep(
                        actions = actions,
                        onCreated = { id -> onCircleCreated(id); go(SetupStep.Location) },
                        onJoinLink = onJoinLink,
                        onScan = onScan,
                    )
                    SetupStep.Location -> LocationStep(hasLocationPermission, onRequestLocation, onDone = { actions.setSetupDone() })
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, text: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun ColumnScope.NameStep(state: AppState, actions: Actions, onNext: () -> Unit, onRestore: () -> Unit) {
    var name by remember { mutableStateOf(state.myName) }
    Spacer(Modifier.height(8.dp))
    BigIcon(AppIcons.Add)
    StepTitle("What should people call you?", "Your name is shown only to people in your circles. There's no account and no phone number.")
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40) },
        label = { Text("Your name") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryButton("Continue", { actions.setName(name); onNext() }, enabled = name.isNotBlank())
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onRestore) { Text("Restore from a backup") }
}

@Composable
private fun ColumnScope.PhotoStep(state: AppState, me: String, actions: Actions, platform: PlatformBridge, onNext: () -> Unit) {
    val info = avatarFor(state, me, me, state.myName)
    StepTitle("Add a profile photo", "Optional. Use a photo, or your initial on a color you pick.")
    Box(Modifier.clip(CircleShape).clickable { platform.pickProfilePhoto { if (it != null) actions.setAvatar(state.myColor, it) } }) {
        Avatar(info, size = 120.dp)
    }
    TonalButton(if (state.myPhoto == null) "Choose a photo" else "Change photo", { platform.pickProfilePhoto { if (it != null) actions.setAvatar(state.myColor, it) } }, icon = AppIcons.Camera)
    AvatarColorGrid(state.myName, me, state.myColor ?: AvatarColors.defaultIndex(me), photoSet = state.myPhoto != null, horizontalPadding = 0.dp) { actions.setAvatar(it, null) }
    Spacer(Modifier.height(24.dp))
    PrimaryButton(if (state.myPhoto == null && state.myColor == null) "Skip" else "Continue", onNext)
}

@Composable
private fun ColumnScope.CircleStep(actions: Actions, onCreated: (String) -> Unit, onJoinLink: (String) -> Unit, onScan: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) } // 0 = choose, 1 = create, 2 = join
    var circleName by remember { mutableStateOf("Family") }
    var link by remember { mutableStateOf("") }
    StepTitle("Your circle", "A circle is a group that sees each other on the map, like your family.")
    ChoiceCard("Create a circle", "Start one and invite people", AppIcons.Circles, selected = mode == 1) { mode = 1 }
    AnimatedVisibility(mode == 1, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = circleName,
                onValueChange = { circleName = it.take(40) },
                label = { Text("Circle name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("Create", { actions.createCircle(circleName, onCreated) }, enabled = circleName.isNotBlank())
        }
    }
    ChoiceCard("Join a circle", "Use an invite QR code or link", AppIcons.ScanQr, selected = mode == 2) { mode = 2 }
    AnimatedVisibility(mode == 2, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalButton("Scan QR code", onScan, Modifier.fillMaxWidth(), icon = AppIcons.ScanQr)
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Or paste an invite link") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(link.isNotBlank()) { PrimaryButton("Open invite", { onJoinLink(link) }) }
        }
    }
}

@Composable
private fun ColumnScope.LocationStep(hasPermission: Boolean, onRequest: () -> Unit, onDone: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    BigIcon(AppIcons.LocationPin)
    StepTitle(
        "Share your location",
        "Quietmap needs location access to show you to your circle. Your location is encrypted on this phone before it goes anywhere.",
    )
    InfoCard {
        Text("You're always in control", style = MaterialTheme.typography.titleSmall)
        Caption("${LocalPlatform.current.sharingIndicatorText}, and you can pause sharing with one tap on the map.")
    }
    Spacer(Modifier.height(24.dp))
    if (hasPermission) {
        PrimaryButton("Open the map", onDone)
    } else {
        PrimaryButton("Allow location", onRequest)
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}

@Composable
private fun BigIcon(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
    }
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "choice")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(2.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Caption(subtitle)
            }
        }
    }
}
