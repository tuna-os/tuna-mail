package org.tunaos.mail.backend.tuta

import com.fsck.k9.mail.Message
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.legacy.logging.Log
import org.tunaos.tuta.FolderRole
import org.tunaos.tuta.IdOutcome
import org.tunaos.tuta.MoveOutcome
import org.tunaos.tuta.TutaErrorCode
import org.tunaos.tuta.TutaException

/** What the backend does to mail on the server: flags, moves, deletes, sending and drafts. */
internal class TutaCommands(
    private val connection: TutaConnection,
    private val folders: TutaFolders,
    private val labels: TutaLabels,
    private val passwords: TutaRecipientPasswords,
) {
    fun setFlag(messageServerIds: List<String>, flag: Flag, newState: Boolean) {
        when (flag) {
            Flag.SEEN -> markRead(messageServerIds, newState)

            Flag.FLAGGED -> setFlagged(messageServerIds, newState)

            // Tuta keeps no other flag a client can set; they stay on this device.
            else -> Unit
        }
    }

    fun markAllAsRead(folderServerId: String) {
        val unread = mutableListOf<String>()
        var offset = 0
        do {
            val page = connection.call { sync(it, folderServerId, MARK_ALL_PAGE_SIZE, offset) }
            unread += page.messages.filter { it.header.isUnread }.map { it.header.id }
            offset += page.messages.size
        } while (page.messages.isNotEmpty() && offset < page.exists)
        markRead(unread, isRead = true)
    }

    /** Moves mails; Tuta keeps a mail's id when it moves, but the server's answer is used all the same. */
    fun move(targetFolderServerId: String, messageServerIds: List<String>, markAsRead: Boolean): Map<String, String> {
        val outcomes = messageServerIds.chunked(BATCH_SIZE).flatMap { batch ->
            connection.call { move(it, batch, targetFolderServerId) }
        }
        outcomes.firstNotNullOfOrNull { it.failure() }?.let { throw connection.toMessagingException(it) }
        val newIds = outcomes.associate { it.id to (it.newId ?: it.id) }
        if (markAsRead) markRead(newIds.values.toList(), isRead = true)
        return newIds
    }

    /**
     * Deletes mails for good. Tuta deletes only from Trash and Spam, and discards drafts through Trash; mail anywhere
     * else is moved to Trash, which is what deleting means there.
     */
    fun delete(folderServerId: String, messageServerIds: List<String>) {
        if (messageServerIds.isEmpty()) return
        if (folders.roleOf(folderServerId) == FolderRole.DRAFTS) {
            messageServerIds.forEach { id -> connection.call { deleteDraft(it, id) } }
            return
        }

        try {
            val outcomes = messageServerIds.chunked(BATCH_SIZE).flatMap { batch ->
                connection.call { delete(it, batch, folderServerId) }
            }
            outcomes.firstNotNullOfOrNull { it.failure() }?.let { throw connection.toMessagingException(it) }
        } catch (e: MessagingException) {
            if (e.tutaCode != TutaErrorCode.NOT_TRASH_OR_SPAM) throw e
            val trash = folders.folderWithRole(FolderRole.TRASH) ?: throw e
            move(trash, messageServerIds, markAsRead = false)
        }
    }

    /** Empties Trash or Spam. */
    fun deleteAll(folderServerId: String) {
        connection.call { emptyFolder(it, folderServerId) }
    }

    /**
     * Sends [message]. A confidential one goes with the passwords the composer handed over for it, and, for a recipient
     * outside Tuta it has none for (a message queued before the app restarted), the one its Tuta contact keeps; Tuta
     * refuses the send when a recipient still has none. The passwords are forgotten once it went.
     */
    fun send(message: Message) {
        val outgoing = TutaOutgoing.from(message)
        val messageId = message.messageId
        val request = if (outgoing.confidential) {
            outgoing.toSendRequest(confidentialPasswords(outgoing, messageId?.let(passwords::get).orEmpty()))
        } else {
            outgoing.toSendRequest()
        }
        connection.call { send(it, request) }
        messageId?.let(passwords::remove)
    }

    private fun confidentialPasswords(outgoing: TutaOutgoing, typed: Map<String, String>): Map<String, String> {
        val addresses = outgoing.recipientAddresses
        val missing = addresses.filter { address -> typed.keys.none { it.equals(address, ignoreCase = true) } }
        val stored = missing.chunked(CONTACT_LOOKUP_SIZE).flatMap { batch ->
            connection.call { contactPasswords(it, batch) }
        }
        return sendPasswords(addresses, typed, stored)
    }

    /** Saves [message] as a new draft when [folderServerId] is Drafts; returns its id, or null elsewhere. */
    fun upload(folderServerId: String, message: Message): String? {
        if (folders.roleOf(folderServerId) != FolderRole.DRAFTS) {
            // Tuta files sent mail itself and imports only on its top plans; the app drops its copy and syncs
            // the server's.
            Log.d(TAG, "Not uploading a message to a Tuta folder other than Drafts")
            return null
        }
        val draft = TutaOutgoing.from(message).toDraftRequest()
        return connection.call { saveDraft(it, draft) }
    }

    private fun markRead(messageServerIds: List<String>, isRead: Boolean) {
        for (batch in messageServerIds.chunked(BATCH_SIZE)) {
            connection.call { markRead(it, batch, isRead) }.throwFirstFailure()
        }
    }

    private fun setFlagged(messageServerIds: List<String>, isFlagged: Boolean) {
        val label = labels.flaggedLabelForWriting() ?: return
        val add = if (isFlagged) listOf(label) else emptyList()
        val remove = if (isFlagged) emptyList() else listOf(label)
        for (batch in messageServerIds.chunked(BATCH_SIZE)) {
            connection.call { applyLabels(it, batch, add, remove) }.throwFirstFailure()
        }
    }

    private fun List<IdOutcome>.throwFirstFailure() {
        firstNotNullOfOrNull { it.error }?.let { throw connection.toMessagingException(it) }
    }

    private fun MoveOutcome.failure(): TutaException? = error

    private fun IdOutcome.failure(): TutaException? = error

    private val MessagingException.tutaCode: TutaErrorCode?
        get() = (cause as? TutaException)?.code

    private companion object {
        /** The most ids one Tuta request takes. */
        const val BATCH_SIZE = 50

        /** The most addresses one `contact_passwords` request takes. */
        const val CONTACT_LOOKUP_SIZE = 100
        const val MARK_ALL_PAGE_SIZE = 1000
        const val TAG = "TutaCommands"
    }
}
