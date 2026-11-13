package app.quietmap.core.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i ->
        val hi = HEX.indexOf(this[2 * i].lowercaseChar())
        val lo = HEX.indexOf(this[2 * i + 1].lowercaseChar())
        require(hi >= 0 && lo >= 0) { "bad hex" }
        ((hi shl 4) or lo).toByte()
    }
}

@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64(): String = Base64.Default.encode(this)

@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64(): ByteArray = Base64.Default.decode(this)

@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64Url(): String = Base64.UrlSafe.encode(this).trimEnd('=')

@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64Url(): ByteArray {
    val padded = this + "=".repeat((4 - length % 4) % 4)
    return Base64.UrlSafe.decode(padded)
}
