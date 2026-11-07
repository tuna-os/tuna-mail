package org.tunaos.mail.provider

import app.k9mail.feature.account.common.domain.AccountDomainContract.AccountStateRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.feature.account.AccountId

/** The account a provider's sign-in screens sign in: one being set up, or an existing one signing in again. */
interface TunaSignInTarget {
    /** The address to sign in. */
    val email: String

    /** A password to try at once (the one the setup asked for), or empty to ask for it. */
    val password: String

    /** Keeps [session], a finished sign-in of [email], for the account. */
    suspend fun complete(session: String)
}

/**
 * The account being set up. Its address and password come from the setup's password step; the session waits in the
 * [TunaSignInHandoff] until the account exists.
 */
class SetupSignInTarget(
    private val accountStateRepository: AccountStateRepository,
    private val handoff: TunaSignInHandoff,
    private val serverType: String,
) : TunaSignInTarget {
    override val email: String
        get() = accountStateRepository.getState().incomingServerSettings?.username.orEmpty()

    override val password: String
        get() = accountStateRepository.getState().incomingServerSettings?.password.orEmpty()

    override suspend fun complete(session: String) {
        handoff.complete(accountStateRepository, serverType, email, session)
    }
}

/**
 * An existing account of the [provider] signing in again, after the provider refused its session or from its server
 * settings. The new session replaces the old one, which is then closed on the server; the account and its mail stay.
 */
class AccountSignInTarget(
    private val accountId: AccountId,
    override val email: String,
    private val provider: TunaMailProvider,
    private val sessions: TunaAccountSessions,
    private val backendReset: TunaBackendReset,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TunaSignInTarget {
    override val password: String = ""

    override suspend fun complete(session: String) {
        val replaced = withContext(ioDispatcher) {
            sessions.replace(accountId, session).also { backendReset.reset(accountId) }
        }
        if (replaced != null && replaced != session) provider.revoke(replaced)
    }

    override fun toString() = "AccountSignInTarget($accountId)"
}

/**
 * Drops the open backend of an account, so its next use opens one on the account's current session and its push starts
 * over. The app binds it to `BackendManager.removeBackend`.
 */
fun interface TunaBackendReset {
    fun reset(accountId: AccountId)
}
