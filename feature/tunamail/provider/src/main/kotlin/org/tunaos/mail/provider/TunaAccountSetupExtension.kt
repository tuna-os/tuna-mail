package org.tunaos.mail.provider

import androidx.navigation.NavGraphBuilder
import app.k9mail.feature.account.common.domain.entity.AccountState
import app.k9mail.feature.account.setup.navigation.AccountSetupExtension

/** Sends a provider account from the setup's password step to its provider's sign-in screens. */
class TunaAccountSetupExtension(
    private val providers: List<TunaMailProvider>,
) : AccountSetupExtension {
    override fun signInRoute(accountState: AccountState): String? {
        val serverType = accountState.incomingServerSettings?.type ?: return null
        return providers.firstOrNull { it.serverType == serverType }?.let { routeFor(it.serverType) }
    }

    override fun registerRoutes(navGraphBuilder: NavGraphBuilder, onBack: () -> Unit, onSignedIn: () -> Unit) {
        for (provider in providers) {
            provider.registerSignIn(navGraphBuilder, routeFor(provider.serverType), onBack, onSignedIn)
        }
    }

    private fun routeFor(serverType: String) = "tunamail/sign-in/$serverType"
}
