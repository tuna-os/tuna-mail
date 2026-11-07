package org.tunaos.mail.feature.proton.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.k9mail.feature.account.common.domain.AccountDomainContract.AccountStateRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.tunaos.mail.feature.proton.HumanVerificationMessage
import org.tunaos.mail.feature.proton.PROTON_SERVER_TYPE
import org.tunaos.mail.feature.proton.ProtonSignIn
import org.tunaos.mail.feature.proton.ProtonSignInState
import org.tunaos.mail.feature.proton.ProtonSignInStep
import org.tunaos.mail.provider.TunaSignInHandoff

internal sealed interface ProtonSignInEffect {
    data object SignedIn : ProtonSignInEffect
    data object Back : ProtonSignInEffect
}

/**
 * Drives [ProtonSignIn] for the account being set up: the address and password come from the
 * setup's password step and are tried at once; Proton then asks for whatever else it needs.
 */
internal class ProtonSignInViewModel(
    private val signIn: ProtonSignIn,
    private val accountStateRepository: AccountStateRepository,
    private val handoff: TunaSignInHandoff,
) : ViewModel() {
    val state: StateFlow<ProtonSignInState> = signIn.state

    /** Buffered, so an effect of the sign-in started in `init` waits for the screen to collect it. */
    private val effectChannel = Channel<ProtonSignInEffect>(Channel.BUFFERED)
    val effects: Flow<ProtonSignInEffect> = effectChannel.receiveAsFlow()

    init {
        val settings = accountStateRepository.getState().incomingServerSettings
        signIn.setEmail(settings?.username.orEmpty())
        signIn.setPassword(settings?.password.orEmpty())
        if (signIn.state.value.password.isNotEmpty()) submit()
    }

    fun onEvent(event: ProtonSignInEvent) {
        when (event) {
            is ProtonSignInEvent.PasswordChanged -> signIn.setPassword(event.password)
            is ProtonSignInEvent.TotpChanged -> signIn.setTotp(event.totp)
            is ProtonSignInEvent.MailboxPasswordChanged -> signIn.setMailboxPassword(event.mailboxPassword)
            is ProtonSignInEvent.VerificationMessage -> onVerificationMessage(event.message)
            ProtonSignInEvent.VerificationUnsupported -> signIn.onHumanVerificationCancelled()
            ProtonSignInEvent.Submit -> submit()
            ProtonSignInEvent.Back -> onBack()
        }
    }

    private fun onVerificationMessage(message: HumanVerificationMessage) {
        when (message) {
            is HumanVerificationMessage.Solved -> launchAndFinish { signIn.onHumanVerified(message.token) }
            HumanVerificationMessage.Closed -> signIn.onHumanVerificationCancelled()
            HumanVerificationMessage.Other -> Unit
        }
    }

    private fun onBack() {
        if (state.value.step == ProtonSignInStep.HUMAN_VERIFICATION) {
            signIn.onHumanVerificationCancelled()
        } else {
            effectChannel.trySend(ProtonSignInEffect.Back)
        }
    }

    private fun submit() = launchAndFinish { signIn.submit() }

    private fun launchAndFinish(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            val current = state.value
            val session = current.sessionJson
            if (current.step == ProtonSignInStep.SIGNED_IN && session != null) {
                handoff.complete(accountStateRepository, PROTON_SERVER_TYPE, current.email, session)
                effectChannel.send(ProtonSignInEffect.SignedIn)
            }
        }
    }
}
