package org.tunaos.mail.provider

import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountId
import org.tunaos.mail.backend.session.TunaSessionStore

/** The sessions of the provider accounts, for their backends. */
class TunaAccountSessions(
    private val store: TunaSessionStore,
    private val handoff: TunaSignInHandoff,
    private val accountManager: LegacyAccountManager,
) {
    /**
     * The session of the account [accountId], an [email] account of the [serverType] provider; null when it has none
     * (it has to be signed in again).
     *
     * The first call for a new account adopts the session its setup signed in ([TunaSignInHandoff]): it is saved in
     * the [TunaSessionStore], and the account stops uploading sent mail, since the provider files it in Sent itself.
     * Later calls read the store; call it whenever a session is needed rather than caching it.
     */
    fun sessionFor(accountId: AccountId, serverType: String, email: String): String? {
        val accountUuid = accountId.toString()
        return store.get(accountUuid) ?: adoptParkedSession(accountId, serverType, email)
    }

    private fun adoptParkedSession(accountId: AccountId, serverType: String, email: String): String? {
        val session = handoff.take(serverType, email) ?: return null
        store.put(accountId.toString(), session)
        accountManager.updateSync(accountId) { it.copy(isUploadSentMessages = false) }
        return session
    }

    /** Saves a [session] the provider renewed for the account [accountId]. */
    fun save(accountId: AccountId, session: String) {
        store.put(accountId.toString(), session)
    }

    /**
     * Keeps [session], from a sign-in of the existing account [accountId], in place of its session and returns the one
     * it replaced (null when it had none). Like a new account, it stops uploading sent mail: one whose setup never
     * handed it a session may still have the default.
     */
    fun replace(accountId: AccountId, session: String): String? {
        val replaced = store.replace(accountId.toString(), session)
        if (accountManager.findById(accountId)?.isUploadSentMessages == true) {
            accountManager.updateSync(accountId) { it.copy(isUploadSentMessages = false) }
        }
        return replaced
    }

    /** Forgets the session of [accountId], once the provider has refused it for good. */
    fun forget(accountId: AccountId) {
        store.remove(accountId.toString())
    }
}
