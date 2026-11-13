package app.quietmap.core.nostr

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RelayStatus { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Keeps websocket connections to a set of relays, replays the current subscriptions on
 * every (re)connect, and fans published events out to every relay.
 */
class RelayPool(
    private val scope: CoroutineScope,
    private val onEvent: suspend (relay: String, event: Event) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val client = HttpClient {
        install(WebSockets) { pingIntervalMillis = 30_000 }
    }

    private val mutex = Mutex()
    private val connections = mutableMapOf<String, Connection>()
    private var subscriptions: Map<String, List<Filter>> = emptyMap()

    private val _status = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    val status: StateFlow<Map<String, RelayStatus>> = _status.asStateFlow()

    suspend fun setRelays(urls: List<String>) = mutex.withLock {
        val wanted = urls.map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.toSet()
        (connections.keys - wanted).forEach { url ->
            connections.remove(url)?.job?.cancel()
            _status.update { it - url }
        }
        (wanted - connections.keys).forEach { url -> connections[url] = Connection(url).also { it.start() } }
    }

    /** Replaces all subscriptions. Keys are subscription ids. */
    suspend fun setSubscriptions(subs: Map<String, List<Filter>>) {
        val old: Map<String, List<Filter>>
        val conns: List<Connection>
        mutex.withLock {
            old = subscriptions
            subscriptions = subs
            conns = connections.values.toList()
        }
        if (old == subs) return
        conns.forEach { c ->
            (old.keys - subs.keys).forEach { c.send(ClientMessages.close(it)) }
            subs.forEach { (id, filters) -> if (old[id] != filters) c.send(ClientMessages.req(id, filters)) }
        }
    }

    suspend fun publish(event: Event) {
        val msg = ClientMessages.event(event)
        mutex.withLock { connections.values.toList() }.forEach { it.send(msg, queueIfOffline = true) }
    }

    fun shutdown() {
        connections.values.forEach { it.job?.cancel() }
        connections.clear()
        client.close()
    }

    private inner class Connection(val url: String) {
        var job: Job? = null
        private val outbox = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private var connected = false

        fun start() {
            job = scope.launch {
                var backoff = 2_000L
                while (isActive) {
                    setStatus(RelayStatus.CONNECTING)
                    try {
                        client.webSocket(url) {
                            connected = true
                            backoff = 2_000L
                            setStatus(RelayStatus.CONNECTED)
                            val subs = mutex.withLock { subscriptions }
                            subs.forEach { (id, filters) -> send(Frame.Text(ClientMessages.req(id, filters))) }
                            val writer = launch { for (m in outbox) send(Frame.Text(m)) }
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) handle(frame.readText())
                                }
                            } finally {
                                writer.cancel()
                            }
                        }
                    } catch (t: Throwable) {
                        if (!isActive) break
                        log("relay $url: ${t.message}")
                    }
                    connected = false
                    setStatus(RelayStatus.DISCONNECTED)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }

        fun send(message: String, queueIfOffline: Boolean = false) {
            if (connected || queueIfOffline) outbox.trySend(message)
        }

        private suspend fun handle(text: String) {
            when (val msg = RelayMessage.parse(text)) {
                is RelayMessage.EventMsg -> onEvent(url, msg.event)
                is RelayMessage.Ok -> if (!msg.accepted) log("relay $url rejected ${msg.eventId.take(8)}: ${msg.message}")
                is RelayMessage.Notice -> log("relay $url notice: ${msg.message}")
                is RelayMessage.Closed -> log("relay $url closed ${msg.subscriptionId}: ${msg.message}")
                else -> Unit
            }
        }

        private fun setStatus(s: RelayStatus) = _status.update { it + (url to s) }
    }
}
