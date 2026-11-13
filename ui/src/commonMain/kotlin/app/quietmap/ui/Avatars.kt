package app.quietmap.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.crypto.fromBase64
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.model.AppState

/**
 * Default avatars: a pastel circle with the person's initial in a deeper shade of the same hue,
 * twelve hues to pick from. Everyone gets one derived from their key until they choose.
 */
object AvatarColors {
    data class Pair(val container: Color, val content: Color)

    val all = listOf(
        Pair(Color(0xFFE3E3FE), Color(0xFF3838F5)), // indigo
        Pair(Color(0xFFDDE7FC), Color(0xFF1251D3)), // blue
        Pair(Color(0xFFD8E8F0), Color(0xFF086DA0)), // teal blue
        Pair(Color(0xFFCDE4CD), Color(0xFF067906)), // green
        Pair(Color(0xFFEAE0F8), Color(0xFF661AFF)), // violet
        Pair(Color(0xFFF5E3FE), Color(0xFF9F00F0)), // purple
        Pair(Color(0xFFF6D8EC), Color(0xFFB8057C)), // magenta
        Pair(Color(0xFFF5D7D7), Color(0xFFBE0404)), // red
        Pair(Color(0xFFFEF5D0), Color(0xFF836B01)), // gold
        Pair(Color(0xFFEAE6D5), Color(0xFF7D6F40)), // sand
        Pair(Color(0xFFD2D2DC), Color(0xFF4F4F6D)), // slate
        Pair(Color(0xFFD7D7D9), Color(0xFF5C5C5C)), // grey
    )

    fun defaultIndex(pubkey: String): Int = try {
        (PlatformCrypto.sha256(pubkey.hexToBytes())[0].toInt() and 0xff) % all.size
    } catch (_: Throwable) {
        0
    }

    fun forIndex(index: Int?, pubkey: String): Pair = all[(index ?: defaultIndex(pubkey)).mod(all.size)]

    /** In dark mode the pastel becomes the text and a deep tint becomes the circle, like Signal. */
    @Composable
    fun themed(pair: Pair): Pair =
        if (isSystemInDarkTheme()) Pair(pair.content.copy(alpha = 0.55f).compositeOver(Color(0xFF121212)), pair.container) else pair
}

/** Everything needed to draw someone's avatar. */
data class AvatarInfo(val pubkey: String, val name: String, val colorIndex: Int?, val photo: String?, val verified: Boolean)

/** Looks up a member's avatar from their published profile, or ours for "me". */
fun avatarFor(state: AppState, pubkey: String, me: String, fallbackName: String): AvatarInfo {
    if (pubkey == me) return AvatarInfo(pubkey, state.myName.ifBlank { "You" }, state.myColor, state.myPhoto, verified = false)
    val p = state.profiles[pubkey]
    val name = state.contacts[pubkey]?.label ?: p?.name?.ifBlank { null } ?: fallbackName.ifBlank { "Unnamed" }
    return AvatarInfo(pubkey, name, p?.color, p?.photo, verified = state.contacts[pubkey]?.verified == true)
}

/** Decoded photos, keyed by the base64 text, so scrolling lists don't decode the same JPEG twice. */
private object PhotoCache {
    private val map = LinkedHashMap<String, ImageBitmap?>()
    fun get(b64: String): ImageBitmap? {
        map[b64]?.let { return it }
        val img = try { decodeImage(b64.fromBase64()) } catch (_: Throwable) { null }
        map[b64] = img
        if (map.size > 64) map.remove(map.keys.first())
        return img
    }
}

/**
 * A round avatar: the person's photo if they shared one, otherwise their initial on their color.
 * Verified people get a small check badge (bottom-right), like a verified safety number.
 */
@Composable
fun Avatar(
    info: AvatarInfo,
    size: Dp = 48.dp,
    dimmed: Boolean = false,
    showVerified: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = AvatarColors.themed(AvatarColors.forIndex(info.colorIndex, info.pubkey))
    val alpha by animateFloatAsState(if (dimmed) 0.45f else 1f, label = "avatarDim")
    Box(modifier.size(size)) {
        Crossfade(targetState = info.photo, label = "avatarPhoto") { photo ->
            val image = remember(photo) { photo?.let { PhotoCache.get(it) } }
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = alpha,
                    modifier = Modifier.size(size).clip(CircleShape),
                )
            } else {
                Box(
                    Modifier.size(size).clip(CircleShape).background(colors.container.copy(alpha = colors.container.alpha * alpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        Format.initial(info.name),
                        color = colors.content.copy(alpha = alpha),
                        fontWeight = FontWeight.Medium,
                        fontSize = (size.value * 0.42f).sp,
                    )
                }
            }
        }
        if (showVerified) VerifiedBadge(info.verified, size)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.VerifiedBadge(visible: Boolean, avatarSize: Dp) {
    val scale by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f), label = "badge")
    if (scale <= 0.01f) return
    val badge = (avatarSize.value * 0.36f).coerceIn(14f, 28f).dp
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 2.dp, y = 2.dp)
            .scale(scale)
            .size(badge)
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .padding(2.dp)
            .background(verifiedGreen(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = "Verified", tint = Color.White, modifier = Modifier.size(badge * 0.62f))
    }
}

/** Map marker color for a member: the deep shade of their avatar color. */
fun markerColorHex(info: AvatarInfo): String {
    val c = AvatarColors.forIndex(info.colorIndex, info.pubkey).content
    fun h(v: Float) = (v * 255).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#" + h(c.red) + h(c.green) + h(c.blue)
}
