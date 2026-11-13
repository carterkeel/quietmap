package app.quietmap.core

import app.quietmap.core.crypto.KeyPair
import app.quietmap.core.crypto.PlatformCrypto
import app.quietmap.core.crypto.hexToBytes
import app.quietmap.core.crypto.toHex
import app.quietmap.core.model.AppState
import app.quietmap.core.model.Circle
import app.quietmap.core.model.Contact
import app.quietmap.core.model.Invite
import app.quietmap.core.model.JoinRequest
import app.quietmap.core.model.Member
import app.quietmap.core.model.MemberLocation
import app.quietmap.core.model.PendingJoin
import app.quietmap.core.nostr.Event
import app.quietmap.core.nostr.Filter
import app.quietmap.core.nostr.Kinds
import app.quietmap.core.nostr.RelayPool
import app.quietmap.core.nostr.RelayStatus
import app.quietmap.core.protocol.CircleCrypto
import app.quietmap.core.protocol.DirectMessages
import app.quietmap.core.protocol.InviteData
import app.quietmap.core.protocol.InviteLinks
import app.quietmap.core.protocol.JoinRequestBody
import app.quietmap.core.protocol.LocationBody
import app.quietmap.core.protocol.RemovedBody
import app.quietmap.core.protocol.RosterBody
import app.quietmap.core.protocol.RosterCircle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Persists the app state blob. The Android implementation encrypts it with a Keystore key. */
interface StateStore {
    fun load(): String?
    fun save(data: String)
}

/**
 * The whole Quietmap protocol: identity, circles, invites, key rotation and location exchange.
 * Platform code only supplies storage, a clock, and location fixes.
 */
