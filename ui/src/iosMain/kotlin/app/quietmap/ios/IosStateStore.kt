package app.quietmap.ios

import app.quietmap.core.StateStore
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.secretbox.SecretBox
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * Stores the app state (including the identity key) the way Android's SecureStore does: the file
 * is encrypted (libsodium secretbox, XSalsa20-Poly1305) with a random 256-bit key that lives in
 * the Keychain, readable after the first unlock and never synced or restored to another device.
 * The file itself uses file protection and is excluded from iCloud and computer backups.
 *
 * Before the first unlock after a reboot, iOS keeps both the key and the file locked. Then
 * [load] throws instead of returning null, so the engine never mistakes "locked" for "no
 * state yet" and replaces the identity. [QuietmapIos.start] waits for unlock before loading.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class, BetaInteropApi::class)
class IosStateStore internal constructor(private val keys: KeyStore) : StateStore {
    constructor() : this(Keychain)

    private val lock = NSLock()
    private val dir: NSURL = run {
        val fm = NSFileManager.defaultManager
        val support = fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL
        val dir = support.URLByAppendingPathComponent("Quietmap", isDirectory = true)!!
        fm.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        dir.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        dir
    }
    private val file: NSURL = dir.URLByAppendingPathComponent("state.bin")!!

    override fun load(): String? {
        ensureSodium()
        if (!NSFileManager.defaultManager.fileExistsAtPath(file.path!!)) return null
        val bytes = NSData.dataWithContentsOfURL(file)?.toByteArray()
            ?: throw IllegalStateException("Saved state is locked until the phone is unlocked")
        val key = when (val k = keys.read(KEY_ACCOUNT)) {
            is KeyStore.Result.Found -> k.data
            // Key gone (e.g. app data moved to a new phone): start fresh rather than crash.
            KeyStore.Result.Missing -> return null
            is KeyStore.Result.Error -> throw IllegalStateException("Keychain unavailable (status ${k.status})")
        }
        if (bytes.size <= NONCE_BYTES) return null
        return try {
            SecretBox.openEasy(
                bytes.copyOfRange(NONCE_BYTES, bytes.size).asUByteArray(),
                bytes.copyOfRange(0, NONCE_BYTES).asUByteArray(),
                key.asUByteArray(),
            ).asByteArray().decodeToString()
        } catch (_: Throwable) {
            null
        }
    }

    override fun save(data: String) {
        ensureSodium()
        lock.lock()
        try {
            val nonce = LibsodiumRandom.buf(NONCE_BYTES)
            val sealed = SecretBox.easy(data.encodeToByteArray().asUByteArray(), nonce, key().asUByteArray())
            val ok = (nonce.asByteArray() + sealed.asByteArray()).toNSData().writeToURL(
                file,
                options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication,
                error = null,
            )
            check(ok) { "Could not write saved state" }
        } finally {
            lock.unlock()
        }
    }

    /** The file key, created on first save. */
    private fun key(): ByteArray = when (val k = keys.read(KEY_ACCOUNT)) {
        is KeyStore.Result.Found -> k.data
        KeyStore.Result.Missing -> SecretBox.keygen().asByteArray().also { keys.add(KEY_ACCOUNT, it) }
        is KeyStore.Result.Error -> throw IllegalStateException("Keychain unavailable (status ${k.status})")
    }

    private companion object {
        const val KEY_ACCOUNT = "state-key"
        const val NONCE_BYTES = 24 // crypto_secretbox_NONCEBYTES

        fun ensureSodium() {
            if (LibsodiumInitializer.isInitialized()) return
            LibsodiumInitializer.initializeWithCallback {}
            check(LibsodiumInitializer.isInitialized()) { "libsodium failed to initialize" }
        }
    }
}

/** Where the file key lives. The Keychain in the app; an in-memory fake in tests (test binaries get no Keychain). */
internal interface KeyStore {
    sealed interface Result {
        class Found(val data: ByteArray) : Result
        data object Missing : Result
        class Error(val status: Int) : Result
    }

    fun read(account: String): Result
    fun add(account: String, data: ByteArray)
}

/** Minimal Keychain access for one generic-password item per account. */
@OptIn(ExperimentalForeignApi::class)
private object Keychain : KeyStore {
    private const val SERVICE = "app.quietmap"

    override fun read(account: String): KeyStore.Result = memScoped {
        val query = baseQuery(account)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, out.ptr)
        CFRelease(query)
        when (status) {
            errSecSuccess -> KeyStore.Result.Found((CFBridgingRelease(out.value) as NSData).toByteArray())
            errSecItemNotFound -> KeyStore.Result.Missing
            else -> KeyStore.Result.Error(status)
        }
    }

    override fun add(account: String, data: ByteArray) {
        val query = baseQuery(account)
        val value: CFTypeRef? = CFBridgingRetain(data.toNSData())
        CFDictionaryAddValue(query, kSecValueData, value)
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(query, null)
        CFRelease(value)
        CFRelease(query)
        check(status == errSecSuccess) { "Could not save key to the Keychain (status $status)" }
    }

    private fun baseQuery(account: String): CFMutableDictionaryRef {
        val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        // The dictionary retains these; release our own references.
        val service = CFBridgingRetain(SERVICE)
        val acct = CFBridgingRetain(account)
        CFDictionaryAddValue(query, kSecAttrService, service)
        CFDictionaryAddValue(query, kSecAttrAccount, acct)
        CFRelease(service)
        CFRelease(acct)
        return query
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }
