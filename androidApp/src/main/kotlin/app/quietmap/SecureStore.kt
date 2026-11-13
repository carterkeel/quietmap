package app.quietmap

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.quietmap.core.StateStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the app state (including the identity key) encrypted with AES-256-GCM.
 * The AES key lives in the Android Keystore, in secure hardware where the phone has it,
 * and can't be exported. The file is excluded from backups in the manifest.
 */
class SecureStore(context: Context) : StateStore {
    private val file = File(context.noBackupFilesDir, "state.bin")

    override fun load(): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
        } catch (t: Throwable) {
            // Keystore key lost (e.g. device restored); start fresh rather than crash.
            null
        }
    }

    @Synchronized
    override fun save(data: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(data.encodeToByteArray())
        val tmp = File(file.parentFile, "state.bin.tmp")
        tmp.writeBytes(out)
        tmp.renameTo(file)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "quietmap-state"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
