package app.quietmap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// -------------------------------------------------------------------------------------------------
// Screen chrome
// -------------------------------------------------------------------------------------------------

/** A plain top bar: back arrow, title, optional actions. No divider, like Signal. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (onBack == null) 20.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.width(4.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

/** A full screen with a top bar and a scrolling column of content. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack, actions)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            content = content,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Settings-style rows
// -------------------------------------------------------------------------------------------------

/** Small bold section title above a group of rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** A Signal-style list row: optional leading icon, title, optional subtitle, optional trailing content. */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (titleColor == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else titleColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    SettingsRow(title, subtitle, icon, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// -------------------------------------------------------------------------------------------------
// Buttons, cards, text
// -------------------------------------------------------------------------------------------------

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), modifier = modifier) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun Mono(text: String, size: Int = 13, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, weight: FontWeight = FontWeight.Normal) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size.sp, color = color, fontWeight = weight)
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A rounded pill used for small status labels. */
@Composable
fun StatusPill(text: String, container: Color, content: Color, onClick: (() -> Unit)? = null) {
    Surface(color = container, shape = RoundedCornerShape(50), onClick = onClick ?: {}, enabled = onClick != null) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(text, color = content, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Icons not in the core material set (drawn as 24dp strokes)
// -------------------------------------------------------------------------------------------------

object AppIcons {
    private fun stroke(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()

    val QrCode: ImageVector = stroke("QrCode") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(16f, 14f); moveTo(19f, 14f); lineTo(20f, 14f); lineTo(20f, 16f)
        moveTo(14f, 17f); lineTo(14f, 20f); lineTo(17f, 20f); moveTo(17f, 17f); lineTo(20f, 17f); lineTo(20f, 20f)
    }

    val ScanQr: ImageVector = stroke("ScanQr") {
        moveTo(3f, 8f); lineTo(3f, 4f); lineTo(7f, 4f)
        moveTo(17f, 4f); lineTo(21f, 4f); lineTo(21f, 8f)
        moveTo(21f, 16f); lineTo(21f, 20f); lineTo(17f, 20f)
        moveTo(7f, 20f); lineTo(3f, 20f); lineTo(3f, 16f)
        moveTo(7f, 12f); lineTo(17f, 12f)
    }

    val Camera: ImageVector = stroke("Camera") {
        moveTo(4f, 8f); lineTo(7.5f, 8f); lineTo(9f, 5.5f); lineTo(15f, 5.5f); lineTo(16.5f, 8f); lineTo(20f, 8f)
        lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(15.5f, 13f); arcTo(3.5f, 3.5f, 0f, true, true, 8.5f, 13f); arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 13f); close()
    }

    val MyLocation: ImageVector = stroke("MyLocation") {
        moveTo(18f, 12f); arcTo(6f, 6f, 0f, true, true, 6f, 12f); arcTo(6f, 6f, 0f, true, true, 18f, 12f); close()
        moveTo(12f, 2f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 22f)
        moveTo(2f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(22f, 12f)
    }

    val Battery: ImageVector = stroke("Battery") {
        moveTo(8f, 4f); lineTo(16f, 4f); lineTo(16f, 21f); lineTo(8f, 21f); close()
        moveTo(10.5f, 2f); lineTo(13.5f, 2f)
    }

    val Relay: ImageVector = stroke("Relay") {
        moveTo(5f, 12f); arcTo(7f, 7f, 0f, false, true, 19f, 12f)
        moveTo(8.5f, 12f); arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 12f)
        moveTo(12f, 12f); lineTo(12f, 20f)
    }

    val Circles: ImageVector = stroke("Circles") {
        moveTo(13f, 9f); arcTo(4f, 4f, 0f, true, true, 5f, 9f); arcTo(4f, 4f, 0f, true, true, 13f, 9f); close()
        moveTo(2f, 20f); curveTo(2f, 16f, 5f, 15f, 9f, 15f); curveTo(13f, 15f, 16f, 16f, 16f, 20f)
        moveTo(15f, 5.5f); arcTo(3.5f, 3.5f, 0f, false, true, 15f, 12.5f)
        moveTo(18f, 15.5f); curveTo(20.5f, 16.3f, 22f, 17.5f, 22f, 20f)
    }

    // Standard UI icons, drawn here so the app doesn't depend on the frozen material-icons library.

    val ArrowBack: ImageVector = stroke("ArrowBack") {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }

    val Add: ImageVector = stroke("Add") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Check: ImageVector = stroke("Check") {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }

    val Close: ImageVector = stroke("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val ChevronDown: ImageVector = stroke("ChevronDown") {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    }

    val Delete: ImageVector = stroke("Delete") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
        moveTo(10f, 11f); lineTo(10f, 16f); moveTo(14f, 11f); lineTo(14f, 16f)
    }

    val Edit: ImageVector = stroke("Edit") {
        moveTo(4f, 20f); lineTo(8f, 19f); lineTo(19f, 8f); lineTo(16f, 5f); lineTo(5f, 16f); close()
        moveTo(14f, 7f); lineTo(17f, 10f)
    }

    val Face: ImageVector = stroke("Face") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(8.5f, 14.5f); curveTo(10f, 16.5f, 14f, 16.5f, 15.5f, 14.5f)
        moveTo(9f, 9.5f); lineTo(9f, 10f); moveTo(15f, 9.5f); lineTo(15f, 10f)
    }

    val Info: ImageVector = stroke("Info") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 7.5f); lineTo(12f, 8f)
    }

    val LocationPin: ImageVector = stroke("LocationPin") {
        moveTo(12f, 21f); curveTo(12f, 21f, 5f, 14.5f, 5f, 9.5f); arcTo(7f, 7f, 0f, false, true, 19f, 9.5f); curveTo(19f, 14.5f, 12f, 21f, 12f, 21f); close()
        moveTo(14.5f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 14.5f, 9.5f); close()
    }

    val Lock: ImageVector = stroke("Lock") {
        moveTo(5f, 11f); lineTo(19f, 11f); lineTo(19f, 20f); lineTo(5f, 20f); close()
        moveTo(8f, 11f); lineTo(8f, 8f); arcTo(4f, 4f, 0f, false, true, 16f, 8f); lineTo(16f, 11f)
    }

    val Person: ImageVector = stroke("Person") {
        moveTo(16f, 8f); arcTo(4f, 4f, 0f, true, true, 8f, 8f); arcTo(4f, 4f, 0f, true, true, 16f, 8f); close()
        moveTo(4f, 20f); curveTo(4f, 16f, 7.5f, 14f, 12f, 14f); curveTo(16.5f, 14f, 20f, 16f, 20f, 20f)
    }

    val Refresh: ImageVector = stroke("Refresh") {
        moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 16.9f, 7f)
        moveTo(17f, 3f); lineTo(17f, 7f); lineTo(13f, 7f)
    }

    val Settings: ImageVector = stroke("Settings") {
        moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f); close()
        moveTo(12f, 2.5f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 21.5f)
        moveTo(2.5f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(21.5f, 12f)
        moveTo(5.3f, 5.3f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(18.7f, 18.7f)
        moveTo(5.3f, 18.7f); lineTo(7f, 17f); moveTo(17f, 7f); lineTo(18.7f, 5.3f)
        moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 5f, 12f); arcTo(7f, 7f, 0f, true, true, 19f, 12f); close()
    }

    val Share: ImageVector = stroke("Share") {
        moveTo(12f, 15f); lineTo(12f, 3f)
        moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
        moveTo(5f, 12f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 12f)
    }

    val Star: ImageVector = stroke("Star") {
        moveTo(12f, 3f); lineTo(14.8f, 8.8f); lineTo(21f, 9.6f); lineTo(16.5f, 14f); lineTo(17.6f, 20.2f)
        lineTo(12f, 17.2f); lineTo(6.4f, 20.2f); lineTo(7.5f, 14f); lineTo(3f, 9.6f); lineTo(9.2f, 8.8f); close()
    }

    val Leave: ImageVector = stroke("Leave") {
        moveTo(10f, 4f); lineTo(5f, 4f); lineTo(5f, 20f); lineTo(10f, 20f)
        moveTo(10f, 12f); lineTo(20f, 12f)
        moveTo(16f, 8f); lineTo(20f, 12f); lineTo(16f, 16f)
    }

    val Shield: ImageVector = stroke("Shield") {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); curveTo(19f, 16f, 16f, 19.5f, 12f, 21f)
        curveTo(8f, 19.5f, 5f, 16f, 5f, 11f); lineTo(5f, 6f); close()
    }
}

// -------------------------------------------------------------------------------------------------
// Prompts that slide up from the bottom instead of popping in from the centre
// -------------------------------------------------------------------------------------------------

/** A yes/no question as a bottom sheet. [destructive] colours the confirm button red. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismiss: String = "Cancel",
    header: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (header != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { header() }
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            body?.invoke(this)
            Spacer(Modifier.heightIn(min = 4.dp))
            if (destructive) DangerButton(confirm, onConfirm) else PrimaryButton(confirm, onConfirm)
            androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(dismiss) }
        }
    }
}

/** Asks for one line of text in a bottom sheet. */
@Composable
fun TextSheet(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    ConfirmSheet(
        title = title,
        text = null,
        confirm = confirm,
        onConfirm = { if (text.isNotBlank()) onConfirm(text.trim()) },
        onDismiss = onDismiss,
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(40) },
            label = { Text(label) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
