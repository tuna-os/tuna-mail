package org.tunaos.mail.feature.proton

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.tunaos.proton.HumanVerificationChallenge
import org.tunaos.proton.HumanVerificationToken
import org.tunaos.proton.ProtonErrorCode

enum class ProtonSignInStep {
    PASSWORD,
    TOTP,
    MAILBOX_PASSWORD,
    HUMAN_VERIFICATION,
    SIGNED_IN,
}

enum class ProtonSignInError {
    WRONG_PASSWORD,
    INVALID_TOTP,
    TOTP_REJECTED,
    UNSUPPORTED_SECOND_FACTOR,
    MAILBOX_PASSWORD_REJECTED,
    HUMAN_VERIFICATION_FAILED,
    HUMAN_VERIFICATION_UNAVAILABLE,
    HUMAN_VERIFICATION_CANCELLED,
    HUMAN_VERIFICATION_UNSUPPORTED,
    CLIENT_OUTDATED,
    UNREACHABLE,
    FAILED,
}

data class ProtonSignInState(
    val email: String = "",
    val password: String = "",
    val totp: String = "",
    val mailboxPassword: String = "",
    val step: ProtonSignInStep = ProtonSignInStep.PASSWORD,
    /** The step a CAPTCHA interrupted, to return to when it is dismissed. */
    val stepBeforeVerification: ProtonSignInStep = ProtonSignInStep.PASSWORD,
    val challenge: HumanVerificationChallenge? = null,
    val error: ProtonSignInError? = null,
    val isLoading: Boolean = false,
    val sessionJson: String? = null,
) {
    /** The page the human-verification step shows. */
    val verificationUrl: String? get() = challenge?.verifyUrl()

    override fun toString() = "ProtonSignInState(email=$email, step=$step, error=$error, isLoading=$isLoading)"
}

/**
 * The Proton sign-in steps: password, then whatever Proton asks for next — a TOTP code, the
 * mailbox password of a two-password account, or a CAPTCHA (human verification), which can come
 * before any of them. Proton keeps no half-finished sign-in, so every [submit] is a complete
 * login with everything entered so far; changing the address or password forgets the rest.
 */
class ProtonSignIn(private val login: ProtonLogin) {
    private val mutableState = MutableStateFlow(ProtonSignInState())
    val state: StateFlow<ProtonSignInState> = mutableState.asStateFlow()

    fun setEmail(email: String) = restart { it.copy(email = email.trim()) }

    fun setPassword(password: String) = restart { it.copy(password = password) }

    fun setTotp(totp: String) = mutableState.update { it.copy(totp = totp.filter(Char::isDigit), error = null) }

    fun setMailboxPassword(mailboxPassword: String) =
        mutableState.update { it.copy(mailboxPassword = mailboxPassword, error = null) }

    suspend fun submit() = attempt(humanVerification = null)

    /** The CAPTCHA page reported a solved token: sign in again presenting it. */
    suspend fun onHumanVerified(token: HumanVerificationToken) = attempt(humanVerification = token)

    fun onHumanVerificationCancelled() = leaveVerification(ProtonSignInError.HUMAN_VERIFICATION_CANCELLED)

    /** The device's WebView is too old to show Proton's CAPTCHA page. */
    fun onHumanVerificationUnsupported() = leaveVerification(ProtonSignInError.HUMAN_VERIFICATION_UNSUPPORTED)

    private fun leaveVerification(error: ProtonSignInError) = mutableState.update {
        it.copy(step = it.stepBeforeVerification, challenge = null, error = error)
    }

    private fun restart(change: (ProtonSignInState) -> ProtonSignInState) = mutableState.update {
        change(it).copy(
            totp = "",
            mailboxPassword = "",
            step = ProtonSignInStep.PASSWORD,
            stepBeforeVerification = ProtonSignInStep.PASSWORD,
            challenge = null,
            error = null,
            sessionJson = null,
        )
    }

