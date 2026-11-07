package org.tunaos.mail.provider

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.k9mail.feature.account.common.domain.AccountDomainContract.AccountStateRepository
import app.k9mail.feature.account.common.domain.entity.AccountState
import app.k9mail.feature.account.setup.navigation.AccountSetupExtension
import kotlinx.collections.immutable.toImmutableList
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sends a provider account from the setup's password step to its provider's sign-in screens, and lets the user pick a
 * provider by hand for an address the automatic configuration does not recognize (a custom domain).
 */
class TunaAccountSetupExtension(
    private val providers: List<TunaMailProvider>,
    private val accountStateRepository: AccountStateRepository,
    private val handoff: TunaSignInHandoff,
) : AccountSetupExtension {
    override fun signInRoute(accountState: AccountState): String? {
        val serverType = accountState.incomingServerSettings?.type ?: return null
        return providers.firstOrNull { it.serverType == serverType }?.let { routeFor(it.serverType) }
    }

    override fun accountTypeRoute(accountState: AccountState): String? =
        ACCOUNT_TYPE_ROUTE.takeIf { providers.isNotEmpty() && !accountState.emailAddress.isNullOrBlank() }

    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onSignedIn: () -> Unit,
        onManualSetup: () -> Unit,
    ) {
        for (provider in providers) {
            navGraphBuilder.composable(route = routeFor(provider.serverType)) {
                val target = remember { setupTarget(provider) }
                provider.SignInScreen(target, onBack, onSignedIn)
            }
        }

        navGraphBuilder.composable(route = ACCOUNT_TYPE_ROUTE) {
            val viewModel = koinViewModel<TunaAccountTypeViewModel>()
            val chosenServerType by viewModel.chosenServerType.collectAsState()
            val provider = providers.firstOrNull { it.serverType == chosenServerType }
            if (provider == null) {
                TunaAccountTypeScreen(
                    providers = providers.toImmutableList(),
                    onProviderClick = viewModel::choose,
                    onOtherClick = {
                        viewModel.chooseOther()
                        onManualSetup()
                    },
                    onBack = onBack,
                )
            } else {
                val target = remember(provider) { setupTarget(provider) }
                provider.SignInScreen(target, onBack = viewModel::clearChoice, onSignedIn = onSignedIn)
            }
        }
    }

    private fun setupTarget(provider: TunaMailProvider) =
        SetupSignInTarget(accountStateRepository, handoff, provider.serverType)

    private fun routeFor(serverType: String) = "tunamail/sign-in/$serverType"

    private companion object {
        const val ACCOUNT_TYPE_ROUTE = "tunamail/account-type"
    }
}
