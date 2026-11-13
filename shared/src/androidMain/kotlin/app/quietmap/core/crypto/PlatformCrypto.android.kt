package app.quietmap.core.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual object PlatformCrypto {
    private val random = SecureRandom()

    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    actual fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, out, 0)
        return out
    }

    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}
