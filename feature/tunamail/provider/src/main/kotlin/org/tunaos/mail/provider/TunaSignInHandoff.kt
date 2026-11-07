package org.tunaos.mail.provider

import app.k9mail.feature.account.common.domain.AccountDomainContract.AccountStateRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Carries a session from a provider's sign-in screen to the account it belongs to.
 *
 * The sign-in happens during setup, before the account (and its UUID) exists, so the session waits here, in memory
 * only, until [TunaAccountSessions.sessionFor] adopts it for the new account. A session that is never adopted
 * (setup abandoned) dies with the process and expires on the server.
 */
class TunaSignInHandoff {
    private val sessions = ConcurrentHashMap<Key, String>()

    /**
     * Records a finished sign-in: removes the password from the account being set up, so it is never saved, and
     * keeps [session] for the account until it is created.
     */
    fun complete(accountStateRepository: AccountStateRepository, serverType: String, email: String, session: String) {
        val state = accountStateRepository.getState()
        state.incomingServerSettings?.let { accountStateRepository.setIncomingServerSettings(it.copy(password = null)) }
        state.outgoingServerSettings?.let { accountStateRepository.setOutgoingServerSettings(it.copy(password = null)) }
        sessions[Key(serverType, email.lowercase())] = session
    }

    /** The session signed in for [email] with the [serverType] provider, removed from the handoff; null if none. */
    fun take(serverType: String, email: String): String? = sessions.remove(Key(serverType, email.lowercase()))

    private data class Key(val serverType: String, val email: String)
}
