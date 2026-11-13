package app.quietmap.core

import app.quietmap.core.model.Member
import app.quietmap.core.protocol.Backups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine behaviour that doesn't need relays (the engine is never started, so nothing connects). */
class EngineTest {
    private class MemoryStore : StateStore {
        var data: String? = null
        override fun load(): String? = data
        override fun save(data: String) { this.data = data }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_763_000_000L

    private fun engine(store: StateStore = MemoryStore()) = Engine(store, scope, { clock })

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun backupRestoresIdentityCirclesAndSettings() = runTest {
        val a = engine()
        a.setMyName("Mom")
        a.setShowPhotos(false)
        val circle = a.createCircle("Family")
        val backup = a.exportBackup("correct horse battery")
        assertTrue(backup.startsWith(Backups.HEADER))

        val b = engine()
        b.importBackup(backup, "correct horse battery")
        assertEquals(a.myPubkey, b.myPubkey)
        assertEquals("Mom", b.state.value.myName)
        assertEquals(listOf(circle.id), b.state.value.circles.map { it.id })
        assertEquals(circle.keyHex, b.state.value.circles.single().keyHex)
        assertFalse(b.state.value.showPhotos)
        assertFalse(b.state.value.sharing, "sharing starts paused after a restore")
        assertTrue(b.state.value.setupDone)
    }

    @Test
    fun backupRejectsWrongPassphraseAndShortOnes() = runTest {
        val a = engine()
        val backup = a.exportBackup("correct horse battery")
        val b = engine()
        val before = b.myPubkey
        assertFailsWith<IllegalArgumentException> { b.importBackup(backup, "wrong passphrase") }
        assertEquals(before, b.myPubkey, "a failed import changes nothing")
        assertFailsWith<IllegalArgumentException> { a.exportBackup("short") }
        assertFailsWith<IllegalArgumentException> { b.importBackup("not a backup", "whatever123") }
    }

    @Test
    fun stateSurvivesRestartWithNewFields() = runTest {
        val store = MemoryStore()
        val a = engine(store)
        val c = a.createCircle("Friends")
        a.setLastCircle(c.id)
        val reopened = engine(store)
        assertEquals(c.id, reopened.state.value.lastCircleId)
        assertEquals(a.myPubkey, reopened.myPubkey)
    }

    @Test
    fun adminMustHandOverBeforeLeaving() = runTest {
        val a = engine()
        val c = a.createCircle("Family")
        // Alone in the circle: leaving is fine.
        a.leaveCircle(c.id)
        assertTrue(a.state.value.circles.isEmpty())
        assertNull(a.state.value.lastCircleId)
    }

    @Test
    fun managersCanInviteButOnlyTheAdminChoosesThem() = runTest {
        val a = engine()
        val c = a.createCircle("Family")
        assertTrue(a.canInvite(c))
        assertTrue(a.createInvite(c.id).startsWith("quietmap://join#"))
        val stranger = Member("f".repeat(64), "Nobody")
        assertFailsWith<IllegalArgumentException> { a.setManager(c.id, stranger.pubkey, true) }
    }
}
