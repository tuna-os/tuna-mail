package org.tunaos.mail.feature.tuta

import androidx.compose.runtime.Composable
import com.fsck.k9.backend.api.Backend
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountId
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.tuta.TutaApi
import org.tunaos.mail.backend.tuta.TutaBackend
import org.tunaos.mail.backend.tuta.TutaNotices
import org.tunaos.mail.backend.tuta.TutaRecipientPasswords
import org.tunaos.mail.backend.tuta.TutaSessionSource
import org.tunaos.mail.provider.TunaAccountSessions
import org.tunaos.mail.provider.TunaMailProvider
import org.tunaos.mail.provider.TunaSignInTarget
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaSession

/** The `ServerSettings.type` of Tuta accounts. */
const val TUTA_SERVER_TYPE = "tuta"

/** Tuta, as one of Tuna Mail's native providers. */
class TutaProvider(
    override val backendFactory: BackendFactory,
    private val api: TutaApi,
) : TunaMailProvider {
    override val serverType: String = TUTA_SERVER_TYPE

    override val displayName: String = "Tuta"

    override fun isProviderDomain(domain: String): Boolean = isTutaDomain(domain)

    override fun isProviderMailServer(host: String): Boolean = host == TUTA_MAIL_SERVER

    @Composable
    override fun SignInScreen(target: TunaSignInTarget, onBack: () -> Unit, onSignedIn: () -> Unit) {
        TutaSignInScreen(target = target, onBack = onBack, onSignedIn = onSignedIn)
    }

    override suspend fun revoke(session: String) {
        val tutaSession = try {
            TutaSession.fromJson(session)
        } catch (_: TutaException) {
            return // Not a Tuta session.
        }
        try {
            api.revoke(tutaSession)
        } catch (e: TutaException) {
            Log.w(TAG, "Could not revoke a Tuta session: %s", e.wireCode)
        }
    }

    private companion object {
        const val TAG = "TutaProvider"
    }
}

/** Tuta's own mail domains. An address on a custom domain is recognized by its MX record ([TUTA_MAIL_SERVER]). */
fun isTutaDomain(domain: String): Boolean {
    val name = domain.lowercase().trimEnd('.')
    return name in TUTA_DOMAINS || TUTANOTA_DOMAIN.matches(name)
}

private val TUTA_DOMAINS = setOf("tuta.com", "tuta.io", "tutamail.com", "keemail.me")
private val TUTANOTA_DOMAIN = Regex("tutanota\\.[a-z]{2,}")

/** The mail server a custom domain hosted at Tuta names in its MX record. */
private const val TUTA_MAIL_SERVER = "mail.tutanota.de"

/** Creates the backends of Tuta accounts, each with its session from the [TunaAccountSessions]. */
class TutaBackendFactory(
    private val accountManager: LegacyAccountManager,
    private val backendStorageFactory: BackendStorageFactory,
    private val api: TutaApi,
    private val sessions: TunaAccountSessions,
    private val flagLabelNotifier: TutaFlagLabelNotifier,
    private val passwords: TutaRecipientPasswords,
) : BackendFactory {
    override fun createBackend(accountId: AccountId): Backend {
        val account = accountManager.findById(accountId) ?: error("Account not found: $accountId")
        return TutaBackend(
            backendStorage = backendStorageFactory.createBackendStorage(accountId),
            api = api,
            sessions = sessions.tutaSessionSource(accountId, account.incomingServerSettings.username),
            notices = TutaNotices { flagLabelNotifier.show(accountId, account.name ?: account.email) },
            passwords = passwords,
        )
    }
}

/** The session of the Tuta account [accountId] (signed in as [email]), kept by these [TunaAccountSessions]. */
internal fun TunaAccountSessions.tutaSessionSource(accountId: AccountId, email: String) = object : TutaSessionSource {
    override fun current(): String? = sessionFor(accountId, TUTA_SERVER_TYPE, email)

    override fun invalidate() = forget(accountId)
}
