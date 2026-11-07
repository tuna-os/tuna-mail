package org.tunaos.mail.provider

import androidx.compose.runtime.Composable
import app.k9mail.autodiscovery.api.AutoDiscoveryResult
import app.k9mail.feature.account.common.data.InMemoryAccountStateRepository
import app.k9mail.feature.account.common.domain.entity.AccountState
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.core.android.account.Identity
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.fake.FakeLegacyAccountManager
import net.thunderbird.core.common.mail.toUserEmailAddress
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.account.storage.profile.AvatarDto
import net.thunderbird.feature.account.storage.profile.AvatarTypeDto
import net.thunderbird.feature.account.storage.profile.ProfileDto
import net.thunderbird.legacy.logging.Log
import org.junit.Before
import org.junit.Test
import org.tunaos.mail.backend.session.TunaSessionStore

class TunaProviderTest {
    private val provider = FakeProvider()
    private val store = InMemorySessionStore()
    private val handoff = TunaSignInHandoff()
    private val mxLookup = FakeMxLookup()
    private val discovery = TunaProviderAutoDiscovery(listOf(provider), mxLookup)

    @Before
    fun setUp() {
        Log.logger = TestLogger()
    }

    @Test
    fun `addresses at a provider domain are discovered as that provider's accounts`() = runTest {
        val result = discovery.initDiscovery("Alice@Example.TEST".toUserEmailAddress()).single().run()

        assertThat(result).isInstanceOf<AutoDiscoveryResult.Settings>().prop(AutoDiscoveryResult.Settings::isTrusted)
            .isTrue()
        val settings = (result as AutoDiscoveryResult.Settings).incomingServerSettings as TunaDirectServerSettings
        assertThat(settings.toServerSettings("secret")).isEqualTo(
            tunaServerSettings(
                serverType = "fake",
                host = "example.test",
                email = "Alice@Example.TEST",
                password = "secret",
            ),
        )
        assertThat(mxLookup.lookups).isEmpty()
    }

    @Test
    fun `other addresses are left to the other discoveries`() = runTest {
        mxLookup.hosts["elsewhere.test"] = MxHosts(listOf("mx.elsewhere.test"), isTrusted = true)

        val result = discovery.initDiscovery("bob@elsewhere.test".toUserEmailAddress()).single().run()

        assertThat(result).isEqualTo(AutoDiscoveryResult.NoUsableSettingsFound)
        assertThat(mxLookup.lookups).containsExactly("elsewhere.test")
    }

    @Test
    fun `a custom domain whose MX record names the provider's mail server is the provider's`() = runTest {
        mxLookup.hosts["custom.test"] = MxHosts(listOf("backup.custom.test", "mx.example.test"), isTrusted = true)

        val result = discovery.initDiscovery("bob@custom.test".toUserEmailAddress()).single().run()

        assertThat(result).isInstanceOf<AutoDiscoveryResult.Settings>().prop(AutoDiscoveryResult.Settings::isTrusted)
            .isTrue()
        val settings = (result as AutoDiscoveryResult.Settings).incomingServerSettings as TunaDirectServerSettings
        assertThat(settings).isEqualTo(TunaDirectServerSettings("fake", "custom.test", "bob@custom.test"))
    }

    @Test
    fun `an unsigned MX answer gives a result the user has to confirm`() = runTest {
        mxLookup.hosts["custom.test"] = MxHosts(listOf("mx.example.test"), isTrusted = false)

        val result = discovery.initDiscovery("bob@custom.test".toUserEmailAddress()).single().run()

        assertThat(result).isInstanceOf<AutoDiscoveryResult.Settings>().prop(AutoDiscoveryResult.Settings::isTrusted)
            .isFalse()
    }

    @Test
    fun `a failed MX lookup leaves the address to the other discoveries`() = runTest {
        mxLookup.failure = IOException("no network")

        val result = discovery.initDiscovery("bob@custom.test".toUserEmailAddress()).single().run()

        assertThat(result).isEqualTo(AutoDiscoveryResult.NoUsableSettingsFound)
    }

    @Test
    fun `the setup extension claims only accounts of a bound provider`() {
        val extension = setupExtension(InMemoryAccountStateRepository())

        assertThat(extension.signInRoute(accountState(type = "fake"))).isEqualTo("tunamail/sign-in/fake")
        assertThat(extension.signInRoute(accountState(type = "imap"))).isNull()
        assertThat(extension.signInRoute(AccountState())).isNull()
    }

    @Test
    fun `a manual setup first asks which kind of account the address is`() {
        val extension = setupExtension(InMemoryAccountStateRepository())

        assertThat(extension.accountTypeRoute(AccountState(emailAddress = EMAIL))).isEqualTo("tunamail/account-type")
        assertThat(extension.accountTypeRoute(AccountState())).isNull()
        assertThat(
            TunaAccountSetupExtension(emptyList(), InMemoryAccountStateRepository(), handoff)
                .accountTypeRoute(AccountState(emailAddress = EMAIL)),
        ).isNull()
    }

