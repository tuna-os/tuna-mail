package app.k9mail.feature.account.edit.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import app.k9mail.feature.account.edit.navigation.AccountEditRoute.IncomingServerSettings
import app.k9mail.feature.account.edit.navigation.AccountEditRoute.OutgoingServerSettings
import app.k9mail.feature.account.edit.ui.server.settings.EditIncomingServerSettingsNavHost
import app.k9mail.feature.account.edit.ui.server.settings.EditOutgoingServerSettingsNavHost
import net.thunderbird.core.ui.navigation.deepLinkComposable

class DefaultAccountEditNavigation : AccountEditNavigation {

    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (AccountEditRoute) -> Unit,
    ) = with(navGraphBuilder) {
        deepLinkComposable<IncomingServerSettings>(
            basePath = IncomingServerSettings.BASE_PATH,
        ) { backStackEntry ->
            val incomingServerSettingsRoute = backStackEntry.toRoute<IncomingServerSettings>()
            val extension = rememberAccountEditExtension(incomingServerSettingsRoute.accountId)

            if (extension != null) {
                extension.ServerSettings(incomingServerSettingsRoute.accountId, onBack) {
                    onFinish(incomingServerSettingsRoute)
                }
            } else {
                EditIncomingServerSettingsNavHost(
                    accountUuid = incomingServerSettingsRoute.accountId,
                    onBack = onBack,
                    onFinish = onFinish,
                )
            }
        }

        deepLinkComposable<OutgoingServerSettings>(
            basePath = OutgoingServerSettings.BASE_PATH,
        ) { backStackEntry ->
            val outgoingServerSettingsRoute = backStackEntry.toRoute<OutgoingServerSettings>()
            val extension = rememberAccountEditExtension(outgoingServerSettingsRoute.accountId)

            if (extension != null) {
                extension.ServerSettings(outgoingServerSettingsRoute.accountId, onBack) {
                    onFinish(outgoingServerSettingsRoute)
                }
            } else {
                EditOutgoingServerSettingsNavHost(
                    accountUuid = outgoingServerSettingsRoute.accountId,
                    onBack = onBack,
                    onFinish = onFinish,
                )
            }
        }
    }
}
