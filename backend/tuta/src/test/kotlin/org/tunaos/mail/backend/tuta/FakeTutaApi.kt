package org.tunaos.mail.backend.tuta

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import org.tunaos.tuta.DraftRequest
import org.tunaos.tuta.Folder
import org.tunaos.tuta.FolderRole
import org.tunaos.tuta.IdOutcome
import org.tunaos.tuta.Label
import org.tunaos.tuta.ListedMail
import org.tunaos.tuta.Message
import org.tunaos.tuta.MoveOutcome
import org.tunaos.tuta.SearchHit
import org.tunaos.tuta.SearchPage
import org.tunaos.tuta.SearchQuery
import org.tunaos.tuta.SendOutcome
import org.tunaos.tuta.SendRequest
import org.tunaos.tuta.SyncPage
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaPendingSession
import org.tunaos.tuta.TutaSession
import org.tunaos.tuta.TutaWatchEvent

/** A Tuta account in memory: folders hold listed mails newest first, and every write is recorded. */
class FakeTutaApi(
    var folders: List<Folder> = emptyList(),
) : TutaApi {
    val labels = mutableListOf<Label>()
    val mails = mutableMapOf<String, MutableList<ListedMail>>()
    val messages = mutableMapOf<String, Message>()

    val syncRequests = mutableListOf<Triple<String?, Int, Int>>()
    val markReadCalls = mutableListOf<Pair<List<String>, Boolean>>()
    val moveCalls = mutableListOf<Pair<List<String>, String>>()
    val deleteCalls = mutableListOf<Pair<List<String>, String>>()
    val deletedDrafts = mutableListOf<String>()
    val emptiedFolders = mutableListOf<String>()
    val applyLabelsCalls = mutableListOf<Triple<List<String>, List<String>, List<String>>>()
    val sent = mutableListOf<SendRequest>()
    val drafts = mutableListOf<DraftRequest>()
    val revoked = mutableListOf<TutaSession>()
    val cancelledSignIns = mutableListOf<TutaPendingSession>()
    val searchCalls = mutableListOf<Pair<SearchQuery, String?>>()
    val watchEvents = Channel<TutaWatchEvent>(Channel.UNLIMITED)

    /** Codes to fail the next calls with, one per call, whatever the operation. */
    val failures = ArrayDeque<String>()

    /** Folders whose mails cannot be deleted for good (anything but Trash and Spam). */
    val undeletableFolders = mutableSetOf<String>()

    override suspend fun login(email: String, password: String, totp: String?, pending: TutaPendingSession?) =
        error("not used by the backend")

    override suspend fun sync(session: TutaSession, folder: String?, limit: Int, offset: Int): SyncPage {
        failIfAsked()
        syncRequests += Triple(folder, limit, offset)
        val folderId = folder ?: folders.first { it.role == FolderRole.INBOX }.id
        val all = mails[folderId].orEmpty()
        return SyncPage(
            folders = folders,
            labels = labels.toList(),
            messages = all.drop(offset).take(limit),
            folder = folderId,
            exists = all.size.toLong(),
            allIds = if (offset == 0) all.map { it.header.id } else null,
            offset = offset.toLong(),
        )
    }

    override suspend fun message(session: TutaSession, id: String): Message {
        failIfAsked()
        return messages[id] ?: throw TutaException.of("invalid_id")
    }

    override suspend fun search(
        session: TutaSession,
        query: SearchQuery,
        folder: String,
        limit: Int,
        cursor: String?,
    ): SearchPage {
        failIfAsked()
        searchCalls += query to cursor
        val keyword = query.keyword?.lowercase()
        val all = mails[folder].orEmpty()
        val start = cursor?.toInt() ?: 0
        var end = start
        val hits = mutableListOf<SearchHit>()
        while (end < all.size && end - start < SEARCH_SCAN && hits.size < limit) {
            val mail = all[end++]
            val isMatch = (keyword == null || keyword in mail.header.subject.lowercase()) &&
                (query.isUnread == null || mail.header.isUnread == query.isUnread)
            if (isMatch) hits += SearchHit(mail, folder)
        }
        return SearchPage(hits, nextCursor = end.takeIf { it < all.size }?.toString(), scanned = (end - start).toLong())
    }

    override suspend fun markRead(session: TutaSession, ids: List<String>, isRead: Boolean): List<IdOutcome> {
        failIfAsked()
        markReadCalls += ids to isRead
        return ids.map { IdOutcome(it) }
    }

    override suspend fun move(session: TutaSession, ids: List<String>, destination: String): List<MoveOutcome> {
        failIfAsked()
        moveCalls += ids to destination
        return ids.map { MoveOutcome(id = it, newId = it, source = "source") }
    }

    override suspend fun delete(session: TutaSession, ids: List<String>, folder: String): List<IdOutcome> {
        failIfAsked()
        if (folder in undeletableFolders) throw TutaException.of("not_trash_or_spam")
        deleteCalls += ids to folder
        return ids.map { IdOutcome(it) }
    }

    override suspend fun emptyFolder(session: TutaSession, id: String): Long {
        failIfAsked()
        emptiedFolders += id
        return 0
    }

    override suspend fun send(session: TutaSession, request: SendRequest): SendOutcome {
        failIfAsked()
        sent += request
        return SendOutcome(messageId = "sent-id")
    }

    override suspend fun saveDraft(session: TutaSession, draft: DraftRequest): String {
        failIfAsked()
        drafts += draft
        return "draft-${drafts.size}"
    }

    override suspend fun deleteDraft(session: TutaSession, id: String) {
        failIfAsked()
        deletedDrafts += id
    }

    override suspend fun listLabels(session: TutaSession): List<Label> {
        failIfAsked()
        return labels.toList()
    }

    override suspend fun createLabel(session: TutaSession, name: String, color: String): String {
        failIfAsked()
        val id = "label-${labels.size + 1}"
        labels += Label(id, name, color)
        return id
    }

    override suspend fun applyLabels(
        session: TutaSession,
        ids: List<String>,
        add: List<String>,
        remove: List<String>,
    ): List<IdOutcome> {
        failIfAsked()
        applyLabelsCalls += Triple(ids, add, remove)
        return ids.map { IdOutcome(it) }
    }

    override suspend fun createFolder(session: TutaSession, name: String, parent: String?): String {
        failIfAsked()
        val id = "folder-${folders.size + 1}"
        folders = folders + Folder(id = id, role = FolderRole.OTHER, label = name, parent = parent)
        return id
    }

    override suspend fun revoke(session: TutaSession) {
        revoked += session
    }

    override suspend fun cancelSignIn(pending: TutaPendingSession) {
        cancelledSignIns += pending
    }

    override fun watch(session: TutaSession): Flow<TutaWatchEvent> = watchEvents.consumeAsFlow()

    private fun failIfAsked() {
        failures.removeFirstOrNull()?.let { throw TutaException.of(it) }
    }
}

/** How many mails one fake search call looks at, like the SDK's bounded scan. */
const val SEARCH_SCAN = 500

/** A session object as Tuta's sign-in returns it. */
const val SESSION_JSON =
    """{"login":"alice@tuta.com","user_id":"user","access_token":"token","encrypted_passphrase_key":"key"}"""

class FakeSessionSource(var session: String? = SESSION_JSON) : TutaSessionSource {
    var isInvalidated = false

    override fun current(): String? = session

    override fun invalidate() {
        isInvalidated = true
        session = null
    }
}
