package org.tunaos.mail.provider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.k9mail.feature.account.edit.navigation.AccountEditExtension
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory

/**
 * Shows a provider account's sign-in where the app would show its server settings: from the account settings and from
 * the "authentication failed" notification. Signing in again replaces the account's session and keeps the account and
 * its mail ([AccountSignInTarget]).
 */
class TunaAccountEditExtension(
    private val providers: List<TunaMailProvider>,
    private val accountManager: LegacyAccountManager,
    private val sessions: TunaAccountSessions,
    private val backendReset: TunaBackendReset,
) : AccountEditExtension {
    override fun handlesServerSettings(accountUuid: String): Boolean = signInTarget(accountUuid) != null

    @Composable
    override fun ServerSettings(accountUuid: String, onBack: () -> Unit, onFinish: () -> Unit) {
        val target = remember(accountUuid) { signInTarget(accountUuid) }
        if (target == null) {
            // The account was removed while its settings were open.
            LaunchedEffect(Unit) { onBack() }
        } else {
            target.provider.SignInScreen(target.target, onBack, onFinish)
        }
    }

    private fun signInTarget(accountUuid: String): ProviderTarget? {
        val accountId = AccountIdFactory.of(accountUuid)
        val account = accountManager.findById(accountId) ?: return null
        return providers.firstOrNull { it.serverType == account.incomingServerSettings.type }?.let { provider ->
            val target = AccountSignInTarget(
                accountId = accountId,
                email = account.incomingServerSettings.username,
                provider = provider,
                sessions = sessions,
                backendReset = backendReset,
            )
            ProviderTarget(provider, target)
        }
    }

    private class ProviderTarget(val provider: TunaMailProvider, val target: TunaSignInTarget)
}
