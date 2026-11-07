package org.tunaos.mail.feature.tuta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.provider.TunaSignInTarget
import org.tunaos.tuta.TutaErrorCode

/**
 * The Tuta sign-in of an account: of one being set up, or of an existing one signing in again ([TunaSignInTarget]).
 * The password (carried over from the setup's password step, and editable here), then a TOTP code when the account
 * has two-factor sign-in.
 *
 * The sign-in Tuta keeps open for the code ([PendingSignIn]) is held only here, in memory, and dropped whenever the
 * password changes, the user steps back, or Tuta says the sign-in has to start over. Leaving the screen ends this view
 * model, and with it the pending sign-in.
 */
class TutaSignInViewModel(
    private val signIn: TutaSignIn,
    private val target: TunaSignInTarget,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TutaSignInState())
    val state: StateFlow<TutaSignInState> = mutableState.asStateFlow()

    private var pending: PendingSignIn? = null

    /** Loads the address and any password from the [target]; signs in right away when there is a password. */
    fun start() {
        val email = target.email
        if (email.isEmpty()) return
        if (state.value.email == email && state.value.isStarted) return

        pending = null
        mutableState.value = TutaSignInState(
            email = email,
            password = target.password,
            isStarted = true,
        )
        if (state.value.password.isNotEmpty()) submit()
    }

    fun onPasswordChanged(password: String) {
        if (password == state.value.password) return
        pending = null
        mutableState.update { it.copy(password = password, totp = "", step = Step.PASSWORD, error = null) }
    }

    fun onTotpChanged(totp: String) {
        mutableState.update { it.copy(totp = totp.filter(Char::isDigit).take(TOTP_LENGTH), error = null) }
    }

    fun submit() {
        val current = state.value
        if (current.isLoading || current.isSignedIn) return
        if (current.password.isEmpty()) {
            mutableState.update { it.copy(error = TutaSignInError.PASSWORD_REQUIRED) }
            return
        }
        val totp = if (current.step == Step.SECOND_FACTOR) current.totp else null
        if (totp != null && totp.length != TOTP_LENGTH) {
            mutableState.update { it.copy(error = TutaSignInError.TOTP_REQUIRED) }
            return
        }

        mutableState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = signIn.signIn(current.email, current.password, totp, pending)
            onResult(result)
        }
    }

    /** Steps back from the code to the password; returns false when the screen itself should be left. */
    fun onBack(): Boolean {
        if (state.value.isLoading) return true
        if (state.value.step == Step.SECOND_FACTOR) {
            pending = null
            mutableState.update { it.copy(step = Step.PASSWORD, totp = "", error = null) }
            return true
        }
        return false
    }

    private suspend fun onResult(result: TutaSignInResult) {
        when (result) {
            is TutaSignInResult.SignedIn -> {
                pending = null
                if (!keep(result.session)) return
                mutableState.update {
                    it.copy(password = "", totp = "", isLoading = false, isSignedIn = true, error = null)
                }
            }

            is TutaSignInResult.Failed -> {
                pending = result.pending
                val step = if (result.pending != null) Step.SECOND_FACTOR else Step.PASSWORD
                mutableState.update {
                    it.copy(
                        step = step,
                        totp = "",
                        isLoading = false,
                        error = result.code.toSignInError(),
                    )
                }
            }
        }
    }

    /** Hands the session to the [target]; false (and an error shown) when it could not be saved. */
    private suspend fun keep(session: String): Boolean {
        try {
            target.complete(session)
            return true
        } catch (e: IOException) {
            Log.e(TAG, e, "Could not save the Tuta session of %s", target)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, e, "Could not encrypt the Tuta session of %s", target)
        }
        mutableState.update { it.copy(isLoading = false, error = TutaSignInError.UNKNOWN) }
        return false
    }

    private companion object {
        const val TAG = "TutaSignInViewModel"
        const val TOTP_LENGTH = 6
    }
}

data class TutaSignInState(
    val email: String = "",
    val password: String = "",
    val totp: String = "",
    val step: Step = Step.PASSWORD,
    val isStarted: Boolean = false,
    val isLoading: Boolean = false,
    val isSignedIn: Boolean = false,
    val error: TutaSignInError? = null,
) {
    override fun toString() = "TutaSignInState(step=$step, isLoading=$isLoading, isSignedIn=$isSignedIn, error=$error)"
}

enum class Step {
    PASSWORD,
    SECOND_FACTOR,
}

/** What the screen tells the user; each has a string in this module's resources. */
enum class TutaSignInError {
    PASSWORD_REQUIRED,
    TOTP_REQUIRED,
    LOGIN_FAILED,
    INVALID_TOTP,
    RATE_LIMITED,
    PENDING_SESSION_EXPIRED,
    UNSUPPORTED_SECOND_FACTOR,
    NETWORK,
    CLIENT_OUTDATED,
    UNKNOWN,
}

/** The message for a sign-in failure; [TutaErrorCode.SECOND_FACTOR_REQUIRED] is not one, it asks for the code. */
internal fun TutaErrorCode.toSignInError(): TutaSignInError? = when (this) {
    TutaErrorCode.SECOND_FACTOR_REQUIRED -> null

    TutaErrorCode.LOGIN_FAILED -> TutaSignInError.LOGIN_FAILED

    TutaErrorCode.INVALID_TOTP, TutaErrorCode.SECOND_FACTOR_FAILED -> TutaSignInError.INVALID_TOTP

    TutaErrorCode.RATE_LIMITED -> TutaSignInError.RATE_LIMITED

    TutaErrorCode.PENDING_SESSION_EXPIRED -> TutaSignInError.PENDING_SESSION_EXPIRED

    TutaErrorCode.UNSUPPORTED_U2F,
    TutaErrorCode.UNSUPPORTED_WEBAUTHN,
    TutaErrorCode.UNSUPPORTED_SECOND_FACTOR,
    -> TutaSignInError.UNSUPPORTED_SECOND_FACTOR

    TutaErrorCode.TRANSPORT_FAILED, TutaErrorCode.REQUEST_FAILED, TutaErrorCode.TIMEOUT -> TutaSignInError.NETWORK

    TutaErrorCode.CLIENT_OUTDATED -> TutaSignInError.CLIENT_OUTDATED

    else -> TutaSignInError.UNKNOWN
}
