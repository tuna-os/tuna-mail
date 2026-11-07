package org.tunaos.mail.backend.proton

import com.fsck.k9.mail.AuthenticationFailedException
import org.tunaos.proton.ProtonClient
import org.tunaos.proton.ProtonDraft
import org.tunaos.proton.ProtonDraftResult
import org.tunaos.proton.ProtonEmptyResult
import org.tunaos.proton.ProtonEvents
import org.tunaos.proton.ProtonException
import org.tunaos.proton.ProtonFolder
import org.tunaos.proton.ProtonMessage
import org.tunaos.proton.ProtonOutcome
import org.tunaos.proton.ProtonOutgoingMail
import org.tunaos.proton.ProtonSearchQuery
import org.tunaos.proton.ProtonSearchResult
import org.tunaos.proton.ProtonSendResult
import org.tunaos.proton.ProtonSession
import org.tunaos.proton.ProtonSessionStore
import org.tunaos.proton.ProtonSyncResult

/**
 * The [ProtonApi] of one account, opened from its stored session on first use rather than when
 * the backend is created: the backend may exist before sign-in has handed over the session. One
 * SDK account is open at a time, and every session it rotates goes to [saveSession]. When Proton
 * refuses the session, it is closed, so the next call picks up the session of a new sign-in.
 *
 * Without a stored session every call fails with [AuthenticationFailedException], so the app asks
 * the user to sign in again.
 */
class StoredSessionProtonApi(
    private val loadSession: () -> String?,
    private val saveSession: (String) -> Unit,
    private val client: ProtonClient,
) : ProtonApi {
    private val lock = Any()
    private var opened: ProtonApi? = null

    private fun api(): ProtonApi = synchronized(lock) {
        opened ?: open().also { opened = it }
    }

    private fun forget(api: ProtonApi) = synchronized(lock) {
        if (opened === api) opened = null
    }

    private suspend fun <T> call(block: suspend (ProtonApi) -> T): T {
        val api = api()
        try {
            return block(api)
        } catch (e: ProtonException.Session) {
            forget(api)
            throw e
        }
    }

    private fun open(): ProtonApi {
        val json = loadSession()
            ?: throw AuthenticationFailedException("No Proton session is stored for this account; sign in again")
        val store = ProtonSessionStore { session -> saveSession(session.json) }
        return SdkProtonApi(client.account(ProtonSession(json), store))
    }

    override suspend fun sync(limit: Int, folder: String, cursor: String?, since: String?): ProtonSyncResult =
        call { it.sync(limit, folder, cursor, since) }

    override suspend fun events(since: String): ProtonEvents = call { it.events(since) }

    override suspend fun message(id: String): ProtonMessage = call { it.message(id) }

    override suspend fun markRead(ids: List<String>, isRead: Boolean): List<ProtonOutcome> =
        call { it.markRead(ids, isRead) }

    override suspend fun star(ids: List<String>, isStarred: Boolean): List<ProtonOutcome> =
        call { it.star(ids, isStarred) }

    override suspend fun move(ids: List<String>, destination: String): List<ProtonOutcome> =
        call { it.move(ids, destination) }

    override suspend fun label(ids: List<String>, label: String, isLabelled: Boolean): List<ProtonOutcome> =
        call { it.label(ids, label, isLabelled) }

    override suspend fun delete(ids: List<String>): List<ProtonOutcome> = call { it.delete(ids) }

    override suspend fun emptyFolder(folder: String): ProtonEmptyResult = call { it.emptyFolder(folder) }

    override suspend fun send(mail: ProtonOutgoingMail): ProtonSendResult = call { it.send(mail) }

    override suspend fun saveDraft(draft: ProtonDraft, replaces: String?): ProtonDraftResult =
        call { it.saveDraft(draft, replaces) }

    override suspend fun createFolder(name: String, parent: String?): ProtonFolder =
        call { it.createFolder(name, parent) }

    override suspend fun search(query: ProtonSearchQuery, limit: Int, cursor: String?): ProtonSearchResult =
        call { it.search(query, limit, cursor) }
}
