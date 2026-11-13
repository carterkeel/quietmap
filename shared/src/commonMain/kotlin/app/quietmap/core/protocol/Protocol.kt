package app.quietmap.core.protocol

import app.quietmap.core.crypto.KeyPair
import app.quietmap.core.crypto.Keys
import app.quietmap.core.crypto.Nip44
import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.crypto.fromBase64Url
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.crypto.toBase64Url
import app.quietmap.core.crypto.toHex
import app.quietmap.core.model.Member
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.nostr.Event
import app.quietmap.core.nostr.Kinds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val ProtocolJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "t"
    encodeDefaults = false
}

// ---------------------------------------------------------------------------------------------
// Message bodies. Every body is signed by the sender's identity key inside the encryption, so
// recipients know who really sent it even though the outer Nostr event uses a throwaway key.
// ---------------------------------------------------------------------------------------------

@Serializable
sealed class Body {
    abstract val from: String
    abstract val ts: Long
}

@Serializable
@SerialName("loc")
data class LocationBody(override val from: String, override val ts: Long, val circle: String, val epoch: Int, val loc: MemberLocation) : Body()

@Serializable
@SerialName("join_req")
data class JoinRequestBody(override val from: String, override val ts: Long, val circle: String, val secret: String, val name: String) : Body()

@Serializable
data class RosterCircle(val id: String, val name: String, val key: String, val epoch: Int, val admin: String, val members: List<Member>)

@Serializable
@SerialName("roster")
data class RosterBody(override val from: String, override val ts: Long, val circle: RosterCircle) : Body()

@Serializable
@SerialName("removed")
data class RemovedBody(override val from: String, override val ts: Long, val circle: String) : Body()

@Serializable
private data class Envelope(val b: String, val s: String, val p: String? = null)

object Envelopes {
    /** Location envelopes are padded to this many characters so every update looks the same size. */
    const val LOCATION_SIZE = 1400

    fun seal(identity: KeyPair, body: Body, padTo: Int? = null): String {
        val bodyJson = ProtocolJson.encodeToString(Body.serializer(), body)
        val sig = identity.sign(PlatformCrypto.sha256(bodyJson.encodeToByteArray())).toHex()
        val bare = ProtocolJson.encodeToString(Envelope.serializer(), Envelope(bodyJson, sig))
        if (padTo == null) return bare
        val overhead = ProtocolJson.encodeToString(Envelope.serializer(), Envelope(bodyJson, sig, "")).length
        val padLen = (padTo - overhead).coerceAtLeast(0)
        return ProtocolJson.encodeToString(Envelope.serializer(), Envelope(bodyJson, sig, "0".repeat(padLen)))
    }

    /** Returns the body only if its signature matches the identity it claims to come from. */
    fun open(text: String): Body? = try {
        val env = ProtocolJson.decodeFromString(Envelope.serializer(), text)
        val body = ProtocolJson.decodeFromString(Body.serializer(), env.b)
        val ok = Keys.verify(env.s.hexToBytes(), PlatformCrypto.sha256(env.b.encodeToByteArray()), body.from.hexToBytes())
        if (ok) body else null
    } catch (_: Throwable) {
        null
    }
}

// ---------------------------------------------------------------------------------------------
// Sealed direct messages: invites, approvals, roster updates.
// ---------------------------------------------------------------------------------------------

object DirectMessages {
    private const val EXPIRY_SECONDS = 7 * 24 * 3600L

    fun seal(identity: KeyPair, recipientPubkey: String, body: Body, now: Long): Event {
        val throwaway = KeyPair.generate()
        val conv = Nip44.conversationKey(throwaway.secret, recipientPubkey.hexToBytes())
        val content = Nip44.encrypt(Envelopes.seal(identity, body), conv)
        val tags = listOf(listOf("p", recipientPubkey), listOf("expiration", (now + EXPIRY_SECONDS).toString()))
        // Blur the timestamp slightly so relays can't line it up exactly with other activity.
        val jitter = (PlatformCrypto.randomBytes(2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) } % 900)
        return Event.create(throwaway, Kinds.SEALED_DIRECT, tags, content, now - jitter)
    }

    fun open(identity: KeyPair, event: Event): Body? = try {
        if (event.kind != Kinds.SEALED_DIRECT || event.tag("p") != identity.publicHex) null
        else {
            val conv = Nip44.conversationKey(identity.secret, event.pubkey.hexToBytes())
            Envelopes.open(Nip44.decrypt(event.content, conv))
        }
    } catch (_: Throwable) {
        null
    }
}