    private suspend fun attempt(humanVerification: HumanVerificationToken?, isRetry: Boolean = false) {
        val current = mutableState.value
        if (current.isLoading || current.email.isEmpty() || current.password.isEmpty()) return
        mutableState.update { it.copy(isLoading = true, error = null) }

        val outcome = login.login(current.toRequest(humanVerification))
        if (outcome is ProtonLoginOutcome.Refused &&
            outcome.code == ProtonErrorCode.HUMAN_VERIFICATION_FAILED &&
            !isRetry
        ) {
            // The token was spent or refused; only a fresh refusal carries a new challenge.
            mutableState.update { it.copy(isLoading = false) }
            attempt(humanVerification = null, isRetry = true)
            mutableState.update { state ->
                if (state.step == ProtonSignInStep.HUMAN_VERIFICATION) {
                    state.copy(error = ProtonSignInError.HUMAN_VERIFICATION_FAILED)
                } else {
                    state
                }
            }
            return
        }
        mutableState.update { it.after(outcome).copy(isLoading = false) }
    }

    private fun ProtonSignInState.toRequest(humanVerification: HumanVerificationToken?) = ProtonLoginRequest(
        email = email,
        password = password,
        totp = totp.takeIf { it.isNotEmpty() },
        mailboxPassword = mailboxPassword.takeIf { it.isNotEmpty() },
        humanVerification = humanVerification,
    )

    private fun ProtonSignInState.after(outcome: ProtonLoginOutcome): ProtonSignInState = when (outcome) {
        is ProtonLoginOutcome.SignedIn -> copy(
            step = ProtonSignInStep.SIGNED_IN,
            challenge = null,
            sessionJson = outcome.sessionJson,
        )

        is ProtonLoginOutcome.VerificationRequired -> if (outcome.challenge == null) {
            copy(error = ProtonSignInError.HUMAN_VERIFICATION_UNAVAILABLE)
        } else {
            copy(
                step = ProtonSignInStep.HUMAN_VERIFICATION,
                stepBeforeVerification = inputStep(),
                challenge = outcome.challenge,
            )
        }

        ProtonLoginOutcome.Unreachable ->
            copy(step = inputStep(), challenge = null, error = ProtonSignInError.UNREACHABLE)

        is ProtonLoginOutcome.Refused -> refused(outcome.code).copy(challenge = null)
    }

    private fun ProtonSignInState.refused(code: ProtonErrorCode): ProtonSignInState = when (code) {
        ProtonErrorCode.SECOND_FACTOR_REQUIRED -> copy(step = ProtonSignInStep.TOTP, totp = "")

        ProtonErrorCode.INVALID_TOTP -> copy(step = ProtonSignInStep.TOTP, error = ProtonSignInError.INVALID_TOTP)

        ProtonErrorCode.SECOND_FACTOR_FAILED ->
            copy(step = ProtonSignInStep.TOTP, totp = "", error = ProtonSignInError.TOTP_REJECTED)

        ProtonErrorCode.MAILBOX_PASSWORD_REQUIRED -> copy(step = ProtonSignInStep.MAILBOX_PASSWORD)

        ProtonErrorCode.MAILBOX_PASSWORD_FAILED -> copy(
            step = ProtonSignInStep.MAILBOX_PASSWORD,
            mailboxPassword = "",
            error = ProtonSignInError.MAILBOX_PASSWORD_REJECTED,
        )

        ProtonErrorCode.LOGIN_FAILED -> copy(
            step = ProtonSignInStep.PASSWORD,
            totp = "",
            mailboxPassword = "",
            error = ProtonSignInError.WRONG_PASSWORD,
        )

        ProtonErrorCode.UNSUPPORTED_SECOND_FACTOR ->
            copy(step = ProtonSignInStep.PASSWORD, error = ProtonSignInError.UNSUPPORTED_SECOND_FACTOR)

        ProtonErrorCode.HUMAN_VERIFICATION_FAILED ->
            copy(step = inputStep(), error = ProtonSignInError.HUMAN_VERIFICATION_FAILED)

        ProtonErrorCode.CLIENT_OUTDATED -> copy(step = inputStep(), error = ProtonSignInError.CLIENT_OUTDATED)

        else -> copy(step = inputStep(), error = ProtonSignInError.FAILED)
    }

    /** The step the user was typing in, even while a CAPTCHA is shown on top of it. */
    private fun ProtonSignInState.inputStep(): ProtonSignInStep =
        if (step == ProtonSignInStep.HUMAN_VERIFICATION) stepBeforeVerification else step
}
