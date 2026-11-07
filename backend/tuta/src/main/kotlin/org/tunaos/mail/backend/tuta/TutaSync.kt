package org.tunaos.mail.backend.tuta

import com.fsck.k9.backend.api.BackendFolder
import com.fsck.k9.backend.api.BackendFolder.MoreMessages
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.internet.MimeMessage
import java.util.Date
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.legacy.logging.Log
import org.tunaos.tuta.ListedMail
import org.tunaos.tuta.MailHeader
import org.tunaos.tuta.SyncPage

/**
 * Syncs one folder: lists its newest mails (as many as the folder shows), stores the new ones, updates the flags of
 * the known ones and drops the ones the folder no longer holds.
 *
 * A new mail without attachments is downloaded whole; one with attachments is stored as its envelope, and downloaded
 * when it is opened ([downloadMessage]), so a sync never pulls attachments the user may not open.
 */
internal class TutaSync(
    private val backendStorage: BackendStorage,
    private val connection: TutaConnection,
    private val folders: TutaFolders,
    private val labels: TutaLabels,
) {
    @Suppress("TooGenericExceptionCaught")
    fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        listener.syncStarted(folderServerId)
        try {
            syncFolder(folderServerId, syncConfig, listener)
            listener.syncFinished(folderServerId)
        } catch (e: AuthenticationFailedException) {
            Log.e(TAG, e, "Tuta refused the session while syncing a folder")
            listener.syncFailed(folderServerId, "Authentication failed", e)
        } catch (e: Exception) {
            Log.e(TAG, e, "Failed to sync a Tuta folder")
            listener.syncFailed(folderServerId, e.message ?: "Sync failed", e)
        }
    }

    /**
     * Downloads the whole of a mail and stores it in [folderServerId]. A mail read on its own comes without its labels,
     * so it keeps the flagged state it has on this device.
     */
    suspend fun downloadMessage(folderServerId: String, messageServerId: String) {
        val backendFolder = backendStorage.getFolder(folderServerId)
        val isFlagged = backendFolder.isMessagePresent(messageServerId) &&
            Flag.FLAGGED in backendFolder.getMessageFlags(messageServerId)
        val message = connection.suspendCall { message(it, messageServerId) }
        val mimeMessage = TutaMime.fullMessage(message).withListing(message.header, labels = emptyList())
        mimeMessage.setFlag(Flag.FLAGGED, isFlagged)
        backendFolder.saveMessage(mimeMessage, MessageDownloadState.FULL)
    }

    private fun syncFolder(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        val backendFolder = backendStorage.getFolder(folderServerId)
        val limit = backendFolder.visibleLimit.takeIf { it > 0 } ?: Int.MAX_VALUE

        listener.syncHeadersStarted(folderServerId)
        val firstPage = connection.call { sync(it, folderServerId, minOf(PAGE_SIZE, limit), 0) }
        listener.syncAuthenticationSuccess()
        folders.remember(firstPage.folders)
        labels.remember(firstPage.labels)

        val listed = listMails(folderServerId, firstPage, limit, syncConfig.earliestPollDate)
        val localServerIds = backendFolder.getMessageServerIds()
        if (syncConfig.syncRemoteDeletions) {
            removeDeletedMails(backendFolder, folderServerId, localServerIds, firstPage, listener)
        }

        val (knownMails, newMails) = listed.partition { it.header.id in localServerIds }
        updateFlags(backendFolder, folderServerId, knownMails, syncConfig, listener)
        listener.syncHeadersFinished(folderServerId, firstPage.exists.toInt(), newMails.size)

        val hasSyncedBefore = backendFolder.getFolderExtraNumber(EXTRA_SYNCED) != null
        val newestLocalDate = backendFolder.getAllMessagesAndEffectiveDates().values.filterNotNull().maxOrNull()
        newMails.forEachIndexed { index, mail ->
            storeNewMail(backendFolder, mail)
            val isOldMessage = !hasSyncedBefore || (newestLocalDate != null && mail.header.dateMs < newestLocalDate)
            listener.syncNewMessage(folderServerId, mail.header.id, isOldMessage)
            listener.syncProgress(folderServerId, index + 1, newMails.size)
        }

        backendFolder.setMoreMessages(if (firstPage.exists > listed.size) MoreMessages.TRUE else MoreMessages.FALSE)
        backendFolder.setFolderExtraNumber(EXTRA_SYNCED, 1)
        backendFolder.setLastChecked(System.currentTimeMillis())
        backendFolder.setStatus(null)
    }

    /** The folder's newest mails, up to [limit] and not older than [earliestDate], newest first. */
    private fun listMails(folderServerId: String, firstPage: SyncPage, limit: Int, earliestDate: Date?): List<ListedMail> {
        val listed = firstPage.messages.toMutableList()
        var page = firstPage.messages
        while (listed.size < limit && listed.size < firstPage.exists && page.isNotEmpty() &&
            !page.last().isOlderThan(earliestDate)
        ) {
            val offset = listed.size
            page = connection.call { sync(it, folderServerId, minOf(PAGE_SIZE, limit - offset), offset) }.messages
            listed += page
        }
        return listed.filterNot { it.isOlderThan(earliestDate) }.distinctBy { it.header.id }
    }

    private fun removeDeletedMails(
        backendFolder: BackendFolder,
        folderServerId: String,
        localServerIds: Set<String>,
        firstPage: SyncPage,
        listener: SyncListener,
    ) {
        // The first page carries every id the folder holds, so anything else has left it.
        val remoteServerIds = firstPage.allIds?.toSet() ?: return
        val removed = (localServerIds - remoteServerIds).toList()
        if (removed.isEmpty()) return

        backendFolder.destroyMessages(removed)
        removed.forEach { listener.syncRemovedMessage(folderServerId, it) }
    }

    private fun updateFlags(
        backendFolder: BackendFolder,
        folderServerId: String,
        mails: List<ListedMail>,
        syncConfig: SyncConfig,
        listener: SyncListener,
    ) {
        val flaggedLabel = labels.flaggedLabel
        for (mail in mails) {
            val serverId = mail.header.id
            val remoteFlags = mail.header.flags(mail.labels, flaggedLabel)
            val localFlags = backendFolder.getMessageFlags(serverId)
            var changed = false
            for (flag in syncConfig.syncFlags) {
                // Without the label nothing is flagged on the server; keep the flags set on this device.
                if (flag == Flag.FLAGGED && flaggedLabel == null) continue
                val isSetRemotely = flag in remoteFlags
                if (isSetRemotely != flag in localFlags) {
                    backendFolder.setMessageFlag(serverId, flag, isSetRemotely)
                    changed = true
                }
            }
            if (changed) listener.syncFlagChanged(folderServerId, serverId)
        }
    }

    private fun storeNewMail(backendFolder: BackendFolder, mail: ListedMail) {
        val header = mail.header
        val (mimeMessage, downloadState) = if (header.hasAttachments) {
            TutaMime.envelope(header) to MessageDownloadState.ENVELOPE
        } else {
            downloadOrEnvelope(header)
        }
        runBlocking { backendFolder.saveMessage(mimeMessage.withListing(header, mail.labels), downloadState) }
    }

    /** The whole mail; its envelope when Tuta cannot hand it out (so one bad mail does not stop the sync). */
    private fun downloadOrEnvelope(header: MailHeader): Pair<MimeMessage, MessageDownloadState> {
        return try {
            TutaMime.fullMessage(connection.call { message(it, header.id) }) to MessageDownloadState.FULL
        } catch (e: MessagingException) {
            if (!e.isPermanentFailure || e is AuthenticationFailedException) throw e
            Log.w(TAG, e, "Could not download a Tuta mail; keeping its envelope")
            TutaMime.envelope(header) to MessageDownloadState.ENVELOPE
        }
    }

    /** Sets the server id, date and flags the listing gave. */
    private fun MimeMessage.withListing(header: MailHeader, labels: List<String>): MimeMessage = apply {
        uid = header.id
        internalDate = Date(header.dateMs)
        setFlags(header.flags(labels, this@TutaSync.labels.flaggedLabel), true)
    }

    private fun ListedMail.isOlderThan(date: Date?): Boolean = date != null && header.dateMs < date.time

    companion object {
        /** Mails asked for per request. */
        const val PAGE_SIZE = 100
        private const val EXTRA_SYNCED = "tutaSynced"
        private const val TAG = "TutaSync"
    }
}

/** The app's flags for a mail Tuta lists with [labels]; [flaggedLabel] is the label that stands for "flagged". */
internal fun MailHeader.flags(labels: List<String>, flaggedLabel: String?): Set<Flag> = buildSet {
    if (!isUnread) add(Flag.SEEN)
    if (flaggedLabel != null && flaggedLabel in labels) add(Flag.FLAGGED)
    if (replyType and REPLIED != 0) add(Flag.ANSWERED)
    if (replyType and FORWARDED != 0) add(Flag.FORWARDED)
}

private const val REPLIED = 1
private const val FORWARDED = 2