// ---------------------------------------------------------------------------------------------
// Circle updates. One event per member per hour slot, replaced in place on the relays.
// The signing key and the d-tag both change every hour, so relays can't link a member's
// updates across hours.
// ---------------------------------------------------------------------------------------------

object CircleCrypto {
    private const val SLOT_SECONDS = 3600L
    private const val EXPIRY_SECONDS = 2 * 3600L

    fun slot(now: Long): Long = now / SLOT_SECONDS

    fun tag(circleKey: ByteArray, epoch: Int, slot: Long): String =
        PlatformCrypto.hmacSha256(circleKey, "qm-tag|$epoch|$slot".encodeToByteArray()).toHex().take(32)

    fun publisher(circleKey: ByteArray, memberPubkey: String, slot: Long): KeyPair =
        KeyPair.fromSeed(PlatformCrypto.hmacSha256(circleKey, "qm-pub|$memberPubkey|$slot".encodeToByteArray()))

    private fun conversationKey(circleKey: ByteArray, epoch: Int): ByteArray =
        Nip44.conversationKeyFromSecret(circleKey, "quietmap-circle|$epoch")

    fun seal(identity: KeyPair, circleKey: ByteArray, epoch: Int, body: Body, now: Long, padTo: Int? = Envelopes.LOCATION_SIZE): Event {
        val s = slot(now)
        val tags = listOf(listOf("d", tag(circleKey, epoch, s)), listOf("expiration", (now + EXPIRY_SECONDS).toString()))
        val content = Nip44.encrypt(Envelopes.seal(identity, body, padTo), conversationKey(circleKey, epoch))
        return Event.create(publisher(circleKey, identity.publicHex, s), Kinds.CIRCLE_UPDATE, tags, content, now)
    }

    fun open(circleKey: ByteArray, epoch: Int, event: Event): Body? = try {
        Envelopes.open(Nip44.decrypt(event.content, conversationKey(circleKey, epoch)))
    } catch (_: Throwable) {
        null
    }

    fun newKey(): String = PlatformCrypto.randomBytes(32).toHex()
    fun newId(): String = PlatformCrypto.randomBytes(16).toHex()
}

// ---------------------------------------------------------------------------------------------
// Device codes and invite links.
// ---------------------------------------------------------------------------------------------

object DeviceCode {
    /** "7F3A 91C2 D04E 6B1A": a short fingerprint of a public key, for comparing in person. */
    fun full(pubkeyHex: String): String =
        PlatformCrypto.sha256(pubkeyHex.hexToBytes()).copyOfRange(0, 8).toHex().uppercase().chunked(4).joinToString(" ")

    fun short(pubkeyHex: String): String = full(pubkeyHex).split(" ").take(2).joinToString("·")
}

@Serializable
data class InviteData(
    val v: Int = 1,
    /** inviter public key */
    val k: String,
    /** inviter name */
    val n: String,
    /** relays */
    val r: List<String>,
    /** circle id and name */
    val c: String,
    val cn: String,
    /** one-time secret */
    val s: String,
    /** expiry, unix seconds */
    val e: Long,
)

object InviteLinks {
    const val SCHEME_PREFIX = "quietmap://join#"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(data: InviteData): String =
        SCHEME_PREFIX + json.encodeToString(InviteData.serializer(), data).encodeToByteArray().toBase64Url()

    /** Accepts the full link or anything containing "#<payload>". Returns null if it isn't a valid invite. */
    fun decode(link: String): InviteData? = try {
        val payload = link.trim().substringAfter('#', missingDelimiterValue = link.trim())
        val data = json.decodeFromString(InviteData.serializer(), payload.fromBase64Url().decodeToString())
        if (data.v == 1 && Keys.isValidPublicKey(data.k) && data.s.length >= 16) data else null
    } catch (_: Throwable) {
        null
    }
}
