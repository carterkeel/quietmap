package app.quietmap.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.stream.Stream
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * iOS primitives: SHA-256 and HMAC from Apple's CommonCrypto, randomness from the Security
 * framework, and the ChaCha20 stream cipher from libsodium (Apple only ships ChaCha20 as part
 * of the ChaChaPoly AEAD, which NIP-44 doesn't use).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
actual object PlatformCrypto {
    actual fun sha256(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        data.usePinned { input ->
            out.usePinned { digest ->
                CC_SHA256(input.addressOfOrNull(), data.size.convert(), digest.addressOf(0).reinterpret<UByteVar>())
            }
        }
        return out
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        key.usePinned { k ->
            data.usePinned { d ->
                out.usePinned { o ->
                    CCHmac(kCCHmacAlgSHA256, k.addressOfOrNull(), key.size.convert(), d.addressOfOrNull(), data.size.convert(), o.addressOf(0))
                }
            }
        }
        return out
    }

    actual fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        ensureSodium()
        // crypto_stream_chacha20_ietf_xor: RFC 8439 ChaCha20, block counter starting at 0.
        return Stream.chacha20IetfXor(data.asUByteArray(), nonce.asUByteArray(), key.asUByteArray()).asByteArray()
    }

    actual fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        if (size == 0) return out
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
        return out
    }

    private fun ensureSodium() {
        if (LibsodiumInitializer.isInitialized()) return
        // On native targets this runs sodium_init() synchronously before calling back.
        LibsodiumInitializer.initializeWithCallback {}
        check(LibsodiumInitializer.isInitialized()) { "libsodium failed to initialize" }
    }
}

/** CommonCrypto rejects nothing for empty input, but addressOf(0) on an empty array throws. */
@OptIn(ExperimentalForeignApi::class)
private fun kotlinx.cinterop.Pinned<ByteArray>.addressOfOrNull() =
    if (get().isEmpty()) null else addressOf(0)

