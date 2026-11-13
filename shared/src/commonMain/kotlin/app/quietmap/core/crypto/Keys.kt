package app.quietmap.core.crypto

import fr.acinq.secp256k1.Secp256k1

/** A secp256k1 keypair as used by Nostr: 32-byte secret, 32-byte x-only public key. */
class KeyPair(val secret: ByteArray) {
    init {
        require(secret.size == 32 && Secp256k1.secKeyVerify(secret)) { "invalid secret key" }
    }

    val publicKey: ByteArray by lazy { xOnly(secret) }
    val publicHex: String by lazy { publicKey.toHex() }

    fun sign(message32: ByteArray): ByteArray = Keys.sign(secret, message32)

    companion object {
        fun generate(): KeyPair {
            while (true) {
                val candidate = PlatformCrypto.randomBytes(32)
                if (Secp256k1.secKeyVerify(candidate)) return KeyPair(candidate)
            }
        }

        /** Deterministic key from seed material: hashes until it lands on a valid scalar. */
        fun fromSeed(seed: ByteArray): KeyPair {
            var candidate = PlatformCrypto.sha256(seed)
            while (!Secp256k1.secKeyVerify(candidate)) candidate = PlatformCrypto.sha256(candidate)
            return KeyPair(candidate)
        }

        fun xOnly(secret: ByteArray): ByteArray {
            val full = Secp256k1.pubkeyCreate(secret)
            return Secp256k1.pubKeyCompress(full).copyOfRange(1, 33)
        }
    }
}

object Keys {
    fun sign(secret: ByteArray, message32: ByteArray): ByteArray {
        require(message32.size == 32)
        return Secp256k1.signSchnorr(message32, secret, PlatformCrypto.randomBytes(32))
    }

    fun verify(signature: ByteArray, message32: ByteArray, xOnlyPub: ByteArray): Boolean = try {
        signature.size == 64 && xOnlyPub.size == 32 && Secp256k1.verifySchnorr(signature, message32, xOnlyPub)
    } catch (_: Throwable) {
        false
    }

    /** ECDH returning the unhashed shared x coordinate, as NIP-44 requires. */
    fun sharedX(secret: ByteArray, theirXOnly: ByteArray): ByteArray {
        val compressed = byteArrayOf(0x02) + theirXOnly
        val point = Secp256k1.pubKeyTweakMul(compressed, secret)
        return point.copyOfRange(1, 33)
    }

    fun isValidPublicKey(hex: String): Boolean = try {
        val bytes = hex.hexToBytes()
        bytes.size == 32 && Secp256k1.pubkeyParse(byteArrayOf(0x02) + bytes).isNotEmpty()
    } catch (_: Throwable) {
        false
    }
}
