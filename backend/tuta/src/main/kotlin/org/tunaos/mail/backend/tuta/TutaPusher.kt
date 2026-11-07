package org.tunaos.mail.backend.tuta

import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.mail.AuthenticationFailedException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.legacy.logging.Log
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaWatchEvent

/**
 * Push over Tuta's event bus ([TutaApi.watch]): every change Tuta reports in a watched folder triggers a sync of it,
 * and a (re)connect syncs them all, since changes may have been missed in between.
 *
 * A watch that drops is opened again after a growing delay; one that ends because Tuta refused the session is
 * reported through [BackendPusherCallback.onPushError] and not retried.
 */
internal class TutaPusher(
    private val connection: TutaConnection,
    private val callback: BackendPusherCallback,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val initialRetryDelay: Duration = INITIAL_RETRY_DELAY,
) : BackendPusher {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()

    @Volatile
    private var folderServerIds: Set<String> = emptySet()
    private var job: Job? = null

    override fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { watchUntilRefused() }
        }
    }

    override fun updateFolders(folderServerIds: Collection<String>) {
        this.folderServerIds = folderServerIds.toSet()
    }

    override fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }

    override fun reconnect() {
        stop()
        start()
    }

    // A watch must never take the app down: anything unexpected is logged and the watch opened again.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun watchUntilRefused() {
        var retryDelay = initialRetryDelay
        while (true) {
            try {
                val session = connection.session()
                connection.api.watch(session).collect { event ->
                    retryDelay = initialRetryDelay
                    onEvent(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TutaException) {
                val failure = connection.toMessagingException(e)
                if (failure is AuthenticationFailedException) {
                    callback.onPushError(failure)
                    return
                }
                Log.w(TAG, e, "Tuta watch ended; reconnecting in %s", retryDelay)
            } catch (e: AuthenticationFailedException) {
                callback.onPushError(e)
                return
            } catch (e: MessagingException) {
                Log.w(TAG, e, "Tuta watch could not start; retrying in %s", retryDelay)
            } catch (e: Exception) {
                Log.e(TAG, e, "Tuta watch failed; retrying in %s", retryDelay)
            }
            delay(retryDelay)
            retryDelay = minOf(retryDelay * 2, MAXIMUM_RETRY_DELAY)
        }
    }

    private fun onEvent(event: TutaWatchEvent) {
        val folders = when (event) {
            TutaWatchEvent.Ready, TutaWatchEvent.Reconnect, TutaWatchEvent.FoldersChanged -> folderServerIds
            is TutaWatchEvent.MailChanged -> event.folder?.let { setOf(it) intersect folderServerIds } ?: folderServerIds
        }
        folders.forEach(callback::onPushEvent)
    }

    private companion object {
        const val TAG = "TutaPusher"
        val INITIAL_RETRY_DELAY = 30.seconds
        val MAXIMUM_RETRY_DELAY = 15.minutes
    }
}
