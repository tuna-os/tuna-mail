package org.tunaos.mail.backend.proton

import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.BackendFolder.MoreMessages
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.internet.MimeMessage
import java.util.Date
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.legacy.logging.Log
import org.tunaos.proton.ProtonException
import org.tunaos.proton.ProtonMailHeader

private const val TAG = "ProtonMessageSync"

/**
 * Syncs one folder. The first sync lists the newest [BackendFolder.visibleLimit] messages and
 * remembers Proton's event id; later syncs replay the event log from it, so only what changed is
 * fetched. A larger visible limit ("load more") pages further back from the stored cursor.
 *
 * Every message is stored complete: Proton only hands out the decrypted RFC 5322 message, so
 * there is no cheaper partial download to make.
 */
internal class ProtonMessageSync(
    private val backendStorage: BackendStorage,
    private val api: ProtonApi,
) {
    @Suppress("TooGenericExceptionCaught")
    fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        listener.syncStarted(folderServerId)
        val backendFolder = backendStorage.getFolder(folderServerId)
        try {
            runBlocking {
                protonSuspendCall {
                    FolderRun(backendFolder, folderServerId, syncConfig, listener).run()
                }
            }
            backendFolder.setLastChecked(System.currentTimeMillis())
            backendFolder.setStatus(null)
            listener.syncFinished(folderServerId)
        } catch (e: Exception) {
            Log.e(TAG, e, "Proton sync of folder %s failed", folderServerId)
            backendFolder.setStatus(e.message)
            backendFolder.setLastChecked(System.currentTimeMillis())
            listener.syncFailed(folderServerId, e.message ?: "Proton sync failed", e)
        }
    }

    /** Downloads one message on demand, e.g. one a sync could not fetch. */
    suspend fun downloadMessage(folderServerId: String, messageServerId: String) {
        val backendFolder = backendStorage.getFolder(folderServerId)
        protonSuspendCall {
            val message = api.message(messageServerId)
            backendFolder.saveMessage(message.header.toMimeMessage(message.mime), MessageDownloadState.FULL)
        }
    }

    private inner class FolderRun(
        private val backendFolder: BackendFolder,
        private val folderServerId: String,
        private val syncConfig: SyncConfig,
        private val listener: SyncListener,
    ) {
        private val visibleLimit = backendFolder.visibleLimit
        private val newestLocalDate: Long? = backendFolder.getAllMessagesAndEffectiveDates().values
            .filterNotNull()
            .maxOrNull()

        suspend fun run() {
            listener.syncHeadersStarted(folderServerId)
            val eventId = backendFolder.getFolderExtraString(EXTRA_EVENT_ID)
            if (eventId == null) {
                fullSync()
            } else {
                deltaSync(eventId)
            }
            backfill()
        }

        private suspend fun fullSync() {
            Log.d(TAG, "Listing folder %s from scratch", folderServerId)
            val first = api.sync(limit = pageSize(0), folder = folderServerId)
            listener.syncAuthenticationSuccess()

            val remoteIds = first.allIds?.toSet()
            if (remoteIds != null && syncConfig.syncRemoteDeletions) {
                destroy(backendFolder.getMessageServerIds() - remoteIds)
            }

            val headers = first.messages.toMutableList()
            var cursor = first.nextCursor
            while (cursor != null && wantsMore(headers.size)) {
                val page = api.sync(limit = pageSize(headers.size), folder = folderServerId, cursor = cursor)
                headers += page.messages
                cursor = page.nextCursor
            }
            val newCount = headers.count { !backendFolder.isMessagePresent(it.id) }
            listener.syncHeadersFinished(folderServerId, first.exists, newCount)

            store(headers, isBackfill = false)
            saveCursor(cursor)
            backendFolder.setFolderExtraString(EXTRA_EVENT_ID, first.eventId)
        }

        private suspend fun deltaSync(eventId: String) {
            Log.d(TAG, "Replaying Proton events for folder %s", folderServerId)
            var since = eventId
            var newCount = 0
            while (true) {
                val result = api.sync(limit = 1, folder = folderServerId, since = since)
                listener.syncAuthenticationSuccess()
                val changes = result.changes
                if (changes == null || changes.refresh) {
                    Log.d(TAG, "Proton asked for a full resync of folder %s", folderServerId)
                    backendFolder.setFolderExtraString(EXTRA_CURSOR, null)
                    fullSync()
                    return
                }

                val (inFolder, elsewhere) = changes.updated.partition { folderServerId in it.labelIds }
                if (syncConfig.syncRemoteDeletions) {
                    destroy((changes.deleted + elsewhere.map { it.id }).filter(backendFolder::isMessagePresent).toSet())
                }
                newCount += inFolder.count { !backendFolder.isMessagePresent(it.id) }
                store(inFolder, isBackfill = false)

                since = result.eventId
                backendFolder.setFolderExtraString(EXTRA_EVENT_ID, since)
                if (!changes.more) {
                    listener.syncHeadersFinished(folderServerId, result.exists, newCount)
                    return
                }
            }
        }

        /** Pages further back when the visible limit grew past what is stored. */
        private suspend fun backfill() {
            var cursor = backendFolder.getFolderExtraString(EXTRA_CURSOR) ?: return
            var count = backendFolder.getMessageServerIds().size
            while (wantsMore(count)) {
                val page = api.sync(limit = pageSize(count), folder = folderServerId, cursor = cursor)
                store(page.messages, isBackfill = true)
                count = backendFolder.getMessageServerIds().size
                saveCursor(page.nextCursor)
                cursor = page.nextCursor ?: return
            }
        }

        private suspend fun store(headers: List<ProtonMailHeader>, isBackfill: Boolean) {
            val total = headers.size
            headers.forEachIndexed { index, header ->
                if (backendFolder.isMessagePresent(header.id)) {
                    updateFlags(header)
                } else {
                    download(header, isBackfill)
                }
                listener.syncProgress(folderServerId, index + 1, total)
            }
        }

        private suspend fun download(header: ProtonMailHeader, isBackfill: Boolean) {
            val message = try {
                api.message(header.id)
            } catch (e: ProtonException.Content) {
                // One undecryptable message must not stop the folder; the next full sync retries it.
                Log.w(TAG, e, "Could not fetch Proton message %s in folder %s", header.id, folderServerId)
                return
            }
            backendFolder.saveMessage(header.toMimeMessage(message.mime), MessageDownloadState.FULL)

            val isOldMessage = isBackfill || (newestLocalDate != null && header.dateMs < newestLocalDate)
            listener.syncNewMessage(folderServerId, header.id, isOldMessage)
        }

        private fun updateFlags(header: ProtonMailHeader) {
            val localFlags = backendFolder.getMessageFlags(header.id)
            var isChanged = false
            for ((flag, isSet) in header.flags()) {
                if (flag in syncConfig.syncFlags && (flag in localFlags) != isSet) {
                    backendFolder.setMessageFlag(header.id, flag, isSet)
                    isChanged = true
                }
            }
            if (isChanged) listener.syncFlagChanged(folderServerId, header.id)
        }

        private fun destroy(ids: Set<String>) {
            if (ids.isEmpty()) return
            Log.d(TAG, "Removing %d message(s) no longer in folder %s", ids.size, folderServerId)
            backendFolder.destroyMessages(ids.toList())
            ids.forEach { listener.syncRemovedMessage(folderServerId, it) }
        }

        private fun saveCursor(cursor: String?) {
            backendFolder.setFolderExtraString(EXTRA_CURSOR, cursor)
            backendFolder.setMoreMessages(if (cursor == null) MoreMessages.FALSE else MoreMessages.TRUE)
        }

        /** A visible limit of 0 means no limit. */
        private fun wantsMore(count: Int): Boolean = visibleLimit <= 0 || count < visibleLimit

        private fun pageSize(count: Int): Int =
            if (visibleLimit <= 0) PAGE_SIZE else (visibleLimit - count).coerceIn(1, PAGE_SIZE)
    }

    companion object {
        internal const val EXTRA_EVENT_ID = "protonEventId"
        internal const val EXTRA_CURSOR = "protonCursor"
        private const val PAGE_SIZE = 100
    }
}

internal fun ProtonMailHeader.flags(): Map<Flag, Boolean> = mapOf(
    Flag.SEEN to !isUnread,
    Flag.FLAGGED to isStarred,
)

internal fun ProtonMailHeader.toMimeMessage(mime: ByteArray): MimeMessage {
    val message = MimeMessage.parseMimeMessage(mime.inputStream(), false)
    message.uid = id
    message.setInternalDate(Date(dateMs))
    for ((flag, isSet) in flags()) message.setFlag(flag, isSet)
    if (isDraft) message.setFlag(Flag.DRAFT, true)
    return message
}
