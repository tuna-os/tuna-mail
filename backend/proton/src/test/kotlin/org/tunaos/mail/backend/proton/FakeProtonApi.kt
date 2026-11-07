package org.tunaos.mail.backend.proton

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.tunaos.proton.ProtonClient
import org.tunaos.proton.ProtonDraft
import org.tunaos.proton.ProtonDraftResult
import org.tunaos.proton.ProtonEmptyResult
import org.tunaos.proton.ProtonEvents
import org.tunaos.proton.ProtonException
import org.tunaos.proton.ProtonFolder
import org.tunaos.proton.ProtonFolderKind
import org.tunaos.proton.ProtonFolderRole
import org.tunaos.proton.ProtonMailHeader
import org.tunaos.proton.ProtonMailbox
import org.tunaos.proton.ProtonMessage
import org.tunaos.proton.ProtonOutcome
import org.tunaos.proton.ProtonOutgoingMail
import org.tunaos.proton.ProtonSendResult
import org.tunaos.proton.ProtonSession
import org.tunaos.proton.ProtonSessionStore
import org.tunaos.proton.ProtonSyncChanges
import org.tunaos.proton.ProtonSyncResult
import org.tunaos.proton.ProtonTransport
import org.tunaos.proton.ProtonWatchEvent

/**
 * An in-memory Proton account. Messages live in [messages] and are listed newest first by the
 * folders in their label ids; a `since` sync answers from [changes], keyed by the event id.
 */
class FakeProtonApi : ProtonApi {
    var folders: List<ProtonFolder> = SYSTEM_FOLDERS
    val messages = linkedMapOf<String, ProtonMailHeader>()
    var eventId = "event-1"
    val changes = mutableMapOf<String, Pair<String, ProtonSyncChanges>>()

    /** Failures by message id for mark_read, star and move outcomes. */
    val outcomeCodes = mutableMapOf<String, String>()
    var failure: ProtonException? = null
    var watchFlow: () -> Flow<ProtonWatchEvent> = { emptyFlow() }

    val syncCalls = mutableListOf<SyncCall>()
    val markReadCalls = mutableListOf<Pair<List<String>, Boolean>>()
    val starCalls = mutableListOf<Pair<List<String>, Boolean>>()
    val moveCalls = mutableListOf<Pair<List<String>, String>>()
    val messageCalls = mutableListOf<String>()
    val sent = mutableListOf<ProtonOutgoingMail>()
    val drafts = mutableListOf<ProtonDraft>()
    val labelCalls = mutableListOf<Triple<List<String>, String, Boolean>>()
    val deleteCalls = mutableListOf<List<String>>()
    val emptyCalls = mutableListOf<String>()
    var emptyRuns = 1
    val watchCalls = mutableListOf<String>()

    data class SyncCall(val limit: Int, val folder: String, val cursor: String?, val since: String?)

    fun addMessage(
        id: String,
        folder: String,
        dateMs: Long,
        isUnread: Boolean = true,
        isStarred: Boolean = false,
    ): ProtonMailHeader {
        val header = header(id, listOf(folder, ProtonFolderIds.ALL_MAIL), dateMs, isUnread, isStarred)
        messages[id] = header
        return header
    }

    override suspend fun sync(limit: Int, folder: String, cursor: String?, since: String?): ProtonSyncResult {
        syncCalls += SyncCall(limit, folder, cursor, since)
        failure?.let { throw it }
        val all = messages.values.filter { folder in it.labelIds }.sortedByDescending { it.dateMs }
        if (since != null) {
            val (next, delta) = changes[since] ?: (since to ProtonSyncChanges(false, false, emptyList(), emptyList()))
            return result(folder, emptyList(), all.size, null, null, next, delta)
        }
        val start = if (cursor == null) 0 else all.indexOfFirst { it.id == cursor } + 1
        val page = all.drop(start).take(limit)
        val nextCursor = page.lastOrNull()?.id?.takeIf { start + page.size < all.size }
        val allIds = if (cursor == null) all.map { it.id } else null
        return result(folder, page, all.size, allIds, nextCursor, eventId, null)
    }

    override suspend fun events(since: String): ProtonEvents = error("not used")

    override suspend fun message(id: String): ProtonMessage {
        messageCalls += id
        val header = messages[id] ?: throw protonError("invalid_id")
        return ProtonMessage(header, mimeOf(header).toByteArray(), emptyList())
    }

    override suspend fun markRead(ids: List<String>, isRead: Boolean): List<ProtonOutcome> {
        markReadCalls += ids to isRead
        return outcomes(ids)
    }

