package org.tunaos.mail.provider

import androidx.navigation.NavGraphBuilder
import app.k9mail.autodiscovery.api.AutoDiscoveryResult
import app.k9mail.feature.account.common.data.InMemoryAccountStateRepository
import app.k9mail.feature.account.common.domain.entity.AccountState
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import kotlinx.coroutines.test.runTest
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.core.android.account.Identity
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.fake.FakeLegacyAccountManager
import net.thunderbird.core.common.mail.toUserEmailAddress
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.account.storage.profile.AvatarDto
import net.thunderbird.feature.account.storage.profile.AvatarTypeDto
import net.thunderbird.feature.account.storage.profile.ProfileDto
import org.junit.Test
import org.tunaos.mail.backend.session.TunaSessionStore

class TunaProviderTest {
    private val provider = FakeProvider()
    private val store = InMemorySessionStore()
    private val handoff = TunaSignInHandoff()

    @Test
    fun `addresses at a provider domain are discovered as that provider's accounts`() = runTest {
        val discovery = TunaProviderAutoDiscovery(listOf(provider))

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
    }

    @Test
    fun `other addresses are left to the other discoveries`() = runTest {
        val discovery = TunaProviderAutoDiscovery(listOf(provider))

        val result = discovery.initDiscovery("bob@elsewhere.test".toUserEmailAddress()).single().run()

        assertThat(result).isEqualTo(AutoDiscoveryResult.NoUsableSettingsFound)
    }

    @Test
    fun `the setup extension claims only accounts of a bound provider`() {
        val extension = TunaAccountSetupExtension(listOf(provider))

        assertThat(extension.signInRoute(accountState(type = "fake"))).isEqualTo("tunamail/sign-in/fake")
        assertThat(extension.signInRoute(accountState(type = "imap"))).isNull()
        assertThat(extension.signInRoute(AccountState())).isNull()
    }

    @Test
    fun `completing a sign-in removes the password from the setup and parks the session`() {
        val repository = InMemoryAccountStateRepository(accountState(type = "fake"))

        handoff.complete(repository, "fake", "Alice@Example.test", SESSION)

        assertThat(repository.getState().incomingServerSettings?.password).isNull()
        assertThat(repository.getState().outgoingServerSettings?.password).isNull()
        assertThat(handoff.take("fake", "alice@example.test")).isEqualTo(SESSION)
        assertThat(handoff.take("fake", "alice@example.test")).isNull()
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

    @Test
    fun `backend factories are keyed by server type`() {
        assertThat(provider.serverType).isEqualTo("fake")
        assertThat(provider.isProviderDomain("elsewhere.test")).isFalse()
    }

    private class FakeProvider : TunaMailProvider {
        val revoked = mutableListOf<String>()
        override val serverType = "fake"
        override val backendFactory = object : BackendFactory {
            override fun createBackend(accountId: AccountId) = error("not used")
        }

        override fun isProviderDomain(domain: String) = domain == "example.test"

        override fun registerSignIn(
            navGraphBuilder: NavGraphBuilder,
            route: String,
            onBack: () -> Unit,
            onSignedIn: () -> Unit,
        ) = Unit

        override suspend fun revoke(session: String) {
            revoked += session
        }
    }

    private class InMemorySessionStore : TunaSessionStore {
        private val sessions = mutableMapOf<String, String>()
        override fun get(accountUuid: String) = sessions[accountUuid]
        override fun put(accountUuid: String, session: String) {
            sessions[accountUuid] = session
        }

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

        fun legacyAccount(id: AccountId) = LegacyAccount(
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
            incomingServerSettings = serverSettings(),
            outgoingServerSettings = serverSettings(),
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
