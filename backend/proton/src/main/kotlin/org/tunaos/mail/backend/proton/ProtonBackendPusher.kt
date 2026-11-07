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
import org.tunaos.proton.ProtonEvents
import org.tunaos.proton.ProtonException

private const val TAG = "ProtonBackendPusher"

/**
 * Proton has no push channel for third-party clients, so "push" is a poll of the events API
 * ([ProtonApi.events]). Every change it reports becomes a push event for each watched folder it
 * touches; the messaging controller then syncs that folder, which replays the event log.
 *
 * How often it polls follows [ProtonPollSchedule]: as often as Proton's own clients while the
 * screen is on and the Inbox is watched, less often on a metered network or with the screen off.
 * A change in the Inbox is pushed as soon as a poll sees it; changes in other folders are
 * collected and pushed together at the slower pace of [ProtonPollSchedule.otherFolders], since
 * each push costs a folder sync.
 *
 * A network failure restarts the poll after a growing delay; a refused session ends it and is
 * reported, since only signing in again can fix it.
 */
@Suppress("TooManyFunctions")
internal class ProtonBackendPusher(
    private val api: ProtonApi,
    private val backendStorage: BackendStorage,
    private val callback: BackendPusherCallback,
    private val schedule: ProtonPollSchedule,
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
        job = scope.launch { pollWithRetry() }
    }

    private fun stopWatching() {
        job?.cancel()
        job = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pollWithRetry() {
        var failures = 0
        while (true) {
            try {
                poll(onPolled = { failures = 0 })
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

    /**
     * Polls from the Inbox's current event id until cancelled. The first poll pushes every watched
     * folder, so whatever changed while nobody polled is synced.
     */
    private suspend fun poll(onPolled: () -> Unit) {
        var eventId = api.sync(limit = 1, folder = ProtonFolderIds.INBOX).eventId
        val pending = mutableSetOf<String>()
        var sinceOtherFolders = Duration.ZERO
        var isFirst = true
        while (true) {
            if (!isFirst) sinceOtherFolders += waitForNextPoll()
            do {
                val events = api.events(eventId)
                eventId = events.eventId
                onPolled()
                pending += changedFolders(events)
            } while (events.more)

            val watched = watched()
            if (isFirst) pending += watched
            val isOtherFoldersDue = isFirst || sinceOtherFolders >= schedule.otherFolders()
            val due = pending.filter { it == ProtonFolderIds.INBOX || isOtherFoldersDue }.filter { it in watched }
            pending -= due.toSet()
            pending.retainAll(watched)
            if (isOtherFoldersDue) sinceOtherFolders = Duration.ZERO
            due.forEach(callback::onPushEvent)
            isFirst = false
        }
    }

    /**
     * Waits until the next poll is due, in steps of [ProtonPollSchedule.MINIMUM_INTERVAL] so a
     * screen turned on or a network change takes effect within one step. Returns the time waited.
     */
    private suspend fun waitForNextPoll(): Duration {
        var waited = Duration.ZERO
        do {
            delay(ProtonPollSchedule.MINIMUM_INTERVAL)
            waited += ProtonPollSchedule.MINIMUM_INTERVAL
        } while (waited < schedule.interval(isInboxWatched = ProtonFolderIds.INBOX in watched()))
        return waited
    }

    private fun changedFolders(events: ProtonEvents): Set<String> {
        val watched = watched()
        return when {
            events.refresh -> watched

            else -> buildSet {
                events.mailChanged.filterTo(this) { it in watched }
                val ids = events.updated + events.deleted
                if (ids.isNotEmpty()) watched.filterTo(this) { folder -> ids.any { isPresent(folder, it) } }
            }
        }
    }

    private fun watched(): Set<String> = synchronized(lock) { folderServerIds }

    private fun isPresent(folderServerId: String, messageServerId: String): Boolean =
        folderServerId in backendStorage.getFolderServerIds() &&
            backendStorage.getFolder(folderServerId).isMessagePresent(messageServerId)

    private companion object {
        val DEFAULT_RETRY_DELAYS = listOf(30.seconds, 1.minutes, 5.minutes, 15.minutes)
    }
}
