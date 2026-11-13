package app.quietmap.core

import app.quietmap.core.crypto.KeyPair
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.model.Activity
import app.quietmap.core.model.LocationSample
import app.quietmap.core.model.Member
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.model.Point
import app.quietmap.core.nostr.Kinds
import app.quietmap.core.protocol.CircleCrypto
import app.quietmap.core.protocol.DeviceCode
import app.quietmap.core.protocol.DirectMessages
import app.quietmap.core.protocol.Envelopes
import app.quietmap.core.protocol.InviteData
import app.quietmap.core.protocol.InviteLinks
import app.quietmap.core.protocol.JoinRequestBody
import app.quietmap.core.protocol.LocationBody
import app.quietmap.core.protocol.ProfileBody
import app.quietmap.core.protocol.RosterBody
import app.quietmap.core.protocol.RosterCircle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private val now = 1_763_000_000L

    private fun sampleLocation(points: Int = 6) = MemberLocation(
        lat = 41.881900, lon = -87.627800, accuracy = 8f, speed = 18.8f, heading = 270f,
        activity = Activity.DRIVING, battery = 64, time = now,
        points = List(points) { Point(41.8819 + it * 0.0001, -87.6278 - it * 0.0001, now - 50 + it * 10) },
    )

    @Test
    fun envelopeRejectsForgedSender() {
        val mom = KeyPair.generate()
        val mallory = KeyPair.generate()
        val body = JoinRequestBody(from = mom.publicHex, ts = now, circle = "c", secret = "s".repeat(32), name = "Mom")
        // Mallory signs a body that claims to come from Mom.
        val forged = Envelopes.seal(mallory, body)
        assertNull(Envelopes.open(forged))
        assertIs<JoinRequestBody>(Envelopes.open(Envelopes.seal(mom, body)))
    }

    @Test
    fun directMessageOnlyOpensForRecipient() {
        val jordan = KeyPair.generate()
        val mom = KeyPair.generate()
        val other = KeyPair.generate()
        val roster = RosterBody(jordan.publicHex, now, RosterCircle("cid", "Family", CircleCrypto.newKey(), 1, jordan.publicHex, listOf(Member(jordan.publicHex, "Jordan"))))
        val event = DirectMessages.seal(jordan, mom.publicHex, roster, now)
        assertEquals(Kinds.SEALED_DIRECT, event.kind)
        assertNotEquals(jordan.publicHex, event.pubkey, "outer event must not reveal the sender")
        assertTrue(event.isValid())
        assertEquals(roster, DirectMessages.open(mom, event))
        assertNull(DirectMessages.open(other, event))
    }

    @Test
    fun circleUpdatesRoundTripAndHideTheSender() {
        val mom = KeyPair.generate()
        val key = CircleCrypto.newKey().hexToBytes()
        val body = LocationBody(mom.publicHex, now, "cid", 3, sampleLocation())
        val event = CircleCrypto.seal(mom, key, 3, body, now)
        assertEquals(Kinds.CIRCLE_UPDATE, event.kind)
        assertNotEquals(mom.publicHex, event.pubkey)
        assertEquals(CircleCrypto.tag(key, 3, CircleCrypto.slot(now)), event.tag("d"))
        assertEquals(body, CircleCrypto.open(key, 3, event))
        assertNull(CircleCrypto.open(key, 4, event), "a different epoch must not decrypt")
        assertNull(CircleCrypto.open(CircleCrypto.newKey().hexToBytes(), 3, event), "a removed member's old key must not decrypt")
    }

    @Test
    fun locationUpdatesAreAllTheSameSize() {
        val mom = KeyPair.generate()
        val key = CircleCrypto.newKey().hexToBytes()
        val sizes = listOf(0, 1, 6).map { n ->
            CircleCrypto.seal(mom, key, 1, LocationBody(mom.publicHex, now, "cid", 1, sampleLocation(n)), now).content.length
        }
        assertEquals(1, sizes.toSet().size, "sizes were $sizes")
        assertEquals(Engine.MAX_LOCATION_CONTENT, sizes.first())
    }

    @Test
    fun publisherKeyAndTagRotateHourly() {
        val key = CircleCrypto.newKey().hexToBytes()
        val member = KeyPair.generate().publicHex
        val s = CircleCrypto.slot(now)
        assertEquals(CircleCrypto.publisher(key, member, s).publicHex, CircleCrypto.publisher(key, member, s).publicHex)
        assertNotEquals(CircleCrypto.publisher(key, member, s).publicHex, CircleCrypto.publisher(key, member, s + 1).publicHex)
        assertNotEquals(CircleCrypto.tag(key, 1, s), CircleCrypto.tag(key, 1, s + 1))
        assertNotEquals(CircleCrypto.tag(key, 1, s), CircleCrypto.tag(key, 2, s))
    }

    @Test
    fun inviteLinksRoundTrip() {
        val jordan = KeyPair.generate()
        val data = InviteData(k = jordan.publicHex, n = "Jordan", r = listOf("wss://nos.lol"), c = "cid", cn = "Family", s = "a".repeat(32), e = now + 3600)
        val link = InviteLinks.encode(data)
        assertTrue(link.startsWith("quietmap://join#"))
        assertEquals(data, InviteLinks.decode(link))
        assertEquals(data, InviteLinks.decode("Join my circle: $link"))
        assertNull(InviteLinks.decode("quietmap://join#garbage"))
    }

    @Test
    fun deviceCodeFormat() {
        val code = DeviceCode.full(KeyPair.generate().publicHex)
        assertTrue(Regex("^[0-9A-F]{4}( [0-9A-F]{4}){3}$").matches(code), code)
        assertEquals(9, DeviceCode.short(KeyPair.generate().publicHex).length)
    }

    @Test
    fun profilesRoundTripWithAStablePublisherPerEpoch() {
        val mom = KeyPair.generate()
        val key = CircleCrypto.newKey().hexToBytes()
        val body = ProfileBody(mom.publicHex, now, "Mom", color = 4, photo = "AAAA")
        val event = CircleCrypto.sealProfile(mom, key, 2, body, now)
        assertEquals(CircleCrypto.profileTag(key, 2), event.tag("d"))
        assertEquals(CircleCrypto.profilePublisher(key, mom.publicHex, 2).publicHex, event.pubkey)
        assertEquals(event.pubkey, CircleCrypto.sealProfile(mom, key, 2, body, now + 7200).pubkey, "same key all epoch, so relays replace it")
        assertNotEquals(event.pubkey, CircleCrypto.sealProfile(mom, key, 3, body, now).pubkey)
        assertEquals(body, CircleCrypto.open(key, 2, event))
        assertNotEquals(CircleCrypto.profileTag(key, 2), CircleCrypto.tag(key, 2, CircleCrypto.slot(now)))
    }

    private fun sample(t: Long, lat: Double, speed: Float?, acc: Float = 5f) = LocationSample(lat, -87.0, acc, speed, null, t)

    @Test
    fun motionPolicyClassifiesWithHysteresis() {
        assertEquals(Activity.STILL, MotionPolicy.classify(0.2f, Activity.UNKNOWN))
        assertEquals(Activity.WALKING, MotionPolicy.classify(1.4f, Activity.STILL))
        assertEquals(Activity.WALKING, MotionPolicy.classify(2.6f, Activity.WALKING), "just over the boundary keeps walking")
        assertEquals(Activity.CYCLING, MotionPolicy.classify(4f, Activity.WALKING))
        assertEquals(Activity.DRIVING, MotionPolicy.classify(6.5f, Activity.DRIVING), "slowing in traffic stays driving")
        assertEquals(Activity.DRIVING, MotionPolicy.classify(18f, Activity.STILL))
    }

    @Test
    fun motionPolicyPublishesPromptlyWhenMovementStartsAndStops() {
        val p = MotionPolicy()
        var t = now
        p.add(sample(t, 41.0, 0f))
        assertTrue(p.shouldPublish(t), "first fix publishes right away")
        p.markPublished(t)
        assertFalse(p.isMoving(t))

        // 30 s later the phone starts driving: publish without waiting out the 5-minute still interval.
        t += 30
        p.add(sample(t, 41.001, 15f))
        p.add(sample(t + 5, 41.002, 16f))
        t += 5
        assertEquals(Activity.DRIVING, p.activity())
        assertTrue(p.shouldPublish(t))
        p.markPublished(t)
        assertFalse(p.shouldPublish(t + 10), "no flood right after publishing")
        assertTrue(p.shouldPublish(t + 70), "about once a minute while moving")

        // Parks: the stop is reported promptly instead of showing "Driving" for minutes.
        p.markPublished(t + 70)
        t += 100
        p.add(sample(t, 41.003, 0f))
        p.add(sample(t + 5, 41.003, 0f))
        t += 5
        assertEquals(Activity.STILL, p.activity())
        assertTrue(p.shouldPublish(t))
    }

    @Test
    fun motionHintSwitchesToGpsBeforeAnyFixMoves() {
        val p = MotionPolicy()
        p.add(sample(now, 41.0, 0f))
        p.markPublished(now)
        assertFalse(p.wantsHighAccuracy(now + 10))
        p.motionHint(now + 10)
        assertTrue(p.wantsHighAccuracy(now + 11))
    }

    @Test
    fun coarseFixJitterIsNotMovement() {
        val p = MotionPolicy()
        // Network fixes wobbling 40 m with 60 m accuracy: still, not walking.
        p.add(sample(now, 41.0, null, acc = 60f))
        p.add(sample(now + 60, 41.00036, null, acc = 60f))
        assertEquals(0f, p.estimateSpeed())
        assertFalse(p.isMoving(now + 60))
    }
}
