package app.quietmap.core.nostr

import app.quietmap.core.crypto.KeyPair
import app.quietmap.core.crypto.Keys
import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.crypto.toHex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** A NIP-01 event. */
@Serializable
data class Event(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun isValid(): Boolean = try {
        val expectedId = computeId(pubkey, createdAt, kind, tags, content)
        expectedId == id && Keys.verify(sig.hexToBytes(), id.hexToBytes(), pubkey.hexToBytes())
    } catch (_: Throwable) {
        false
    }

    companion object {
        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val array = buildJsonArray {
                add(JsonPrimitive(0))
                add(JsonPrimitive(pubkey))
                add(JsonPrimitive(createdAt))
                add(JsonPrimitive(kind))
                add(JsonArray(tags.map { t -> JsonArray(t.map { JsonPrimitive(it) }) }))
                add(JsonPrimitive(content))
            }
            val serialized = Json.encodeToString(JsonArray.serializer(), array)
            return PlatformCrypto.sha256(serialized.encodeToByteArray()).toHex()
        }

        fun create(keys: KeyPair, kind: Int, tags: List<List<String>>, content: String, createdAt: Long): Event {
            val id = computeId(keys.publicHex, createdAt, kind, tags, content)
            val sig = keys.sign(id.hexToBytes()).toHex()
            return Event(id, keys.publicHex, createdAt, kind, tags, content, sig)
        }
    }
}

object Kinds {
    /** Sealed one-to-one message (gift-wrap style): signed by a throwaway key, tagged with the recipient. */
    const val SEALED_DIRECT = 1059

    /** NIP-78 addressable app data; relays keep only the newest per (pubkey, d tag). */
    const val CIRCLE_UPDATE = 30078
}