class Engine(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _state: MutableStateFlow<AppState>
    val state: StateFlow<AppState>
    val identity: KeyPair

    private val pool = RelayPool(scope, ::onEvent, log)
    val relayStatus: StateFlow<Map<String, RelayStatus>> = pool.status

    /** d-tag -> (circle id, key, epoch) for every tag we currently listen on. */
    private var tagIndex: Map<String, Triple<String, ByteArray, Int>> = emptyMap()
    private val seen = LinkedHashSet<String>()

    init {
        val loaded = store.load()?.let { runCatching { json.decodeFromString(AppState.serializer(), it) }.getOrNull() }
        val initial = loaded ?: AppState(identitySecretHex = KeyPair.generate().secret.toHex())
        identity = KeyPair(initial.identitySecretHex.hexToBytes())
        _state = MutableStateFlow(initial)
        state = _state.asStateFlow()
        seen.addAll(initial.seenEventIds)
        if (loaded == null) store.save(json.encodeToString(AppState.serializer(), initial))
    }

    val myPubkey: String get() = identity.publicHex

    fun start() {
        scope.launch {
            pool.setRelays(state.value.relays)
            refreshSubscriptions()
            while (isActive) {
                delay(60_000)
                pruneExpired()
                refreshSubscriptions()
            }
        }
    }

    fun stop() = pool.shutdown()

    // -----------------------------------------------------------------------------------------
    // Profile and settings
    // -----------------------------------------------------------------------------------------

    suspend fun setMyName(name: String) = update { it.copy(myName = name.trim().take(40)) }

    suspend fun setSharing(on: Boolean) = update { it.copy(sharing = on) }

    suspend fun setRelays(relays: List<String>) {
        val clean = relays.map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
        update { it.copy(relays = clean) }
        pool.setRelays(clean)
        refreshSubscriptions()
    }

    suspend fun setLabel(pubkey: String, label: String?) = update {
        val c = it.contacts[pubkey] ?: Contact()
        it.copy(contacts = it.contacts + (pubkey to c.copy(label = label?.trim()?.ifEmpty { null })))
    }

    suspend fun setVerified(pubkey: String, verified: Boolean) = update {
        val c = it.contacts[pubkey] ?: Contact()
        it.copy(contacts = it.contacts + (pubkey to c.copy(verified = verified)))
    }

    // -----------------------------------------------------------------------------------------
    // Circles and invites
    // -----------------------------------------------------------------------------------------

    suspend fun createCircle(name: String): Circle {
        val circle = Circle(
            id = CircleCrypto.newId(),
            name = name.trim().ifEmpty { "Family" },
            keyHex = CircleCrypto.newKey(),
            epoch = 1,
            adminPubkey = myPubkey,
            members = listOf(Member(myPubkey, state.value.myName)),
        )
        update { it.copy(circles = it.circles + circle) }
        refreshSubscriptions()
        return circle
    }

    /** Creates a single-use invite link valid for 24 hours. Only the circle admin can invite. */
    suspend fun createInvite(circleId: String): String {
        val s = state.value
        val circle = s.circles.first { it.id == circleId }
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can invite" }
        val secret = PlatformCrypto.randomBytes(16).toHex()
        val expires = now() + 24 * 3600
        update { it.copy(invites = it.invites + Invite(secret, circleId, expires)) }
        return InviteLinks.encode(InviteData(k = myPubkey, n = s.myName, r = s.relays, c = circleId, cn = circle.name, s = secret, e = expires))
    }

    /** Sends a join request for an invite the user has agreed to. */
    suspend fun acceptInvite(invite: InviteData) {
        require(invite.e > now()) { "This invite has expired" }
        require(invite.k != myPubkey) { "This is your own invite" }
        // Make sure we can reach the inviter's relays too.
        val merged = (state.value.relays + invite.r).distinct().take(8)
        if (merged != state.value.relays) setRelays(merged)
        update {
            it.copy(pendingJoins = it.pendingJoins.filterNot { p -> p.circleId == invite.c } +
                PendingJoin(invite.c, invite.cn, invite.k, invite.n, now()))
        }
        val body = JoinRequestBody(myPubkey, now(), invite.c, invite.s, state.value.myName)
        pool.publish(DirectMessages.seal(identity, invite.k, body, now()))
    }

    suspend fun approve(requestId: String) {
        val req = state.value.joinRequests.firstOrNull { it.id == requestId } ?: return
        val circle = state.value.circles.firstOrNull { it.id == req.circleId } ?: return
        val updated = circle.copy(members = circle.members.filterNot { it.pubkey == req.pubkey } + Member(req.pubkey, req.name))
        update { s ->
            s.copy(
                circles = s.circles.map { if (it.id == circle.id) updated else it },
                joinRequests = s.joinRequests.filterNot { it.id == requestId },
            )
        }
        sendRoster(updated)
        refreshSubscriptions()
    }

    suspend fun deny(requestId: String) = update { s -> s.copy(joinRequests = s.joinRequests.filterNot { it.id == requestId }) }

    /** Removes a member and rotates the circle key so they can't read anything new. */
    suspend fun removeMember(circleId: String, pubkey: String) {
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        require(circle.adminPubkey == myPubkey) { "Only the circle admin can remove people" }
        require(pubkey != myPubkey)
        val rotated = circle.copy(
            keyHex = CircleCrypto.newKey(),
            epoch = circle.epoch + 1,
            members = circle.members.filterNot { it.pubkey == pubkey },
            previousKeyHex = circle.keyHex,
            previousEpoch = circle.epoch,
            previousValidUntil = now() + OVERLAP_SECONDS,
        )
        update { s ->
            s.copy(
                circles = s.circles.map { if (it.id == circleId) rotated else it },
                locations = s.locations + (circleId to (s.locations[circleId].orEmpty() - pubkey)),
            )
        }
        sendRoster(rotated)
        pool.publish(DirectMessages.seal(identity, pubkey, RemovedBody(myPubkey, now(), circleId), now()))
        refreshSubscriptions()
    }

    /** Leaves a circle on this device. (The admin still needs to remove you to rotate keys.) */
    suspend fun leaveCircle(circleId: String) {
        update { s -> s.copy(circles = s.circles.filterNot { it.id == circleId }, locations = s.locations - circleId) }
        refreshSubscriptions()
    }

    private suspend fun sendRoster(circle: Circle) {
        val body = RosterBody(myPubkey, now(), RosterCircle(circle.id, circle.name, circle.keyHex, circle.epoch, circle.adminPubkey, circle.members))
        circle.members.filter { it.pubkey != myPubkey }.forEach { m ->
            pool.publish(DirectMessages.seal(identity, m.pubkey, body, now()))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Location
    // -----------------------------------------------------------------------------------------

    /** Publishes one encrypted update to every circle. Called by the platform's location service. */
    suspend fun publishLocation(location: MemberLocation) {
        val s = state.value
        if (!s.sharing) return
        val t = now()
        s.circles.forEach { c ->
            var loc = location
            var event: Event
            // Drop the oldest batched points until the padded envelope fits the fixed size.
            while (true) {
                event = CircleCrypto.seal(identity, c.keyHex.hexToBytes(), c.epoch, LocationBody(myPubkey, t, c.id, c.epoch, loc), t)
                if (event.content.length <= MAX_LOCATION_CONTENT || loc.points.isEmpty()) break
                loc = loc.copy(points = loc.points.drop(1))
            }
            pool.publish(event)
        }
        update { st ->
            st.copy(locations = st.locations + st.circles.associate { c -> c.id to (st.locations[c.id].orEmpty() + (myPubkey to location)) })
        }
    }

    // -----------------------------------------------------------------------------------------
    // Incoming events
    // -----------------------------------------------------------------------------------------

    private suspend fun onEvent(relay: String, event: Event) {
        val fresh = mutex.withLock { seen.add(event.id) }
        if (!fresh || !event.isValid()) return
        when (event.kind) {
            Kinds.SEALED_DIRECT -> handleDirect(event)
            Kinds.CIRCLE_UPDATE -> handleCircleUpdate(event)
        }
    }

    private suspend fun handleCircleUpdate(event: Event) {
        val (circleId, key, epoch) = tagIndex[event.tag("d") ?: return] ?: return
        val body = CircleCrypto.open(key, epoch, event) as? LocationBody ?: return
        val circle = state.value.circles.firstOrNull { it.id == circleId } ?: return
        if (body.circle != circleId || body.from == myPubkey || circle.members.none { it.pubkey == body.from }) return
        // The publisher key must be the one this member derives for this hour, or someone else is posing as them.
        if (CircleCrypto.publisher(key, body.from, CircleCrypto.slot(event.createdAt)).publicHex != event.pubkey) return
        update { s ->
            val current = s.locations[circleId].orEmpty()
            val existing = current[body.from]
            if (existing != null && existing.time >= body.loc.time) s
            else s.copy(locations = s.locations + (circleId to (current + (body.from to body.loc))))
        }
    }

    private suspend fun handleDirect(event: Event) {
        when (val body = DirectMessages.open(identity, event) ?: return) {
            is JoinRequestBody -> onJoinRequest(body)
            is RosterBody -> onRoster(body)
            is RemovedBody -> onRemoved(body)
            else -> Unit
        }
        update { it.copy(seenEventIds = (it.seenEventIds + event.id).takeLast(500)) }
    }

    private suspend fun onJoinRequest(body: JoinRequestBody) {
        val s = state.value
        val invite = s.invites.firstOrNull { it.secret == body.secret && it.circleId == body.circle } ?: return
        if (invite.used || invite.expiresAt < now()) return
        if (s.joinRequests.any { it.pubkey == body.from && it.circleId == body.circle }) return
        val req = JoinRequest(PlatformCrypto.randomBytes(8).toHex(), body.circle, body.from, body.name.take(40), now())
        update { st ->
            st.copy(
                joinRequests = st.joinRequests + req,
                invites = st.invites.map { if (it.secret == invite.secret) it.copy(used = true) else it },
            )
        }
    }

    private suspend fun onRoster(body: RosterBody) {
        val rc = body.circle
        val s = state.value
        val existing = s.circles.firstOrNull { it.id == rc.id }
        val pending = s.pendingJoins.firstOrNull { it.circleId == rc.id }
        val trusted = when {
            existing != null -> body.from == existing.adminPubkey && rc.admin == existing.adminPubkey && rc.epoch >= existing.epoch
            pending != null -> body.from == pending.inviterPubkey && rc.admin == pending.inviterPubkey
            else -> false
        }
        if (!trusted) return
        if (rc.members.none { it.pubkey == myPubkey }) {
            onRemoved(RemovedBody(body.from, body.ts, rc.id))
            return
        }
        val rotated = existing != null && rc.epoch > existing.epoch
        val circle = Circle(
            id = rc.id, name = rc.name, keyHex = rc.key, epoch = rc.epoch, adminPubkey = rc.admin, members = rc.members,
            previousKeyHex = if (rotated) existing!!.keyHex else existing?.previousKeyHex,
            previousEpoch = if (rotated) existing!!.epoch else existing?.previousEpoch,
            previousValidUntil = if (rotated) now() + OVERLAP_SECONDS else existing?.previousValidUntil,
        )
        update { st ->
            val memberKeys = rc.members.map { it.pubkey }.toSet()
            st.copy(
                circles = st.circles.filterNot { it.id == rc.id } + circle,
                pendingJoins = st.pendingJoins.filterNot { it.circleId == rc.id },
                locations = st.locations + (rc.id to st.locations[rc.id].orEmpty().filterKeys { it in memberKeys }),
            )
        }
        refreshSubscriptions()
    }

    private suspend fun onRemoved(body: RemovedBody) {
        val circle = state.value.circles.firstOrNull { it.id == body.circle } ?: return
        if (body.from != circle.adminPubkey) return
        leaveCircle(circle.id)
    }

    // -----------------------------------------------------------------------------------------
    // Subscriptions and housekeeping
    // -----------------------------------------------------------------------------------------

    private suspend fun refreshSubscriptions() {
        val t = now()
        val slot = CircleCrypto.slot(t)
        val index = mutableMapOf<String, Triple<String, ByteArray, Int>>()
        state.value.circles.forEach { c ->
            val keys = mutableListOf(c.keyHex to c.epoch)
            if (c.previousKeyHex != null && c.previousEpoch != null && (c.previousValidUntil ?: 0) > t) keys += c.previousKeyHex to c.previousEpoch
            keys.forEach { (k, e) ->
                val kb = k.hexToBytes()
                // Current and previous hour, so updates published just before the hour still arrive.
                listOf(slot, slot - 1).forEach { sl -> index[CircleCrypto.tag(kb, e, sl)] = Triple(c.id, kb, e) }
            }
        }
        tagIndex = index
        val subs = buildMap {
            put("qm-dm", listOf(Filter(kinds = listOf(Kinds.SEALED_DIRECT), tagP = listOf(myPubkey), since = t - 7 * 24 * 3600)))
            if (index.isNotEmpty()) put("qm-circles", listOf(Filter(kinds = listOf(Kinds.CIRCLE_UPDATE), tagD = index.keys.sorted())))
        }
        pool.setSubscriptions(subs)
    }

    private suspend fun pruneExpired() {
        val t = now()
        update { s ->
            s.copy(
                invites = s.invites.filter { it.expiresAt > t - 24 * 3600 },
                pendingJoins = s.pendingJoins.filter { it.sentAt > t - 7 * 24 * 3600 },
                circles = s.circles.map { c ->
                    if (c.previousValidUntil != null && c.previousValidUntil < t) c.copy(previousKeyHex = null, previousEpoch = null, previousValidUntil = null) else c
                },
            )
        }
        mutex.withLock { while (seen.size > 5000) seen.remove(seen.first()) }
    }

    private suspend fun update(transform: (AppState) -> AppState) {
        mutex.withLock {
            val next = transform(_state.value)
            if (next != _state.value) {
                _state.value = next
                store.save(json.encodeToString(AppState.serializer(), next))
            }
        }
    }

    companion object {
        /** How long the previous circle key stays valid after a rotation. */
        const val OVERLAP_SECONDS = 10 * 60L

        /**
         * Envelopes are padded to 1400 bytes, which NIP-44 pads to 1536, which base64-encodes to 2140
         * characters. Anything bigger drops batched points, so every update on the relays is the same size.
         */
        const val MAX_LOCATION_CONTENT = 2140

        /** No update for this long means the person is shown as offline. */
        const val OFFLINE_AFTER_SECONDS = 45 * 60L
    }
}
