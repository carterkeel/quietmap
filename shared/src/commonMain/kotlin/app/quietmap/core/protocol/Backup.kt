package app.quietmap.core.protocol

import app.quietmap.core.crypto.Nip44
import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.crypto.fromBase64Url
import app.quietmap.core.crypto.toBase64Url
import app.quietmap.core.model.AppState
import app.quietmap.core.model.Circle
import app.quietmap.core.model.Contact
import app.quietmap.core.model.PendingJoin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a backup carries: your identity key, profile, settings, circles (with their keys) and the
 * names and verifications you set for people. Other people's locations and photos aren't included;
 * they arrive again from the relays.
 */
@Serializable
data class BackupData(
    val v: Int = 1,
    val identitySecretHex: String,
    val myName: String,
    val myColor: Int? = null,
    val myPhoto: String? = null,
    val relays: List<String>,
    val shareBattery: Boolean = true,
    val showPhotos: Boolean = true,
    val circles: List<Circle>,
    val contacts: Map<String, Contact> = emptyMap(),
    val pendingJoins: List<PendingJoin> = emptyList(),
    val lastCircleId: String? = null,
)

/**
 * Passphrase-protected backups for moving to a new phone.
 *
 * The passphrase goes through PBKDF2-HMAC-SHA256 (600,000 iterations, random 16-byte salt) to a
 * 32-byte key, which encrypts the backup with NIP-44 v2: the same audited construction the app
 * uses for messages. File format:
 *
 * ```
 * quietmap-backup-v1
 * <salt, base64url>
 * <NIP-44 payload>
 * ```
 */
object Backups {
    const val HEADER = "quietmap-backup-v1"
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 8
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun fromState(s: AppState): BackupData = BackupData(
        identitySecretHex = s.identitySecretHex,
        myName = s.myName,
        myColor = s.myColor,
        myPhoto = s.myPhoto,
        relays = s.relays,
        shareBattery = s.shareBattery,
        showPhotos = s.showPhotos,
        circles = s.circles,
        contacts = s.contacts,
        pendingJoins = s.pendingJoins,
        lastCircleId = s.lastCircleId,
    )

    /** Fresh app state from a backup. Sharing starts paused so nothing goes out before you check. */
    fun toState(b: BackupData): AppState = AppState(
        identitySecretHex = b.identitySecretHex,
        myName = b.myName,
        myColor = b.myColor,
        myPhoto = b.myPhoto,
        relays = b.relays,
        sharing = false,
        shareBattery = b.shareBattery,
        showPhotos = b.showPhotos,
        circles = b.circles,
        contacts = b.contacts,
        pendingJoins = b.pendingJoins,
        lastCircleId = b.lastCircleId,
        setupDone = true,
    )

    fun encrypt(data: BackupData, passphrase: String): String {
        require(passphrase.length >= MIN_PASSPHRASE) { "Use a passphrase of at least $MIN_PASSPHRASE characters" }
        var plain = json.encodeToString(BackupData.serializer(), data)
        // NIP-44 carries up to 64 KB; a large photo is the only thing that could push past it.
        if (plain.encodeToByteArray().size > 65_000) plain = json.encodeToString(BackupData.serializer(), data.copy(myPhoto = null))
        val salt = PlatformCrypto.randomBytes(16)
        val key = PlatformCrypto.pbkdf2Sha256(passphrase, salt, ITERATIONS, 32)
        return "$HEADER\n${salt.toBase64Url()}\n${Nip44.encrypt(plain, key)}\n"
    }

    /** Throws [IllegalArgumentException] with a readable message if the file or passphrase is wrong. */
    fun decrypt(text: String, passphrase: String): BackupData {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.size == 3 && lines[0] == HEADER) { "That isn't a Quietmap backup file" }
        val salt = try { lines[1].fromBase64Url() } catch (_: Throwable) { throw IllegalArgumentException("The backup file is damaged") }
        val key = PlatformCrypto.pbkdf2Sha256(passphrase, salt, ITERATIONS, 32)
        val plain = try { Nip44.decrypt(lines[2], key) } catch (_: Throwable) { throw IllegalArgumentException("Wrong passphrase, or the backup file is damaged") }
        return json.decodeFromString(BackupData.serializer(), plain)
    }
}
