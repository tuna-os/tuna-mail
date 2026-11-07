package org.tunaos.mail.feature.tuta

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.fsck.k9.backend.api.Backend
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountId
import net.thunderbird.legacy.logging.Log
import org.koin.compose.viewmodel.koinViewModel
import org.tunaos.mail.backend.tuta.TutaApi
import org.tunaos.mail.backend.tuta.TutaBackend
import org.tunaos.mail.backend.tuta.TutaSessionSource
import org.tunaos.mail.provider.TunaAccountSessions
import org.tunaos.mail.provider.TunaMailProvider
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

    override fun isProviderDomain(domain: String): Boolean = isTutaDomain(domain)

    override fun registerSignIn(
        navGraphBuilder: NavGraphBuilder,
        route: String,
        onBack: () -> Unit,
        onSignedIn: () -> Unit,
    ) {
        navGraphBuilder.composable(route = route) {
            TutaSignInScreen(
                onBack = onBack,
                onSignedIn = onSignedIn,
                viewModel = koinViewModel<TutaSignInViewModel>(),
            )
        }
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
            Log.w(TAG, "Could not revoke the session of a removed Tuta account: %s", e.wireCode)
        }
    }

    private companion object {
        const val TAG = "TutaProvider"
    }
}

/** Tuta's own mail domains; addresses on a custom domain are set up as Tuta accounts only through these. */
fun isTutaDomain(domain: String): Boolean {
    val name = domain.lowercase().trimEnd('.')
    return name in TUTA_DOMAINS || TUTANOTA_DOMAIN.matches(name)
}

private val TUTA_DOMAINS = setOf("tuta.com", "tuta.io", "tutamail.com", "keemail.me")
private val TUTANOTA_DOMAIN = Regex("tutanota\\.[a-z]{2,}")

/** Creates the backends of Tuta accounts, each with its session from the [TunaAccountSessions]. */
class TutaBackendFactory(
    private val accountManager: LegacyAccountManager,
    private val backendStorageFactory: BackendStorageFactory,
    private val api: TutaApi,
    private val sessions: TunaAccountSessions,
) : BackendFactory {
    override fun createBackend(accountId: AccountId): Backend {
        val account = accountManager.findById(accountId) ?: error("Account not found: $accountId")
        val email = account.incomingServerSettings.username
        val sessionSource = object : TutaSessionSource {
            override fun current(): String? = sessions.sessionFor(accountId, TUTA_SERVER_TYPE, email)

            override fun invalidate() = sessions.forget(accountId)
        }
        return TutaBackend(backendStorageFactory.createBackendStorage(accountId), api, sessionSource)
    }
}
