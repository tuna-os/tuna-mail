package com.fsck.k9.preferences

import app.k9mail.legacy.di.DI.get
import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.single
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.account.FakeStorageBackedAccountManager
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.thunderbird.core.android.account.AccountDefaultsProvider
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.preference.storage.StorageProvider
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.account.storage.legacy.AccountStorageHandler
import net.thunderbird.feature.mail.folder.api.data.repository.FolderQueryRepository
import org.junit.Before
import org.junit.Test
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.robolectric.RuntimeEnvironment

/**
 * Accounts whose server type is not IMAP, POP3 or SMTP (an account type with a backend of its own, which signs in by
 * itself) are exported by their type and imported without asking for a password.
 */
class OwnBackendAccountImportExportTest : K9RobolectricTest() {
    private val accountManagerModule = module {
        single<LegacyAccountManager> {
            FakeStorageBackedAccountManager(
                storageProvider = get<StorageProvider>(),
                accountStorageHandler = get<AccountStorageHandler>(),
                accountDefaultsProvider = get<AccountDefaultsProvider>(),
            )
        }
    }
    private lateinit var settingsImporter: SettingsImporter

    @Before
    fun setUp() {
        loadKoinModules(accountManagerModule)
        settingsImporter = SettingsImporter(
            settingsFileParser = get(),
            generalSettingsValidator = get(),
            accountSettingsValidator = get(),
            generalSettingsUpgrader = get(),
            accountSettingsWriter = get(),
            accountSettingsUpgrader = get(),
            generalSettingsWriter = get(),
            unifiedInboxConfigurator = mock(),
        )
    }

    @Test
    fun `an account with a backend of its own is imported without a password and exported by its type`() =
        runTest(UnconfinedTestDispatcher()) {
            val accountUuid = UUID.randomUUID().toString()
            val accountManager = get<LegacyAccountManager>()
            accountManager.findAll() // Initialize the account cache before importing.

            val results = settingsImporter.importSettings(
                settingsFile(accountUuid).byteInputStream(),
                globalSettings = false,
                accountUuids = listOf(accountUuid),
            )

            assertThat(results.importedAccounts).single().all {
                prop(AccountDescriptionPair::incomingPasswordNeeded).isFalse()
                prop(AccountDescriptionPair::outgoingPasswordNeeded).isFalse()
                prop(AccountDescriptionPair::authorizationNeeded).isFalse()
            }
            val account = requireNotNull(accountManager.findById(AccountIdFactory.of(accountUuid)))
            assertThat(account.incomingServerSettings.type).isEqualTo("demo")
            assertThat(account.outgoingServerSettings.type).isEqualTo("demo")
            assertThat(account.incomingServerSettings.password).isNull()

            val exported = export(accountManager, accountUuid)

            assertThat(exported).contains("<incoming-server type=\"DEMO\">")
            assertThat(exported).contains("<outgoing-server type=\"DEMO\">")
            assertThat(exported).doesNotContain("<password>")
        }

    @Test
    fun `export names are upper case and read back as the server type`() {
        assertThat(ServerTypeConverter.fromServerSettingsType("imap")).isEqualTo("IMAP")
        assertThat(ServerTypeConverter.fromServerSettingsType("demo")).isEqualTo("DEMO")
        assertThat(ServerTypeConverter.toServerSettingsType("DEMO")).isEqualTo("demo")
        assertThat(ServerTypeConverter.usesPassword("smtp")).isTrue()
        assertThat(ServerTypeConverter.usesPassword("demo")).isFalse()
    }

    private fun export(accountManager: LegacyAccountManager, accountUuid: String): String {
        val output = ByteArrayOutputStream()
        SettingsExporter(
            contentResolver = RuntimeEnvironment.getApplication().contentResolver,
            accountManager = accountManager,
            storageProvider = get(),
            folderSettingsProvider = get(),
            folderQueryRepository = get<FolderQueryRepository>(),
            notificationSettingsUpdater = mock(),
            filePrefixProvider = mock(),
        ).exportPreferences(output, includeGlobals = false, accountUuids = setOf(accountUuid), includePasswords = true)
        return output.toString()
    }

    private fun settingsFile(accountUuid: String) =
        """
        <k9settings format="1" version="1">
          <accounts>
            <account uuid="$accountUuid">
              <name>Account</name>
              <incoming-server type="DEMO">
                <host>example.test</host>
                <port>443</port>
                <connection-security>SSL_TLS_REQUIRED</connection-security>
                <authentication-type>PLAIN</authentication-type>
                <username>user@example.test</username>
              </incoming-server>
              <outgoing-server type="DEMO">
                <host>example.test</host>
                <port>443</port>
                <connection-security>SSL_TLS_REQUIRED</connection-security>
                <authentication-type>PLAIN</authentication-type>
                <username>user@example.test</username>
              </outgoing-server>
              <settings>
                <value key="a">b</value>
              </settings>
              <identities>
                <identity>
                  <email>user@example.test</email>
                </identity>
              </identities>
            </account>
          </accounts>
        </k9settings>
        """.trimIndent()
}
