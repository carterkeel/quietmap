package app.quietmap.core.crypto

/**
 * NIP-44 version 2 encryption, following https://github.com/nostr-protocol/nips/blob/master/44.md
 * ChaCha20 + HMAC-SHA256 with HKDF-derived keys and length-hiding padding.
 */
object Nip44 {
    private val SALT = "nip44-v2".encodeToByteArray()
    private const val VERSION: Byte = 2

    /** Conversation key between two Nostr keys. */
    fun conversationKey(secret: ByteArray, theirXOnly: ByteArray): ByteArray =
        Hkdf.extract(SALT, Keys.sharedX(secret, theirXOnly))

    /** Conversation key for a symmetric group secret (Quietmap circles). */
    fun conversationKeyFromSecret(groupSecret: ByteArray, context: String): ByteArray =
        Hkdf.extract(SALT, groupSecret + context.encodeToByteArray())

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = PlatformCrypto.randomBytes(32)): String {
        require(conversationKey.size == 32 && nonce.size == 32)
        val keys = messageKeys(conversationKey, nonce)
        val padded = pad(plaintext.encodeToByteArray())
        val ciphertext = PlatformCrypto.chacha20(keys.chachaKey, keys.chachaNonce, padded)
        val mac = PlatformCrypto.hmacSha256(keys.hmacKey, nonce + ciphertext)
        return (byteArrayOf(VERSION) + nonce + ciphertext + mac).toBase64()
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(payload.isNotEmpty() && payload[0] != '#') { "unsupported encryption version" }
        val data = payload.fromBase64()
        require(data.size in 99..65603) { "invalid payload size" }
        require(data[0] == VERSION) { "unknown version ${data[0]}" }
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val keys = messageKeys(conversationKey, nonce)
        val expected = PlatformCrypto.hmacSha256(keys.hmacKey, nonce + ciphertext)
        require(constantTimeEquals(expected, mac)) { "invalid MAC" }
        val padded = PlatformCrypto.chacha20(keys.chachaKey, keys.chachaNonce, ciphertext)
        return unpad(padded).decodeToString()
    }

    internal fun calcPaddedLen(unpaddedLen: Int): Int {
        require(unpaddedLen > 0)
        if (unpaddedLen <= 32) return 32
        val nextPower = 1 shl (32 - (unpaddedLen - 1).countLeadingZeroBits())
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    private fun pad(plain: ByteArray): ByteArray {
        require(plain.size in 1..65535) { "plaintext must be 1..65535 bytes" }
        val out = ByteArray(2 + calcPaddedLen(plain.size))
        out[0] = (plain.size ushr 8).toByte()
        out[1] = (plain.size and 0xff).toByte()
        plain.copyInto(out, 2)
        return out
    }

    private fun unpad(padded: ByteArray): ByteArray {
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(len > 0 && padded.size == 2 + calcPaddedLen(len)) { "invalid padding" }
        return padded.copyOfRange(2, 2 + len)
    }

    private class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        val k = Hkdf.expand(conversationKey, nonce, 76)
        return MessageKeys(k.copyOfRange(0, 32), k.copyOfRange(32, 44), k.copyOfRange(44, 76))
    }

}
