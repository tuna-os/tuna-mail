package org.tunaos.mail.provider

import androidx.lifecycle.ViewModel
import app.k9mail.feature.account.common.domain.AccountDomainContract.AccountStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The account type the user picks by hand during setup, for an address the automatic configuration did not recognize.
 *
 * Picking a provider makes the account being set up one of the provider's accounts; stepping back or picking another
 * kind of account restores the server settings the setup had before, so the manual server settings start from them.
 */
class TunaAccountTypeViewModel(
    private val accountStateRepository: AccountStateRepository,
) : ViewModel() {
    private val original = accountStateRepository.getState()

    private val mutableChosenServerType = MutableStateFlow<String?>(null)

    /** The server type of the provider picked, or null while the user is choosing. */
    val chosenServerType: StateFlow<String?> = mutableChosenServerType.asStateFlow()

    fun choose(provider: TunaMailProvider) {
        val email = original.emailAddress.orEmpty().trim()
        val settings = tunaServerSettings(
            serverType = provider.serverType,
            host = email.substringAfterLast('@').lowercase(),
            email = email,
            password = original.incomingServerSettings?.password,
        )
        accountStateRepository.setState(
            accountStateRepository.getState().copy(
                incomingServerSettings = settings,
                outgoingServerSettings = settings,
            ),
        )
        mutableChosenServerType.value = provider.serverType
    }

    /** The user left the provider's sign-in for the list of account types. */
    fun clearChoice() {
        restore()
        mutableChosenServerType.value = null
    }

    /** The user picked an account type of the app's own (IMAP or POP3). */
    fun chooseOther() = restore()

    private fun restore() {
        accountStateRepository.setState(
            accountStateRepository.getState().copy(
                incomingServerSettings = original.incomingServerSettings,
                outgoingServerSettings = original.outgoingServerSettings,
            ),
        )
    }
}
