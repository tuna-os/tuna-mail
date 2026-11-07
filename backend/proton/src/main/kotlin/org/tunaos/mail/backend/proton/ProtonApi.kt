package org.tunaos.mail.backend.proton

import kotlinx.coroutines.flow.Flow
import org.tunaos.proton.ProtonAccount
import org.tunaos.proton.ProtonDraft
import org.tunaos.proton.ProtonDraftResult
import org.tunaos.proton.ProtonEmptyResult
import org.tunaos.proton.ProtonEvents
import org.tunaos.proton.ProtonMessage
import org.tunaos.proton.ProtonOutcome
import org.tunaos.proton.ProtonOutgoingMail
import org.tunaos.proton.ProtonSendResult
import org.tunaos.proton.ProtonSyncResult
import org.tunaos.proton.ProtonWatchEvent

/**
 * The part of a signed-in Proton account the backend uses. [SdkProtonApi] is the real one; tests
 * substitute a fake. Every call may throw [org.tunaos.proton.ProtonException].
 */
interface ProtonApi {
    suspend fun sync(limit: Int, folder: String, cursor: String? = null, since: String? = null): ProtonSyncResult

    suspend fun events(since: String): ProtonEvents

    suspend fun message(id: String): ProtonMessage

    /** At most [MAX_BATCH] ids per call, as for every batch operation below. */
    suspend fun markRead(ids: List<String>, isRead: Boolean): List<ProtonOutcome>

    suspend fun star(ids: List<String>, isStarred: Boolean): List<ProtonOutcome>

    suspend fun move(ids: List<String>, destination: String): List<ProtonOutcome>

    /** Adds or removes the user label [label]; the messages stay in their folder. */
    suspend fun label(ids: List<String>, label: String, isLabelled: Boolean): List<ProtonOutcome>

    /** Deletes permanently, wherever the messages are; also how a draft is discarded. */
    suspend fun delete(ids: List<String>): List<ProtonOutcome>

    /** Permanently deletes a page of Trash or Spam; call again while the result says more. */
    suspend fun emptyFolder(folder: String): ProtonEmptyResult

    suspend fun send(mail: ProtonOutgoingMail): ProtonSendResult

    /** Writes a new draft; [replaces] names the draft it supersedes, which is then deleted. */
    suspend fun saveDraft(draft: ProtonDraft, replaces: String? = null): ProtonDraftResult

    fun watch(since: String): Flow<ProtonWatchEvent>

    companion object {
        const val MAX_BATCH = 50
    }
}

/** [ProtonApi] over the SDK's [ProtonAccount], which owns the session and saves every rotation. */
class SdkProtonApi(private val account: ProtonAccount) : ProtonApi {
    override suspend fun sync(limit: Int, folder: String, cursor: String?, since: String?): ProtonSyncResult =
        account.sync(limit = limit, folder = folder, cursor = cursor, since = since)

    override suspend fun events(since: String): ProtonEvents = account.events(since)

    override suspend fun message(id: String): ProtonMessage = account.message(id)

    override suspend fun markRead(ids: List<String>, isRead: Boolean): List<ProtonOutcome> =
        account.markRead(ids, isRead)

    override suspend fun star(ids: List<String>, isStarred: Boolean): List<ProtonOutcome> =
        account.star(ids, isStarred)

    override suspend fun move(ids: List<String>, destination: String): List<ProtonOutcome> =
        account.move(ids, destination)

    override suspend fun label(ids: List<String>, label: String, isLabelled: Boolean): List<ProtonOutcome> =
        account.label(ids, label, isLabelled)

    override suspend fun delete(ids: List<String>): List<ProtonOutcome> = account.delete(ids)

    override suspend fun emptyFolder(folder: String): ProtonEmptyResult = account.emptyFolder(folder)

    override suspend fun send(mail: ProtonOutgoingMail): ProtonSendResult = account.send(mail)

    override suspend fun saveDraft(draft: ProtonDraft, replaces: String?): ProtonDraftResult =
        account.saveDraft(draft, replaces)

    override fun watch(since: String): Flow<ProtonWatchEvent> = account.watch(since)
}
