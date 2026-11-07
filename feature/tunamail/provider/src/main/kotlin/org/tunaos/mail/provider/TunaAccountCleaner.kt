package org.tunaos.mail.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.thunderbird.core.android.account.LegacyAccountManager
import org.tunaos.mail.backend.session.TunaSessionStore

/**
 * Closes and forgets the sessions of removed accounts.
 *
 * Upstream's account removal knows nothing of the session store, so this watches the account list instead: whenever
 * it changes (and once at start), every saved session whose account no longer exists is revoked on the server and
 * deleted.
 */
class TunaAccountCleaner(
    private val store: TunaSessionStore,
    private val accountManager: LegacyAccountManager,
    private val providers: List<TunaMailProvider>,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            accountManager.observeAll().collectLatest { removeOrphanedSessions() }
        }
    }

    /** Revokes and deletes the sessions of accounts that no longer exist. */
    suspend fun removeOrphanedSessions() {
        // The current list, not the one the flow emitted: an account created since then may already have a session.
        val accountUuids = accountManager.findAll().map { it.id.toString() }.toSet()
        for (orphan in store.accountUuids() - accountUuids) {
            val session = store.get(orphan)
            store.remove(orphan)
            if (session != null) {
                for (provider in providers) provider.revoke(session)
            }
        }
    }
}
