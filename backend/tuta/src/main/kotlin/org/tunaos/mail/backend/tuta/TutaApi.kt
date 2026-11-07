package org.tunaos.mail.backend.tuta

import kotlinx.coroutines.flow.Flow
import org.tunaos.tuta.DraftRequest
import org.tunaos.tuta.IdOutcome
import org.tunaos.tuta.Label
import org.tunaos.tuta.Message
import org.tunaos.tuta.MoveOutcome
import org.tunaos.tuta.SearchPage
import org.tunaos.tuta.SearchQuery
import org.tunaos.tuta.SendOutcome
import org.tunaos.tuta.SendRequest
import org.tunaos.tuta.SyncPage
import org.tunaos.tuta.TutaClient
import org.tunaos.tuta.TutaPendingSession
import org.tunaos.tuta.TutaSession
import org.tunaos.tuta.TutaWatchEvent

/**
 * The part of the Tuta SDK the backend uses, so tests can stand in for it. [SdkTutaApi] is the real one; every call
 * fails with a `TutaException` carrying the protocol's code.
 */
interface TutaApi {
    /** Signs in; see `TutaClient.login` for the second-factor round trip through [pending]. */
    suspend fun login(email: String, password: String, totp: String?, pending: TutaPendingSession?): TutaSession

    suspend fun sync(session: TutaSession, folder: String?, limit: Int, offset: Int): SyncPage

    suspend fun message(session: TutaSession, id: String): Message

    /** One call of Tuta's bounded search scan of [folder]; go on with the page's cursor while it has one. */
    suspend fun search(
        session: TutaSession,
        query: SearchQuery,
        folder: String,
        limit: Int,
        cursor: String?,
    ): SearchPage

    suspend fun markRead(session: TutaSession, ids: List<String>, isRead: Boolean): List<IdOutcome>

    suspend fun move(session: TutaSession, ids: List<String>, destination: String): List<MoveOutcome>

    suspend fun delete(session: TutaSession, ids: List<String>, folder: String): List<IdOutcome>

    suspend fun emptyFolder(session: TutaSession, id: String): Long

    suspend fun send(session: TutaSession, request: SendRequest): SendOutcome

    suspend fun saveDraft(session: TutaSession, draft: DraftRequest): String

    suspend fun deleteDraft(session: TutaSession, id: String)

    suspend fun listLabels(session: TutaSession): List<Label>

    suspend fun createLabel(session: TutaSession, name: String, color: String): String

    suspend fun applyLabels(session: TutaSession, ids: List<String>, add: List<String>, remove: List<String>):
        List<IdOutcome>

    /** Creates a folder under [parent] (a folder id), or at the top level; returns its id. */
    suspend fun createFolder(session: TutaSession, name: String, parent: String?): String

    suspend fun revoke(session: TutaSession)

    /** Gives up a sign-in still waiting for its second factor, so Tuta closes the session it opened for it. */
    suspend fun cancelSignIn(pending: TutaPendingSession)

    fun watch(session: TutaSession): Flow<TutaWatchEvent>
}

/** [TutaApi] over the SDK's [TutaClient]. */
class SdkTutaApi(private val client: TutaClient) : TutaApi {
    override suspend fun login(email: String, password: String, totp: String?, pending: TutaPendingSession?) =
        client.login(email, password, totp, pending)

    override suspend fun sync(session: TutaSession, folder: String?, limit: Int, offset: Int) =
        client.sync(session, folder, limit, offset)

    override suspend fun message(session: TutaSession, id: String) = client.message(session, id)

    override suspend fun search(
        session: TutaSession,
        query: SearchQuery,
        folder: String,
        limit: Int,
        cursor: String?,
    ) = client.search(session, query, folder, limit, cursor)

    override suspend fun markRead(session: TutaSession, ids: List<String>, isRead: Boolean) =
        client.markRead(session, ids, isRead)

    override suspend fun move(session: TutaSession, ids: List<String>, destination: String) =
        client.move(session, ids, destination)

    override suspend fun delete(session: TutaSession, ids: List<String>, folder: String) =
        client.delete(session, ids, folder)

    override suspend fun emptyFolder(session: TutaSession, id: String) = client.emptyFolder(session, id)

    override suspend fun send(session: TutaSession, request: SendRequest) = client.send(session, request)

    override suspend fun saveDraft(session: TutaSession, draft: DraftRequest) = client.saveDraft(session, draft)

    override suspend fun deleteDraft(session: TutaSession, id: String) = client.deleteDraft(session, id)

    override suspend fun listLabels(session: TutaSession) = client.listLabels(session)

    override suspend fun createLabel(session: TutaSession, name: String, color: String) =
        client.createLabel(session, name, color)

    override suspend fun applyLabels(session: TutaSession, ids: List<String>, add: List<String>, remove: List<String>) =
        client.applyLabels(session, ids, add, remove)

    override suspend fun createFolder(session: TutaSession, name: String, parent: String?) =
        client.createFolder(session, name, parent)

    override suspend fun revoke(session: TutaSession) = client.revoke(session)

    override suspend fun cancelSignIn(pending: TutaPendingSession) = client.cancelSignIn(pending)

    override fun watch(session: TutaSession) = client.watch(session)
}
