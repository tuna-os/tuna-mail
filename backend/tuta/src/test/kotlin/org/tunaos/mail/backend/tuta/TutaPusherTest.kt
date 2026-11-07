package org.tunaos.mail.backend.tuta

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.mail.AuthenticationFailedException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.legacy.logging.Log
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaSession
import org.tunaos.tuta.TutaWatchEvent

@OptIn(ExperimentalCoroutinesApi::class)
class TutaPusherTest {
    private val api = FakeTutaApi()
    private val sessions = FakeSessionSource()
    private val callback = RecordingPusherCallback()
    private var pusher: TutaPusher? = null

    @Before
    fun setUp() {
        Log.logger = TestLogger()
    }

    @After
    fun tearDown() {
        pusher?.stop()
    }

    @Test
    fun `a change in a watched folder syncs it, and a connect syncs them all`() {
        val pusher = createPusher(api).apply {
            updateFolders(listOf(INBOX, WORK))
            start()
        }

        api.watchEvents.trySend(TutaWatchEvent.Ready)
        api.watchEvents.trySend(TutaWatchEvent.MailChanged(WORK))
        api.watchEvents.trySend(TutaWatchEvent.MailChanged("folders/unwatched"))
        api.watchEvents.trySend(TutaWatchEvent.MailChanged(null))

        assertThat(callback.events).containsExactly(INBOX, WORK, WORK, INBOX, WORK)
        pusher.stop()
    }

    @Test
    fun `a refused session is reported and not retried`() {
        val refusing = object : TutaApi by api {
            override fun watch(session: TutaSession): Flow<TutaWatchEvent> = flow {
                throw TutaException.of("session_revoked")
            }
        }

        createPusher(refusing).apply {
            updateFolders(listOf(INBOX))
            start()
        }

        assertThat(callback.errors.single()).isInstanceOf<AuthenticationFailedException>()
        assertThat(sessions.isInvalidated).isTrue()
    }

    @Test
    fun `without a session the pusher reports an authentication failure`() {
        sessions.session = null

        createPusher(api).start()

        assertThat(callback.errors.single()).isInstanceOf<AuthenticationFailedException>()
        assertThat(callback.events).isEmpty()
    }

    private fun createPusher(api: TutaApi): TutaPusher {
        return TutaPusher(
            connection = TutaConnection(api, sessions),
            callback = callback,
            dispatcher = UnconfinedTestDispatcher(),
            initialRetryDelay = 10.milliseconds,
        ).also { pusher = it }
    }

    private companion object {
        const val INBOX = "folders/inbox"
        const val WORK = "folders/work"
    }
}

class RecordingPusherCallback : BackendPusherCallback {
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
