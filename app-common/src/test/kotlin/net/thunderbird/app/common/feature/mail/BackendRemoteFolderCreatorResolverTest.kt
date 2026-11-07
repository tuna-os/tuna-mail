package net.thunderbird.app.common.feature.mail

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isSameInstanceAs
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.folders.FolderServerId
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.api.folder.RemoteFolderCreationOutcome
import net.thunderbird.backend.api.folder.RemoteFolderCreator
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.fake.FakeLegacyAccountManager
import net.thunderbird.feature.account.AccountId

class BackendRemoteFolderCreatorResolverTest {
    private val accountManager = FakeLegacyAccountManager()
    private val backendCreator = object : RemoteFolderCreator {
        override suspend fun create(folderServerId: FolderServerId, mustCreate: Boolean, folderType: FolderType) =
            Outcome.success(RemoteFolderCreationOutcome.Success.Created)
    }
    private val askedFor = mutableListOf<AccountId>()

    @Test
    fun `an account of another server type gets the creator of its backend`() = runTest {
        val account = accountOfType("native")
        val resolver = RemoteFolderCreatorResolver(accountManager, FakeImapRemoteFolderCreatorFactory()) {
            askedFor += it
            backendCreator
        }

        val result = resolver.create(account.id)

        assertThat(result).isSameInstanceAs(backendCreator)
        assertThat(askedFor).containsExactly(account.id)
    }

    @Test
    fun `a backend that creates no folders gets the no-op creator`() = runTest {
        val account = accountOfType("native")
        val resolver = RemoteFolderCreatorResolver(accountManager, FakeImapRemoteFolderCreatorFactory()) { null }

        val result = resolver.create(account.id)

        assertThat(result).isSameInstanceAs(NoOpRemoteFolderCreator)
    }

    private fun accountOfType(type: String) = FakeData.legacyAccount.copy(
        incomingServerSettings = FakeData.legacyAccount.incomingServerSettings.copy(type = type),
    ).also { accountManager.createSync(it) }
}
