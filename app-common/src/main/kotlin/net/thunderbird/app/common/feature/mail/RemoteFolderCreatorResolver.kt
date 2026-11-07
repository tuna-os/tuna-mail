package net.thunderbird.app.common.feature.mail

import kotlinx.coroutines.flow.firstOrNull
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.backend.imap.ImapRemoteFolderCreatorFactory
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.feature.account.AccountId

/**
 * Resolves the correct [RemoteFolderCreator] implementation based on the account's incoming server type.
 *
 * Any other server type gets the creator [backendFolderCreator] returns for the account, which is how a backend
 * that implements [RemoteFolderCreator] itself creates its own folders.
 */
class RemoteFolderCreatorResolver(
    private val accountManager: LegacyAccountManager,
    private val imapFactory: ImapRemoteFolderCreatorFactory,
    private val backendFolderCreator: (AccountId) -> RemoteFolderCreator? = { null },
) : RemoteFolderCreator.Factory {
    override suspend fun create(accountId: AccountId): RemoteFolderCreator {
        val account = accountManager.observeById(accountId).firstOrNull()
            ?: error("Account not found: $accountId")

        return when (account.incomingServerSettings.type) {
            Protocols.IMAP -> imapFactory.create(accountId)
            else -> backendFolderCreator(accountId) ?: NoOpRemoteFolderCreator
        }
    }
}
