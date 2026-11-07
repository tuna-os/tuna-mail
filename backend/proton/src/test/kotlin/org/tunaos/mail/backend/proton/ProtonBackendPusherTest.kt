package org.tunaos.mail.backend.proton

import app.k9mail.backend.testing.InMemoryBackendFolder
import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.single
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.proton.FakeProtonApi.Companion.protonError
import org.tunaos.proton.ProtonWatchEvent

class ProtonBackendPusherTest {
    private val storage = InMemoryBackendStorage()
    private val api = FakeProtonApi()
    private val callback = RecordingCallback()

    @BeforeTest
    fun setUp() {
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
        Log.logger = TestLogger()
    }

    @Test
    fun `watch events become push events for the watched folders they touch`() = runTest {
        storage.folders["work"] = InMemoryBackendFolder("Work", FolderType.REGULAR).apply {
            createMessages("m1" to "/message.eml")
        }
        api.eventId = "event-7"
        api.watchFlow = {
            flow {
                emit(ProtonWatchEvent.Ready("event-7"))
                emit(ProtonWatchEvent.MailChanged(INBOX))
                emit(ProtonWatchEvent.MailChanged("unwatched"))
                emit(ProtonWatchEvent.FoldersChanged)
                emit(ProtonWatchEvent.MessagesChanged(updated = listOf("m1"), deleted = emptyList()))
                awaitCancellation()
            }
        }
        val pusher = createPusher()

        pusher.updateFolders(listOf(INBOX, "work"))
        advanceUntilIdle()
        pusher.stop()

        assertThat(api.watchCalls).containsExactly("event-7")
        assertThat(callback.events).containsExactly(INBOX, "work", INBOX, "work")
        assertThat(callback.errors).isEmpty()
    }

    @Test
    fun `a network failure restarts the poll after a delay`() = runTest {
        var attempts = 0
        val failure = protonError("transport_failed")
        api.watchFlow = {
            flow {
                if (attempts++ == 0) throw failure
                emit(ProtonWatchEvent.MailChanged(INBOX))
                awaitCancellation()
            }
        }
        val pusher = createPusher()

        pusher.updateFolders(listOf(INBOX))
        advanceUntilIdle()
        pusher.stop()

        assertThat(api.watchCalls.size).isEqualTo(2)
        assertThat(callback.events).containsExactly(INBOX)
        assertThat(callback.errors).isEmpty()
    }

    @Test
    fun `a refused session stops the poll and is reported`() = runTest {
        val failure = protonError("session_revoked")
        api.watchFlow = { flow { throw failure } }
        val pusher = createPusher()

        pusher.updateFolders(listOf(INBOX))
        advanceUntilIdle()
        pusher.stop()

        assertThat(api.watchCalls.size).isEqualTo(1)
        assertThat(callback.errors).single().isInstanceOf<AuthenticationFailedException>()
    }

    @Test
    fun `no watched folders means no polling`() = runTest {
        val pusher = createPusher()

        pusher.updateFolders(emptyList())
        advanceUntilIdle()
        pusher.stop()

        assertThat(api.syncCalls).isEmpty()
    }

    private fun TestScope.createPusher() = ProtonBackendPusher(
        api = api,
        backendStorage = storage,
        callback = callback,
        dispatcher = StandardTestDispatcher(testScheduler),
        retryDelays = listOf(1.seconds),
    )

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
