package org.tunaos.mail.backend.proton

import com.fsck.k9.backend.api.Backend
import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.folders.FolderServerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.api.search.SearchStep
import net.thunderbird.backend.api.search.SteppedSearch
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.proton.ProtonApi.Companion.MAX_BATCH
import org.tunaos.proton.ProtonOutcome

private const val TAG = "ProtonBackend"

/**
 * A native Proton Mail account. Folder server ids are Proton label ids and message server ids
 * are Proton message ids, which survive moves. Messages are always stored complete, as the
 * decrypted MIME the SDK builds.
 *
 * User labels are folders here too. Copying into a label applies it; moving out of one removes
 * it. Proton only knows one real location per message, so a copy anywhere else is refused.
 *
 * Proton files the Sent copy of a sent message itself, so nothing is uploaded there: an append
 * to Sent returns no id, and the next sync of Sent brings the server's copy. Drafts are saved to
 * Proton's Drafts, encrypted.
 *
 * It creates its own folders ([RemoteFolderCreator]), always as user folders. Its server search follows Proton's
 * metadata search in steps ([SteppedSearch], [ProtonSearch]).
 */
@Suppress("TooManyFunctions")
class ProtonBackend(
    private val backendStorage: BackendStorage,
    private val api: ProtonApi,
    private val pollConditions: ProtonPollConditions = ProtonPollConditions.AlwaysActive,
) : Backend, RemoteFolderCreator, SteppedSearch {
    private val folderList = ProtonFolderList(backendStorage, api)
    private val messageSync = ProtonMessageSync(backendStorage, api)
    private val serverSearch = ProtonSearch(api)

    override val supportsFlags = true
    override val supportsExpunge = false
    override val supportsMove = true
    override val supportsCopy = true
    override val supportsUpload = true
    override val supportsTrashFolder = true
    override val supportsSearchByDate = false
    override val supportsFolderSubscriptions = false
    override val isPushCapable = true

    override fun refreshFolderList(): FolderPathDelimiter {
        folderList.refresh()
        return FOLDER_PATH_DELIMITER
    }

    override suspend fun create(
        folderServerId: FolderServerId,
        mustCreate: Boolean,
        folderType: FolderType,
    ): Outcome<RemoteFolderCreationOutcome.Success, RemoteFolderCreationOutcome.Error> =
        withContext(Dispatchers.IO) { folderList.create(folderServerId.serverId, mustCreate) }

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

            Flag.FLAGGED -> batch(messageServerIds, "star") { api.star(it, newState) }

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

    /**
     * In Trash and Drafts a delete is permanent (discarding a draft is deleting it); anywhere
     * else it moves to Trash.
     */
    override fun deleteMessages(folderServerId: String, messageServerIds: List<String>) {
        if (folderServerId in PERMANENT_DELETE_FOLDERS) {
            batch(messageServerIds, "delete") { api.delete(it) }
        } else {
            relocate(folderServerId, ProtonFolderIds.TRASH, messageServerIds)
        }
    }

    /** Empties Trash or Spam for good; Proton cannot empty any other folder. */
    override fun deleteAllMessages(folderServerId: String) {
        if (folderServerId != ProtonFolderIds.TRASH && folderServerId != ProtonFolderIds.SPAM) {
            throw MessagingException("Proton can only empty Trash and Spam, not $folderServerId", true)
        }
        protonCall {
            while (api.emptyFolder(folderServerId).more) { /* drain */ }
        }
    }

    override fun moveMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> {
        relocate(sourceFolderServerId, targetFolderServerId, messageServerIds)
        return messageServerIds.associateWith { it }
    }

    override fun moveMessagesAndMarkAsRead(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> {
        relocate(sourceFolderServerId, targetFolderServerId, messageServerIds)
        markRead(messageServerIds, isRead = true)
        return messageServerIds.associateWith { it }
    }

    override fun copyMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> {
        if (targetFolderServerId !in folderList.labelIds()) {
            throw MessagingException("Proton can only copy into a label, not folder $targetFolderServerId", true)
        }
        batch(messageServerIds, "label") { api.label(it, targetFolderServerId, isLabelled = true) }
        return messageServerIds.associateWith { it }
    }

    /** The first step of [searchStep]. */
    override fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<String> = searchStep(
        folderServerId = folderServerId,
        query = query,
        requiredFlags = requiredFlags,
        forbiddenFlags = forbiddenFlags,
        performFullTextSearch = performFullTextSearch,
        cursor = null,
        wanted = 0,
    ).messageServerIds

    /** One step of Proton's metadata search ([ProtonSearch]); bodies are never searched, full text or not. */
    override fun searchStep(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
        cursor: String?,
        wanted: Int,
    ): SearchStep = serverSearch.step(folderServerId, query, requiredFlags, forbiddenFlags, cursor, wanted)

    override fun fetchPart(folderServerId: String, messageServerId: String, part: Part, bodyFactory: BodyFactory) {
        throw UnsupportedOperationException("Proton messages are always downloaded complete")
    }

    override fun findByMessageId(folderServerId: String, messageId: String): String? = null

    override fun uploadMessage(folderServerId: String, message: Message): String? = when (folderServerId) {
        ProtonFolderIds.SENT -> null

        ProtonFolderIds.DRAFTS -> {
            val draft = ProtonOutgoingMailMapper.mapDraft(message)
            protonCall { api.saveDraft(draft) }.id
        }

        else -> throw MessagingException("Proton cannot store a message in folder $folderServerId", true)
    }

    override fun sendMessage(message: Message) {
        val mail = ProtonOutgoingMailMapper.map(message)
        val result = protonCall { api.send(mail) }
        Log.d(TAG, "Proton sent message; Sent copy %s", result.sentMail)
    }

    override fun createPusher(callback: BackendPusherCallback): BackendPusher =
        ProtonBackendPusher(api, backendStorage, callback, ProtonPollSchedule(pollConditions))

    private fun markRead(messageServerIds: List<String>, isRead: Boolean) =
        batch(messageServerIds, "mark_read") { api.markRead(it, isRead) }

    /**
     * A move into a label applies the label and leaves the message where it is; a move out of a
     * label removes it. Anything else is a real move.
     */
    private fun relocate(source: String, target: String, messageServerIds: List<String>) {
        val labelIds = folderList.labelIds()
        if (target in labelIds) {
            batch(messageServerIds, "label") { api.label(it, target, isLabelled = true) }
        } else {
            batch(messageServerIds, "move") { api.move(it, target) }
        }
        if (source in labelIds && source != target) {
            batch(messageServerIds, "label") { api.label(it, source, isLabelled = false) }
        }
    }

    private fun batch(
        messageServerIds: List<String>,
        operation: String,
        request: suspend (List<String>) -> List<ProtonOutcome>,
    ) = protonCall {
        messageServerIds.chunked(MAX_BATCH).forEach { request(it).requireApplied(operation) }
    }

    private companion object {
        const val LIST_PAGE_SIZE = 1000
        val PERMANENT_DELETE_FOLDERS = setOf(ProtonFolderIds.TRASH, ProtonFolderIds.DRAFTS)
    }
}
