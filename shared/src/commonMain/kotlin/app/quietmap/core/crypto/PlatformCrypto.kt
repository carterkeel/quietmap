package app.quietmap.core.crypto

/**
 * Low-level primitives that come from audited platform libraries.
 * Android: javax.crypto + BouncyCastle. iOS (later): CryptoKit + libsodium.
 * Nothing here is implemented by hand.
 */
expect object PlatformCrypto {
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** RFC 8439 ChaCha20 stream cipher (12-byte nonce, block counter starting at 0). */
    fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray

    fun randomBytes(size: Int): ByteArray
}

/** RFC 5869 HKDF-SHA256 built from HMAC. */
object Hkdf {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = PlatformCrypto.hmacSha256(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * 32)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = PlatformCrypto.hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        return out
    }
}

/** Constant-time comparison for MACs. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
