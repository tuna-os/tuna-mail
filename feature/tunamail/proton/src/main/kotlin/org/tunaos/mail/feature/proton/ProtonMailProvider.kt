package org.tunaos.mail.feature.proton

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.fsck.k9.backend.api.Backend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountId
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.proton.ProtonBackend
import org.tunaos.mail.backend.proton.ProtonPollConditions
import org.tunaos.mail.backend.proton.StoredSessionProtonApi
import org.tunaos.mail.feature.proton.ui.ProtonSignInScreen
import org.tunaos.mail.provider.TunaAccountSessions
import org.tunaos.mail.provider.TunaMailProvider
import org.tunaos.proton.ProtonClient
import org.tunaos.proton.ProtonException
import org.tunaos.proton.ProtonSession
import org.tunaos.proton.ProtonSessionStore

const val PROTON_SERVER_TYPE = "proton"

private const val TAG = "ProtonMailProvider"

/** Proton's own address domains. Custom domains on Proton are not recognized by their address. */
internal val PROTON_DOMAINS = setOf("proton.me", "protonmail.com", "protonmail.ch", "pm.me")

/** Proton Mail as a native Tuna Mail account type. */
class ProtonMailProvider(
    private val client: ProtonClient,
    override val backendFactory: BackendFactory,
) : TunaMailProvider {
    override val serverType = PROTON_SERVER_TYPE

    override fun isProviderDomain(domain: String): Boolean = domain in PROTON_DOMAINS

    override fun registerSignIn(
        navGraphBuilder: NavGraphBuilder,
        route: String,
        onBack: () -> Unit,
        onSignedIn: () -> Unit,
    ) {
        navGraphBuilder.composable(route) {
            ProtonSignInScreen(onBack = onBack, onSignedIn = onSignedIn)
        }
    }

    override suspend fun revoke(session: String) {
        if (!isProtonSession(session)) return
        try {
            client.account(ProtonSession(session), ProtonSessionStore { }).revoke()
        } catch (e: ProtonException) {
            Log.w(TAG, e, "Could not revoke the Proton session of a removed account")
        }
    }
}

/** Opens each Proton account's backend on the session its sign-in stored. */
class ProtonBackendFactory(
    private val backendStorageFactory: BackendStorageFactory,
    private val accountManager: LegacyAccountManager,
    private val sessions: TunaAccountSessions,
    private val client: ProtonClient,
    private val pollConditions: ProtonPollConditions,
) : BackendFactory {
    override fun createBackend(accountId: AccountId): Backend {
        val account = accountManager.findById(accountId) ?: error("Account not found: $accountId")
        val email = account.incomingServerSettings.username
        val api = StoredSessionProtonApi(
            loadSession = { sessions.sessionFor(accountId, PROTON_SERVER_TYPE, email) },
            saveSession = { sessions.save(accountId, it) },
            client = client,
        )
        return ProtonBackend(backendStorageFactory.createBackendStorage(accountId), api, pollConditions)
    }
}

/** A Proton session carries its auth `uid` and the salted `key_pass`; a Tuta session has neither. */
internal fun isProtonSession(session: String): Boolean {
    val fields = runCatching { Json.parseToJsonElement(session) as? JsonObject }.getOrNull() ?: return false
    return "uid" in fields && "key_pass" in fields
}