    @Test
    fun `picking a provider by hand makes the account the provider's, stepping back restores the setup`() {
        val imap = imapSettings()
        val repository = InMemoryAccountStateRepository(
            AccountState(
                emailAddress = "Bob@Custom.test",
                incomingServerSettings = imap,
                outgoingServerSettings = imap,
            ),
        )
        val viewModel = TunaAccountTypeViewModel(repository)

        viewModel.choose(provider)

        assertThat(viewModel.chosenServerType.value).isEqualTo("fake")
        assertThat(repository.getState().incomingServerSettings).isEqualTo(
            tunaServerSettings("fake", "custom.test", "Bob@Custom.test", "secret"),
        )
        assertThat(repository.getState().outgoingServerSettings?.type).isEqualTo("fake")

        viewModel.clearChoice()

        assertThat(viewModel.chosenServerType.value).isNull()
        assertThat(repository.getState().incomingServerSettings).isEqualTo(imap)
        assertThat(repository.getState().outgoingServerSettings).isEqualTo(imap)
    }

    @Test
    fun `picking another kind of account keeps the setup's own settings`() {
        val repository = InMemoryAccountStateRepository(AccountState(emailAddress = EMAIL))
        val viewModel = TunaAccountTypeViewModel(repository)
        viewModel.choose(provider)

        viewModel.chooseOther()

        assertThat(repository.getState().incomingServerSettings).isNull()
        assertThat(repository.getState().outgoingServerSettings).isNull()
    }

    @Test
    fun `completing a sign-in removes the password from the setup and parks the session`() = runTest {
        val repository = InMemoryAccountStateRepository(accountState(type = "fake"))

        SetupSignInTarget(repository, handoff, "fake").complete(SESSION)

        assertThat(repository.getState().incomingServerSettings?.password).isNull()
        assertThat(repository.getState().outgoingServerSettings?.password).isNull()
        assertThat(handoff.take("fake", "alice@example.test")).isEqualTo(SESSION)
        assertThat(handoff.take("fake", "alice@example.test")).isNull()
    }

    @Test
    fun `the setup target reads the address and password from the setup`() {
        val target = SetupSignInTarget(InMemoryAccountStateRepository(accountState(type = "fake")), handoff, "fake")

        assertThat(target.email).isEqualTo(EMAIL)
        assertThat(target.password).isEqualTo("secret")
    }

    @Test
    fun `the first session lookup adopts the parked session and stops sent mail uploads`() {
        val accountId = AccountIdFactory.create()
        val accountManager = FakeLegacyAccountManager(listOf(legacyAccount(accountId)))
        val sessions = TunaAccountSessions(store, handoff, accountManager)
        handoff.complete(InMemoryAccountStateRepository(accountState(type = "fake")), "fake", EMAIL, SESSION)

        assertThat(sessions.sessionFor(accountId, "fake", EMAIL)).isEqualTo(SESSION)
        assertThat(sessions.sessionFor(accountId, "fake", EMAIL)).isEqualTo(SESSION)

        assertThat(store.get(accountId.toString())).isEqualTo(SESSION)
        assertThat(accountManager.findById(accountId)?.isUploadSentMessages).isEqualTo(false)
    }

    @Test
    fun `an account without a session has none`() {
        val accountId = AccountIdFactory.create()
        val sessions = TunaAccountSessions(store, handoff, FakeLegacyAccountManager(listOf(legacyAccount(accountId))))

        assertThat(sessions.sessionFor(accountId, "fake", EMAIL)).isNull()
    }

