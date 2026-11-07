package org.tunaos.mail.backend.proton

import app.k9mail.backend.testing.InMemoryBackendFolder
import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.single
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.proton.FakeProtonApi.Companion.protonError
import org.tunaos.mail.backend.proton.FakeProtonApi.Companion.protonEvents

class ProtonBackendPusherTest {
    private val storage = InMemoryBackendStorage()
    private val api = FakeProtonApi()
    private val callback = RecordingCallback()
    private val conditions = FakeConditions()

    @BeforeTest
    fun setUp() {
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
        Log.logger = TestLogger()
    }

    @Test
    fun `the first poll pushes every watched folder`() = runTest {
        api.eventId = "event-7"
        val pusher = createPusher()

        pusher.updateFolders(listOf(INBOX, "work"))
        runCurrent()
        pusher.stop()

        assertThat(api.eventCalls).containsExactly("event-7")
        assertThat(callback.events).containsExactly(INBOX, "work")
    }

    @Test
    fun `an Inbox change is pushed at once, other folders at the slower pace`() = runTest {
        storage.folders["work"] = InMemoryBackendFolder("Work", FolderType.REGULAR).apply {
            createMessages("m1" to "/message.eml")
        }
        var polls = 0
        api.eventsReply = { since ->
            if (++polls == 2) {
                protonEvents(since, mailChanged = listOf(INBOX, "unwatched"), updated = listOf("m1"))
            } else {
                protonEvents(since)
            }
        }
        val pusher = createPusher()
        pusher.updateFolders(listOf(INBOX, "work"))
        runCurrent()
        callback.events.clear()

        advance(30.seconds)
        assertThat(callback.events).containsExactly(INBOX)

        advance(5.minutes - 30.seconds)
        pusher.stop()

        assertThat(callback.events).containsExactly(INBOX, "work")
    }

    @Test
    fun `polls follow the Inbox pace and never come faster than every 30 seconds`() = runTest {
        val pusher = createPusher()

        pusher.updateFolders(listOf(INBOX))
        advance(2.minutes)
        pusher.stop()

        // At 0, 30, 60, 90 and 120 seconds.
        assertThat(api.eventCalls).hasSize(5)
    }

    @Test
    fun `a metered network polls every 2 minutes`() = runTest {
        conditions.isNetworkMetered = true
        val pusher = createPusher()

        pusher.updateFolders(listOf(INBOX))
        advance(4.minutes)
        pusher.stop()

        assertThat(api.eventCalls).hasSize(3)
    }

    @Test
    fun `with the screen off it polls every 5 minutes, and speeds up within 30 seconds of it turning on`() =
        runTest {
            conditions.isScreenOn = false
            val pusher = createPusher()

            pusher.updateFolders(listOf(INBOX))
            advance(10.minutes)
            assertThat(api.eventCalls).hasSize(3)

            conditions.isScreenOn = true
            advance(1.minutes)
            pusher.stop()

            assertThat(api.eventCalls).hasSize(5)
        }

    @Test
    fun `without the Inbox it polls at the pace of the other folders`() = runTest {
        val pusher = createPusher()

        pusher.updateFolders(listOf("work"))
        advance(10.minutes)
        pusher.stop()

        assertThat(api.eventCalls).hasSize(3)
    }

    @Test
    fun `a network failure restarts the poll after a delay`() = runTest {
        val failure = protonError("transport_failed")
        var attempts = 0
        api.eventsReply = { since -> if (attempts++ == 0) throw failure else protonEvents(since) }
        val pusher = createPusher()

        pusher.updateFolders(listOf(INBOX))
        advance(1.seconds)
        pusher.stop()

        assertThat(api.eventCalls).hasSize(2)
        assertThat(callback.events).containsExactly(INBOX)
        assertThat(callback.errors).isEmpty()
    }

    @Test
    fun `a refused session stops the poll and is reported`() = runTest {
        val failure = protonError("session_revoked")
        api.eventsReply = { throw failure }
        val pusher = createPusher()

        pusher.updateFolders(listOf(INBOX))
        advance(5.minutes)
        pusher.stop()

        assertThat(api.eventCalls).hasSize(1)
        assertThat(callback.errors).single().isInstanceOf<AuthenticationFailedException>()
    }

    @Test
    fun `no watched folders means no polling`() = runTest {
        val pusher = createPusher()

        pusher.updateFolders(emptyList())
        advance(5.minutes)
        pusher.stop()

        assertThat(api.syncCalls).isEmpty()
        assertThat(api.eventCalls).isEmpty()
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun TestScope.createPusher() = ProtonBackendPusher(
        api = api,
        backendStorage = storage,
        callback = callback,
        schedule = ProtonPollSchedule(conditions),
        dispatcher = StandardTestDispatcher(testScheduler),
        retryDelays = listOf(1.seconds),
    )

    private class FakeConditions : ProtonPollConditions {
        override var isScreenOn = true
        override var isNetworkMetered = false
    }

    private class RecordingCallback : BackendPusherCallback {
        val events = mutableListOf<String>()
        val errors = mutableListOf<Exception>()

        override fun onPushEvent(folderServerId: String) {
            events += folderServerId
        }

        override fun onPushError(exception: Exception) {
            errors += exception
        }

        override suspend fun onPushNotSupported() = Unit
    }

    private companion object {
        const val INBOX = ProtonFolderIds.INBOX
    }
}