    override suspend fun star(ids: List<String>, isStarred: Boolean): List<ProtonOutcome> {
        starCalls += ids to isStarred
        return outcomes(ids)
    }

    override suspend fun move(ids: List<String>, destination: String): List<ProtonOutcome> {
        moveCalls += ids to destination
        return outcomes(ids)
    }

    override suspend fun label(ids: List<String>, label: String, isLabelled: Boolean): List<ProtonOutcome> {
        labelCalls += Triple(ids, label, isLabelled)
        return outcomes(ids)
    }

    override suspend fun delete(ids: List<String>): List<ProtonOutcome> {
        deleteCalls += ids
        return outcomes(ids)
    }

    override suspend fun emptyFolder(folder: String): ProtonEmptyResult {
        emptyCalls += folder
        return ProtonEmptyResult(deleted = 150, more = emptyCalls.size < emptyRuns)
    }

    override suspend fun send(mail: ProtonOutgoingMail): ProtonSendResult {
        failure?.let { throw it }
        sent += mail
        return ProtonSendResult("generated@proton.me", "sent-1", 0, 1, 0)
    }

    override suspend fun saveDraft(draft: ProtonDraft, replaces: String?): ProtonDraftResult {
        drafts += draft
        return ProtonDraftResult(id = "draft-${drafts.size}", messageId = "d@proton.me", replaced = false)
    }

    override fun watch(since: String): Flow<ProtonWatchEvent> {
        watchCalls += since
        return watchFlow()
    }

    private fun outcomes(ids: List<String>) = ids.map { ProtonOutcome(it, outcomeCodes[it]) }

    private fun result(
        folder: String,
        page: List<ProtonMailHeader>,
        exists: Int,
        allIds: List<String>?,
        nextCursor: String?,
        eventId: String,
        changes: ProtonSyncChanges?,
    ) = ProtonSyncResult(
        folders = folders,
        folder = folder,
        messages = page,
        exists = exists,
        offset = 0,
        allIds = allIds,
        nextCursor = nextCursor,
        eventId = eventId,
        changes = changes,
    )

    companion object {
        val SYSTEM_FOLDERS = listOf(
            systemFolder(ProtonFolderIds.INBOX, ProtonFolderRole.INBOX, "Inbox"),
            systemFolder(ProtonFolderIds.DRAFTS, ProtonFolderRole.DRAFTS, "Drafts"),
            systemFolder(ProtonFolderIds.SENT, ProtonFolderRole.SENT, "Sent"),
            systemFolder(ProtonFolderIds.STARRED, ProtonFolderRole.STARRED, "Starred"),
            systemFolder(ProtonFolderIds.ARCHIVE, ProtonFolderRole.ARCHIVE, "Archive"),
            systemFolder(ProtonFolderIds.SPAM, ProtonFolderRole.JUNK, "Spam"),
            systemFolder(ProtonFolderIds.TRASH, ProtonFolderRole.TRASH, "Trash"),
            systemFolder(ProtonFolderIds.ALL_MAIL, ProtonFolderRole.OTHER, "All Mail"),
        )

        private fun systemFolder(id: String, role: ProtonFolderRole, label: String) =
            ProtonFolder(id = id, role = role, label = label, kind = ProtonFolderKind.SYSTEM)

        fun header(
            id: String,
            labelIds: List<String>,
            dateMs: Long,
            isUnread: Boolean = true,
            isStarred: Boolean = false,
        ) = ProtonMailHeader(
            id = id,
            subject = "Subject $id",
            dateMs = dateMs,
            sender = ProtonMailbox(address = "alice@proton.me", name = "Alice"),
            isUnread = isUnread,
            confidential = true,
            messageId = "$id@proton.me",
            labelIds = labelIds,
            isStarred = isStarred,
        )

        fun mimeOf(header: ProtonMailHeader) =
            "From: Alice <alice@proton.me>\r\n" +
                "To: bob@proton.me\r\n" +
                "Subject: ${header.subject}\r\n" +
                "Message-ID: <${header.messageId}>\r\n" +
                "Date: Mon, 2 Nov 2026 10:00:00 +0000\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "Body of ${header.id}\r\n"

        /** A real [ProtonException] for [code], parsed by the SDK from an error reply. */
        fun protonError(code: String): ProtonException = runBlocking {
            val transport = ProtonTransport { """{"version":1,"error":{"code":"$code"}}""" }
            val account = ProtonClient(transport = transport).account(ProtonSession("{}"), ProtonSessionStore { })
            try {
                account.events("event")
                error("the fake transport did not fail")
            } catch (e: ProtonException) {
                e
            }
        }
    }
}