    @Test
    fun `signing an account in again replaces its session, reopens its backend and revokes the old session`() =
        runTest {
            val accountId = AccountIdFactory.create()
            val accountManager = FakeLegacyAccountManager(listOf(legacyAccount(accountId)))
            val reset = mutableListOf<AccountId>()
            store.put(accountId.toString(), "old-session")
            val target = AccountSignInTarget(
                accountId = accountId,
                email = EMAIL,
                provider = provider,
                sessions = TunaAccountSessions(store, handoff, accountManager),
                backendReset = { reset += it },
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            target.complete(SESSION)

            assertThat(store.get(accountId.toString())).isEqualTo(SESSION)
            assertThat(reset).containsExactly(accountId)
            assertThat(provider.revoked).containsExactly("old-session")
            assertThat(accountManager.findById(accountId)?.isUploadSentMessages).isEqualTo(false)
        }

    @Test
    fun `signing in an account that had no session revokes nothing`() = runTest {
        val accountId = AccountIdFactory.create()
        val target = AccountSignInTarget(
            accountId = accountId,
            email = EMAIL,
            provider = provider,
            sessions = TunaAccountSessions(store, handoff, FakeLegacyAccountManager(listOf(legacyAccount(accountId)))),
            backendReset = {},
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        target.complete(SESSION)

        assertThat(store.get(accountId.toString())).isEqualTo(SESSION)
        assertThat(provider.revoked).isEmpty()
        assertThat(target.password).isEqualTo("")
    }

    @Test
    fun `the server settings of a provider account are its sign-in`() {
        val providerAccount = AccountIdFactory.create()
        val imapAccount = AccountIdFactory.create()
        val accountManager = FakeLegacyAccountManager(
            listOf(legacyAccount(providerAccount), legacyAccount(imapAccount, imapSettings())),
        )
        val extension = TunaAccountEditExtension(
            providers = listOf(provider),
            accountManager = accountManager,
            sessions = TunaAccountSessions(store, handoff, accountManager),
            backendReset = {},
        )

        assertThat(extension.handlesServerSettings(providerAccount.toString())).isTrue()
        assertThat(extension.handlesServerSettings(imapAccount.toString())).isFalse()
        assertThat(extension.handlesServerSettings(AccountIdFactory.create().toString())).isFalse()
    }

    @Test
    fun `the sessions of removed accounts are revoked and deleted`() = runTest {
        val kept = AccountIdFactory.create()
        val removed = AccountIdFactory.create()
        store.put(kept.toString(), "kept-session")
        store.put(removed.toString(), "removed-session")
        val cleaner = TunaAccountCleaner(store, FakeLegacyAccountManager(listOf(legacyAccount(kept))), listOf(provider))

        cleaner.removeOrphanedSessions()

        assertThat(store.accountUuids()).isEqualTo(setOf(kept.toString()))
        assertThat(provider.revoked).containsExactly("removed-session")
    }

    private fun setupExtension(repository: InMemoryAccountStateRepository) =
        TunaAccountSetupExtension(listOf(provider), repository, handoff)

    private class FakeProvider : TunaMailProvider {
        val revoked = mutableListOf<String>()
        override val serverType = "fake"
        override val displayName = "Fake"
        override val backendFactory = object : BackendFactory {
            override fun createBackend(accountId: AccountId) = error("not used")
        }

        override fun isProviderDomain(domain: String) = domain == "example.test"

        override fun isProviderMailServer(host: String) = host == "mx.example.test"

        @Composable
        override fun SignInScreen(target: TunaSignInTarget, onBack: () -> Unit, onSignedIn: () -> Unit) = Unit

        override suspend fun revoke(session: String) {
            revoked += session
        }
    }

    private class FakeMxLookup : TunaMxLookup {
        val hosts = mutableMapOf<String, MxHosts>()
        val lookups = mutableListOf<String>()
        var failure: IOException? = null

        override fun lookup(domain: String): MxHosts {
            lookups += domain
            failure?.let { throw it }
            return hosts[domain] ?: MxHosts(emptyList(), isTrusted = false)
        }
    }

    private class InMemorySessionStore : TunaSessionStore {
        private val sessions = mutableMapOf<String, String>()
        override fun get(accountUuid: String) = sessions[accountUuid]
        override fun put(accountUuid: String, session: String) {
            sessions[accountUuid] = session
        }

        override fun replace(accountUuid: String, session: String): String? = sessions.put(accountUuid, session)

        override fun remove(accountUuid: String) {
            sessions.remove(accountUuid)
        }

        override fun accountUuids() = sessions.keys.toSet()
    }

    private companion object {
        const val EMAIL = "alice@example.test"
        const val SESSION = "{\"session\":1}"

        fun accountState(type: String) = AccountState(
            emailAddress = EMAIL,
            incomingServerSettings = tunaServerSettings(type, "example.test", EMAIL, "secret"),
            outgoingServerSettings = tunaServerSettings(type, "example.test", EMAIL, "secret"),
        )

        fun imapSettings() = ServerSettings(
            type = "imap",
            host = "imap.custom.test",
            port = 993,
            connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
            authenticationType = AuthType.PLAIN,
            username = EMAIL,
            password = "secret",
            clientCertificateAlias = null,
        )

        fun legacyAccount(id: AccountId, settings: ServerSettings = serverSettings()) = LegacyAccount(
            id = id,
            name = EMAIL,
            email = EMAIL,
            profile = ProfileDto(
                id = id,
                name = EMAIL,
                color = 0,
                avatar = AvatarDto(
                    accountId = id,
                    avatarType = AvatarTypeDto.MONOGRAM,
                    avatarMonogram = "al",
                    avatarImageUri = null,
                    avatarIconName = null,
                ),
            ),
            identities = listOf(Identity(email = EMAIL)),
            isUploadSentMessages = true,
            incomingServerSettings = settings,
            outgoingServerSettings = settings,
        )

        private fun serverSettings() = ServerSettings(
            type = "fake",
            host = "example.test",
            port = 443,
            connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
            authenticationType = AuthType.PLAIN,
            username = EMAIL,
            password = null,
            clientCertificateAlias = null,
        )
    }
}
