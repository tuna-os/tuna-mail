package app.k9mail.feature.account.setup.navigation

import androidx.navigation.NavGraphBuilder
import app.k9mail.feature.account.common.domain.entity.AccountState

/**
 * Adds an account type whose sign-in the setup flow cannot check itself (it validates IMAP, POP3 and SMTP servers).
 *
 * After an automatic configuration, every extension bound in Koin is asked whether it claims the account being set up.
 * The first that does takes over with its own sign-in screens, and hands back to the account options once the account
 * is signed in. When the user configures the account by hand instead, the first extension with an account type route
 * shows it before the server settings, so the user can pick one of its account types.
 */
interface AccountSetupExtension {
    /** The route of the sign-in screen for [accountState], or null when the account is not one of this extension's. */
    fun signInRoute(accountState: AccountState): String?

    /**
     * The route of a screen where the user picks one of the extension's account types for [accountState], which the
     * user configures by hand; null to go straight to the server settings.
     */
    fun accountTypeRoute(accountState: AccountState): String? = null

    /**
     * Registers the extension's routes. [onSignedIn] continues the setup with the account options, [onManualSetup] with
     * the server settings (the user picked none of the extension's account types).
     */
    fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onSignedIn: () -> Unit,
        onManualSetup: () -> Unit,
    )
}
