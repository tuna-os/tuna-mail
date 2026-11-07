package org.tunaos.mail.backend.proton

import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.backend.api.BackendStorage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.thunderbird.legacy.logging.Log
import org.tunaos.proton.ProtonException
import org.tunaos.proton.ProtonWatchEvent

private const val TAG = "ProtonBackendPusher"

/**
 * Proton has no push channel for third-party clients, so "push" is the SDK's event poll
 * ([ProtonApi.watch]). Every change it reports becomes a push event for each watched folder it
 * touches; the messaging controller then syncs that folder, which replays the event log.
 *
 * A network failure restarts the poll after a growing delay; a refused session ends it and is
 * reported, since only signing in again can fix it.
 */
internal class ProtonBackendPusher(
    private val api: ProtonApi,
    private val backendStorage: BackendStorage,
    private val callback: BackendPusherCallback,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
) : BackendPusher {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var folderServerIds: Set<String> = emptySet()
    private var job: Job? = null

    override fun start() = Unit

    override fun updateFolders(folderServerIds: Collection<String>) {
        synchronized(lock) {
            this.folderServerIds = folderServerIds.toSet()
            if (this.folderServerIds.isEmpty()) {
                stopWatching()
            } else if (job?.isActive != true) {
                startWatching()
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopWatching()
            folderServerIds = emptySet()
        }
        scope.cancel()
    }

    override fun reconnect() {
        synchronized(lock) {
            stopWatching()
            if (folderServerIds.isNotEmpty()) startWatching()
        }
    }

    private fun startWatching() {
        job = scope.launch { watchWithRetry() }
    }

    private fun stopWatching() {
        job?.cancel()
        job = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun watchWithRetry() {
        var failures = 0
        while (true) {
            try {
                val since = api.sync(limit = 1, folder = ProtonFolderIds.INBOX).eventId
                api.watch(since).collect { event ->
                    failures = 0
                    handle(event)
                }
            } catch (e: ProtonException.Transport) {
                Log.w(TAG, e, "Proton event poll failed; retrying")
                delay(retryDelays[failures.coerceAtMost(retryDelays.lastIndex)])
                failures++
            } catch (e: ProtonException) {
                Log.e(TAG, e, "Proton event poll stopped")
                callback.onPushError(e.toMessagingException())
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e, "Proton event poll failed unexpectedly")
                callback.onPushError(e)
                return
            }
        }
    }

    private fun handle(event: ProtonWatchEvent) {
        val watched = synchronized(lock) { folderServerIds }
        val folders = when (event) {
            is ProtonWatchEvent.Ready, ProtonWatchEvent.Refresh -> watched

            ProtonWatchEvent.FoldersChanged -> emptySet()

            is ProtonWatchEvent.MailChanged -> watched.filter { it == event.folder }.toSet()

            is ProtonWatchEvent.MessagesChanged -> {
                val ids = event.updated + event.deleted
                watched.filter { folder -> ids.any { isPresent(folder, it) } }.toSet()
            }
        }
        folders.forEach(callback::onPushEvent)
    }

    private fun isPresent(folderServerId: String, messageServerId: String): Boolean =
        folderServerId in backendStorage.getFolderServerIds() &&
            backendStorage.getFolder(folderServerId).isMessagePresent(messageServerId)

    private companion object {
        val DEFAULT_RETRY_DELAYS = listOf(30.seconds, 1.minutes, 5.minutes, 15.minutes)
    }
}
