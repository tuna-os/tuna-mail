package app.k9mail.feature.account.edit.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.koin.compose.getKoin

/**
 * Shows an account type's own screens in place of the server settings, for an account type whose servers the app
 * cannot edit (the server settings screens validate IMAP, POP3 and SMTP servers), such as one that signs in through a
 * backend of its own.
 *
 * When an account's incoming or outgoing server settings are opened (from the account settings, or from an
 * authentication-error notification), every extension bound in Koin is asked whether it handles the account. The
 * first that does shows its screens instead.
 */
interface AccountEditExtension {
    /** Whether this extension shows the server settings of the account [accountUuid]. */
    fun handlesServerSettings(accountUuid: String): Boolean

    /** The server settings of the account [accountUuid]. [onFinish] closes them once done; [onBack] abandons them. */
    @Composable
    fun ServerSettings(accountUuid: String, onBack: () -> Unit, onFinish: () -> Unit)
}

/** The bound [AccountEditExtension] that handles the account [accountUuid], or null when the default screens do. */
@Composable
internal fun rememberAccountEditExtension(accountUuid: String): AccountEditExtension? {
    val koin = getKoin()
    return remember(accountUuid) {
        koin.getAll<AccountEditExtension>().firstOrNull { it.handlesServerSettings(accountUuid) }
    }
}
