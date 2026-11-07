package org.tunaos.mail.backend.tuta

import com.fsck.k9.backend.api.Backend
import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

/**
 * A Tuta account, through the Tuta SDK ([TutaApi]): folders are Tuta's folder tree, a message's server id is its
 * Tuta mail id, mail is read decrypted and rebuilt as MIME ([TutaMime]), and sending and drafts go through Tuta's
 * own operations ([TutaOutgoing]). Push follows Tuta's event bus ([TutaPusher]). It creates its own folders
 * ([RemoteFolderCreator]).
 */
@Suppress("TooManyFunctions")
class TutaBackend(
    backendStorage: BackendStorage,
    api: TutaApi,
    sessions: TutaSessionSource,
    notices: TutaNotices = TutaNotices {},
) : Backend, RemoteFolderCreator {
    private val connection = TutaConnection(api, sessions)
    private val folders = TutaFolders(backendStorage, connection)
    private val labels = TutaLabels(backendStorage, connection, notices)
    private val tutaSync = TutaSync(backendStorage, connection, folders, labels)
    private val commands = TutaCommands(connection, folders, labels)

    override val supportsFlags = true
    override val supportsExpunge = false
    override val supportsMove = true
    override val supportsCopy = false
    override val supportsUpload = true
    override val supportsTrashFolder = true
    override val supportsSearchByDate = false
    override val supportsFolderSubscriptions = false
    override val isPushCapable = true

    override fun refreshFolderList(): FolderPathDelimiter = folders.refreshFolderList()

    /** Creates the folder at the path [folderServerId] names; Tuta has no folder types to give it. */
    override suspend fun create(
        folderServerId: FolderServerId,
        mustCreate: Boolean,
        folderType: FolderType,
    ): Outcome<RemoteFolderCreationOutcome.Success, RemoteFolderCreationOutcome.Error> =
        withContext(Dispatchers.IO) { folders.create(folderServerId.serverId, mustCreate) }

    override fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        tutaSync.sync(folderServerId, syncConfig, listener)
    }

    override fun downloadMessage(syncConfig: SyncConfig, folderServerId: String, messageServerId: String) {
        runBlocking { tutaSync.downloadMessage(folderServerId, messageServerId) }
    }

    override fun downloadMessageStructure(folderServerId: String, messageServerId: String) {
        runBlocking { tutaSync.downloadMessage(folderServerId, messageServerId) }
    }

    override suspend fun downloadCompleteMessage(folderServerId: String, messageServerId: String) {
        tutaSync.downloadMessage(folderServerId, messageServerId)
    }

    override fun setFlag(folderServerId: String, messageServerIds: List<String>, flag: Flag, newState: Boolean) {
        commands.setFlag(messageServerIds, flag, newState)
    }

    override fun markAllAsRead(folderServerId: String) {
        commands.markAllAsRead(folderServerId)
    }

    override fun expunge(folderServerId: String) = Unit

    override fun deleteMessages(folderServerId: String, messageServerIds: List<String>) {
        commands.delete(folderServerId, messageServerIds)
    }

    override fun deleteAllMessages(folderServerId: String) {
        commands.deleteAll(folderServerId)
    }

    override fun moveMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> = commands.move(targetFolderServerId, messageServerIds, markAsRead = false)

    override fun moveMessagesAndMarkAsRead(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> = commands.move(targetFolderServerId, messageServerIds, markAsRead = true)

    /**
     * Never called: [supportsCopy] is false, which hides the copy action. Tuta has no server-side copy, and rebuilding
     * the mail as an import into the target folder is not a copy worth offering: import is refused below the Legend
     * and Unlimited plans (so the action would fail for most accounts, and a backend cannot vary [supportsCopy] by
     * plan), and an imported mail is a second, separate mail, without the original's labels, that counts twice
     * against the account's storage.
     */
    override fun copyMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>? = throw MessagingException("Tuta cannot copy mail", true)

    /** Tuta's search runs on the device in its own apps; the SDK offers none, so there is no server search. */
    override fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<String> = emptyList()

    /** Messages are downloaded whole ([downloadCompleteMessage]), so there are no parts to fetch on their own. */
    override fun fetchPart(folderServerId: String, messageServerId: String, part: Part, bodyFactory: BodyFactory) {
        throw MessagingException("Tuta mail is downloaded whole", true)
    }

    override fun findByMessageId(folderServerId: String, messageId: String): String? = null

    override fun uploadMessage(folderServerId: String, message: Message): String? =
        commands.upload(folderServerId, message)

    override fun sendMessage(message: Message) {
        commands.send(message)
    }

    override fun createPusher(callback: BackendPusherCallback): BackendPusher = TutaPusher(connection, callback)
}
