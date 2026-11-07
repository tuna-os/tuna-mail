package org.tunaos.mail.backend.proton

import com.fsck.k9.backend.api.Backend
import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.proton.ProtonApi.Companion.MAX_BATCH

private const val TAG = "ProtonBackend"

/**
 * A native Proton Mail account. Folder server ids are Proton label ids and message server ids
 * are Proton message ids, which survive moves. Messages are always stored complete, as the
 * decrypted MIME the SDK builds.
 *
 * Proton files the Sent copy of a sent message itself, so nothing is uploaded: an append to the
 * Sent folder returns no id, and the next sync of Sent brings the server's copy.
 */
class ProtonBackend(
    private val backendStorage: BackendStorage,
    private val api: ProtonApi,
) : Backend {
    private val folderList = ProtonFolderList(backendStorage, api)
    private val messageSync = ProtonMessageSync(backendStorage, api)

    override val supportsFlags = true
    override val supportsExpunge = false
    override val supportsMove = true
    override val supportsCopy = false
    override val supportsUpload = false
    override val supportsTrashFolder = true
    override val supportsSearchByDate = false
    override val supportsFolderSubscriptions = false
    override val isPushCapable = true

    override fun refreshFolderList(): FolderPathDelimiter {
        folderList.refresh()
        return FOLDER_PATH_DELIMITER
    }

    override fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        messageSync.sync(folderServerId, syncConfig, listener)
    }

    override fun downloadMessage(syncConfig: SyncConfig, folderServerId: String, messageServerId: String) {
        protonCall { messageSync.downloadMessage(folderServerId, messageServerId) }
    }

    override fun downloadMessageStructure(folderServerId: String, messageServerId: String) {
        protonCall { messageSync.downloadMessage(folderServerId, messageServerId) }
    }

    override suspend fun downloadCompleteMessage(folderServerId: String, messageServerId: String) {
        messageSync.downloadMessage(folderServerId, messageServerId)
    }

    override fun setFlag(folderServerId: String, messageServerIds: List<String>, flag: Flag, newState: Boolean) {
        when (flag) {
            Flag.SEEN -> markRead(messageServerIds, newState)
            Flag.FLAGGED -> protonCall {
                messageServerIds.chunked(MAX_BATCH).forEach { api.star(it, newState).requireApplied("star") }
            }
            // Proton sets answered/forwarded itself when a reply is sent through it.
            else -> Log.d(TAG, "Not syncing flag %s to Proton", flag)
        }
    }

    override fun markAllAsRead(folderServerId: String) {
        val unreadIds = protonCall {
            buildList {
                var cursor: String? = null
                do {
                    val page = api.sync(limit = LIST_PAGE_SIZE, folder = folderServerId, cursor = cursor)
                    page.messages.filter { it.isUnread }.mapTo(this) { it.id }
                    cursor = page.nextCursor
                } while (cursor != null)
            }
        }
        markRead(unreadIds, isRead = true)
    }

    override fun expunge(folderServerId: String) {
        throw UnsupportedOperationException("Proton has no expunge")
    }

    /** Outside Trash a delete moves to Trash; in Trash it would delete for good. */
    override fun deleteMessages(folderServerId: String, messageServerIds: List<String>) {
        if (folderServerId != ProtonFolderIds.TRASH) {
            move(messageServerIds, ProtonFolderIds.TRASH)
            return
        }
        throw MessagingException(
            "Permanent deletion is not available in proton-sdk-android 0.1.0; the message stays in Proton's Trash",
            true,
        )
    }

    override fun deleteAllMessages(folderServerId: String) {
        throw MessagingException("Emptying a folder is not available in proton-sdk-android 0.1.0", true)
    }

    override fun moveMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> {
        move(messageServerIds, targetFolderServerId)
        return messageServerIds.associateWith { it }
    }

    override fun moveMessagesAndMarkAsRead(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> {
        move(messageServerIds, targetFolderServerId)
        markRead(messageServerIds, isRead = true)
        return messageServerIds.associateWith { it }
    }

    override fun copyMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>? {
        throw UnsupportedOperationException("Proton has no copy")
    }

    override fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<String> {
        throw MessagingException("Server search is not available for Proton accounts", true)
    }

    override fun fetchPart(folderServerId: String, messageServerId: String, part: Part, bodyFactory: BodyFactory) {
        throw UnsupportedOperationException("Proton messages are always downloaded complete")
    }

    override fun findByMessageId(folderServerId: String, messageId: String): String? = null

    override fun uploadMessage(folderServerId: String, message: Message): String? {
        if (folderServerId == ProtonFolderIds.SENT) return null
        throw MessagingException("Uploading to Proton folder $folderServerId is not supported", true)
    }

    override fun sendMessage(message: Message) {
        val mail = ProtonOutgoingMailMapper.map(message)
        val result = protonCall { api.send(mail) }
        Log.d(TAG, "Proton sent message; Sent copy %s", result.sentMail)
    }

    override fun createPusher(callback: BackendPusherCallback): BackendPusher =
        ProtonBackendPusher(api, backendStorage, callback)

    private fun markRead(messageServerIds: List<String>, isRead: Boolean) = protonCall {
        messageServerIds.chunked(MAX_BATCH).forEach { api.markRead(it, isRead).requireApplied("mark_read") }
    }

    private fun move(messageServerIds: List<String>, destination: String) = protonCall {
        messageServerIds.chunked(MAX_BATCH).forEach { api.move(it, destination).requireApplied("move") }
    }

    private companion object {
        const val LIST_PAGE_SIZE = 1000
    }
}
