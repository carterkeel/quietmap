package app.quietmap.core.model

import kotlinx.serialization.Serializable

val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.primal.net",
    "wss://nostr.mom",
)

/** Everything the app persists. Stored encrypted on the device. */
@Serializable
data class AppState(
    val identitySecretHex: String,
    val myName: String = "",
    val relays: List<String> = DEFAULT_RELAYS,
    val sharing: Boolean = true,
    /** Include battery level in location updates. */
    val shareBattery: Boolean = true,
    /** Avatar color index for the default initial avatar; null means derived from the public key. */
    val myColor: Int? = null,
    /** Profile photo as a small JPEG, base64. Shared only with circle members, encrypted. */
    val myPhoto: String? = null,
    /** When the profile (name, color, photo) last changed or was last published, unix seconds. */
    val profileChangedAt: Long = 0,
    val profilePublishedAt: Long = 0,
    /** Profiles other members published to their circles. */
    val profiles: Map<String, MemberProfile> = emptyMap(),
    val circles: List<Circle> = emptyList(),
    val contacts: Map<String, Contact> = emptyMap(),
    val invites: List<Invite> = emptyList(),
    val joinRequests: List<JoinRequest> = emptyList(),
    val pendingJoins: List<PendingJoin> = emptyList(),
    /** circleId -> member pubkey -> latest location */
    val locations: Map<String, Map<String, MemberLocation>> = emptyMap(),
    val seenEventIds: List<String> = emptyList(),
)

@Serializable
data class Member(val pubkey: String, val name: String)

/** What a member chose to show the circle: their name, avatar color and optional photo. */
@Serializable
data class MemberProfile(
    val name: String,
    val color: Int? = null,
    /** Small JPEG, base64. */
    val photo: String? = null,
    val updated: Long,
)

@Serializable
data class Circle(
    val id: String,
    val name: String,
    val keyHex: String,
    val epoch: Int,
    val adminPubkey: String,
    val members: List<Member>,
    /** Previous key, accepted for a short overlap after a rotation. */
    val previousKeyHex: String? = null,
    val previousEpoch: Int? = null,
    val previousValidUntil: Long? = null,
)

/** Local-only knowledge about another person. */
@Serializable
data class Contact(
    val label: String? = null,
    val verified: Boolean = false,
)

/** An invite link this device created (we are the circle admin). */
@Serializable
data class Invite(
    val secret: String,
    val circleId: String,
    val expiresAt: Long,
    val used: Boolean = false,
)

/** Someone opened our invite link and is waiting for approval. */
@Serializable
data class JoinRequest(
    val id: String,
    val circleId: String,
    val pubkey: String,
    val name: String,
    val receivedAt: Long,
)

/** We opened someone's invite link and are waiting for them to approve. */
@Serializable
data class PendingJoin(
    val circleId: String,
    val circleName: String,
    val inviterPubkey: String,
    val inviterName: String,
    val sentAt: Long,
)

@Serializable
data class Point(val lat: Double, val lon: Double, val t: Long)

@Serializable
data class MemberLocation(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float? = null,
    val heading: Float? = null,
    val activity: Activity = Activity.UNKNOWN,
    val battery: Int? = null,
    val time: Long,
    val points: List<Point> = emptyList(),
    val status: String? = null,
)

@Serializable
enum class Activity { STILL, WALKING, CYCLING, DRIVING, UNKNOWN }

/** A location fix from the platform. */
data class LocationSample(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float?,
    val heading: Float?,
    val time: Long,
)
