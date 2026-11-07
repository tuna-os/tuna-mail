package org.tunaos.mail.feature.proton.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.feature.proton.HumanVerificationMessage
import org.tunaos.mail.feature.proton.ProtonSignIn
import org.tunaos.mail.feature.proton.ProtonSignInState
import org.tunaos.mail.feature.proton.ProtonSignInStep
import org.tunaos.mail.provider.TunaSignInTarget

internal sealed interface ProtonSignInEvent {
    data class PasswordChanged(val password: String) : ProtonSignInEvent
    data class TotpChanged(val totp: String) : ProtonSignInEvent
    data class MailboxPasswordChanged(val mailboxPassword: String) : ProtonSignInEvent
    data class VerificationMessage(val message: HumanVerificationMessage) : ProtonSignInEvent
    data object VerificationUnsupported : ProtonSignInEvent
    data object Submit : ProtonSignInEvent
    data object Back : ProtonSignInEvent
}

internal sealed interface ProtonSignInEffect {
    data object SignedIn : ProtonSignInEffect
    data object Back : ProtonSignInEffect
}

/**
 * Drives [ProtonSignIn] for the [target]: an account being set up, whose address and password come
 * from the setup's password step and are tried at once, or an existing account signing in again.
 * Proton then asks for whatever else it needs.
 */
internal class ProtonSignInViewModel(
    private val signIn: ProtonSignIn,
    private val target: TunaSignInTarget,
) : ViewModel() {
    val state: StateFlow<ProtonSignInState> = signIn.state

    /** Buffered, so an effect of the sign-in started in `init` waits for the screen to collect it. */
    private val effectChannel = Channel<ProtonSignInEffect>(Channel.BUFFERED)
    val effects: Flow<ProtonSignInEffect> = effectChannel.receiveAsFlow()

    init {
        signIn.setEmail(target.email)
        signIn.setPassword(target.password)
        if (signIn.state.value.password.isNotEmpty()) submit()
    }

    fun onEvent(event: ProtonSignInEvent) {
        when (event) {
            is ProtonSignInEvent.PasswordChanged -> signIn.setPassword(event.password)
            is ProtonSignInEvent.TotpChanged -> signIn.setTotp(event.totp)
            is ProtonSignInEvent.MailboxPasswordChanged -> signIn.setMailboxPassword(event.mailboxPassword)
            is ProtonSignInEvent.VerificationMessage -> onVerificationMessage(event.message)
            ProtonSignInEvent.VerificationUnsupported -> signIn.onHumanVerificationUnsupported()
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
            if (current.step == ProtonSignInStep.SIGNED_IN && session != null && keep(session)) {
                effectChannel.send(ProtonSignInEffect.SignedIn)
            }
        }
    }

    /** Hands the session to the [target]; false (and the sign-in started over) when it could not be saved. */
    private suspend fun keep(session: String): Boolean {
        try {
            target.complete(session)
            return true
        } catch (e: IOException) {
            Log.e(TAG, e, "Could not save the Proton session of %s", target)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, e, "Could not encrypt the Proton session of %s", target)
        }
        signIn.onSessionNotSaved()
        return false
    }

    private companion object {
        const val TAG = "ProtonSignInViewModel"
    }
}
