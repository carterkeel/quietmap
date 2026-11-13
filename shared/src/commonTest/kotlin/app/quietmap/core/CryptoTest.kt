package app.quietmap.core

import app.quietmap.core.crypto.KeyPair
import app.quietmap.core.crypto.Keys
import app.quietmap.core.crypto.Nip44
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.crypto.toHex
import app.quietmap.core.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoTest {
    @Test
    fun paddedLengthsMatchNip44Spec() {
        val vectors = mapOf(
            16 to 32, 32 to 32, 33 to 64, 37 to 64, 45 to 64, 49 to 64, 64 to 64, 65 to 96, 100 to 128,
            111 to 128, 200 to 224, 250 to 256, 320 to 320, 383 to 384, 384 to 384, 400 to 448, 500 to 512,
            512 to 512, 515 to 640, 700 to 768, 800 to 896, 900 to 1024, 1020 to 1024, 65536 to 65536,
        )
        vectors.forEach { (len, padded) -> assertEquals(padded, Nip44.calcPaddedLen(len), "len=$len") }
    }

    @Test
    fun nip44SpecVector() {
        val sec1 = "0000000000000000000000000000000000000000000000000000000000000001".hexToBytes()
        val sec2 = "0000000000000000000000000000000000000000000000000000000000000002".hexToBytes()
        val conv = Nip44.conversationKey(sec1, KeyPair.xOnly(sec2))
        assertEquals("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d", conv.toHex())
        val nonce = "0000000000000000000000000000000000000000000000000000000000000001".hexToBytes()
        val payload = Nip44.encrypt("a", conv, nonce)
        assertEquals(
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb",
            payload,
        )
        assertEquals("a", Nip44.decrypt(payload, conv))
    }

    @Test
    fun nip44RoundTripBothDirections() {
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val ab = Nip44.conversationKey(alice.secret, bob.publicKey)
        val ba = Nip44.conversationKey(bob.secret, alice.publicKey)
        assertEquals(ab.toHex(), ba.toHex())
        val msg = "where is everyone? 🗺️"
        assertEquals(msg, Nip44.decrypt(Nip44.encrypt(msg, ab), ba))
    }

    @Test
    fun nip44RejectsTamperedPayload() {
        val k = KeyPair.generate()
        val conv = Nip44.conversationKey(k.secret, KeyPair.generate().publicKey)
        val payload = Nip44.encrypt("hello", conv)
        val tampered = payload.substring(0, 60) + (if (payload[60] == 'A') 'B' else 'A') + payload.substring(61)
        assertFailsWith<IllegalArgumentException> { Nip44.decrypt(tampered, conv) }
    }

    @Test
    fun eventsSignAndVerify() {
        val keys = KeyPair.generate()
        val e = Event.create(keys, 1, listOf(listOf("p", keys.publicHex)), "hi \"there\"\n", 1_700_000_000)
        assertTrue(e.isValid())
        assertFalse(e.copy(content = "changed").isValid())
        assertFalse(e.copy(createdAt = e.createdAt + 1).isValid())
    }

    @Test
    fun schnorrRejectsWrongKey() {
        val a = KeyPair.generate()
        val b = KeyPair.generate()
        val msg = ByteArray(32) { it.toByte() }
        assertTrue(Keys.verify(a.sign(msg), msg, a.publicKey))
        assertFalse(Keys.verify(a.sign(msg), msg, b.publicKey))
    }
}
