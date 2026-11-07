package org.tunaos.mail.feature.proton

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.tunaos.proton.HumanVerificationChallenge
import org.tunaos.proton.HumanVerificationToken
import org.tunaos.proton.ProtonErrorCode

class ProtonSignInTest {
    private val outcomes = ArrayDeque<ProtonLoginOutcome>()
    private val requests = mutableListOf<ProtonLoginRequest>()
    private val signIn = ProtonSignIn { request ->
        requests += request
        outcomes.removeFirst()
    }

    @Test
    fun `password only`() = runTest {
        enterCredentials()
        outcomes += ProtonLoginOutcome.SignedIn(SESSION)

        signIn.submit()

        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.SIGNED_IN)
        assertThat(signIn.state.value.sessionJson).isEqualTo(SESSION)
        assertThat(requests.single()).isEqualTo(ProtonLoginRequest(EMAIL, PASSWORD, null, null, null))
    }

    @Test
    fun `TOTP then mailbox password`() = runTest {
        enterCredentials()
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.SECOND_FACTOR_REQUIRED)
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.MAILBOX_PASSWORD_REQUIRED)
        outcomes += ProtonLoginOutcome.SignedIn(SESSION)

        signIn.submit()
        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.TOTP)
        signIn.setTotp("123 456")
        signIn.submit()
        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.MAILBOX_PASSWORD)
        signIn.setMailboxPassword("mailbox")
        signIn.submit()

        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.SIGNED_IN)
        assertThat(requests.last()).isEqualTo(ProtonLoginRequest(EMAIL, PASSWORD, "123456", "mailbox", null))
    }

    @Test
    fun `refused factors stay on their step with an error`() = runTest {
        enterCredentials()
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.SECOND_FACTOR_REQUIRED)
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.SECOND_FACTOR_FAILED)
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.MAILBOX_PASSWORD_REQUIRED)
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.MAILBOX_PASSWORD_FAILED)

        signIn.submit()
        signIn.setTotp("111111")
        signIn.submit()
        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.TOTP)
        assertThat(signIn.state.value.error).isEqualTo(ProtonSignInError.TOTP_REJECTED)
        assertThat(signIn.state.value.totp).isEqualTo("")

        signIn.setTotp("222222")
        signIn.submit()
        signIn.setMailboxPassword("wrong")
        signIn.submit()
        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.MAILBOX_PASSWORD)
        assertThat(signIn.state.value.error).isEqualTo(ProtonSignInError.MAILBOX_PASSWORD_REJECTED)
    }

    @Test
    fun `a wrong password returns to the password step and forgets later inputs`() = runTest {
        enterCredentials()
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.LOGIN_FAILED)

        signIn.submit()

        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.PASSWORD)
        assertThat(signIn.state.value.error).isEqualTo(ProtonSignInError.WRONG_PASSWORD)
    }

    @Test
    fun `changing the password starts over`() = runTest {
        enterCredentials()
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.SECOND_FACTOR_REQUIRED)
        signIn.submit()
        signIn.setTotp("123456")

        signIn.setPassword("other")

        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.PASSWORD)
        assertThat(signIn.state.value.totp).isEqualTo("")
    }

    @Test
    fun `a CAPTCHA is shown, then the login is retried with the solved token and the TOTP`() = runTest {
        enterCredentials()
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.SECOND_FACTOR_REQUIRED)
        outcomes += ProtonLoginOutcome.VerificationRequired(CHALLENGE)
        outcomes += ProtonLoginOutcome.SignedIn(SESSION)

        signIn.submit()
        signIn.setTotp("123456")
        signIn.submit()
        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.HUMAN_VERIFICATION)
        assertThat(signIn.state.value.verificationUrl)
            .isEqualTo("https://verify.proton.me/?methods=captcha%2Cemail&token=challenge&embed=true")

        signIn.onHumanVerified(TOKEN)

        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.SIGNED_IN)
        assertThat(requests.last()).isEqualTo(ProtonLoginRequest(EMAIL, PASSWORD, "123456", null, TOKEN))
    }

    @Test
    fun `a refused token asks Proton for a fresh challenge`() = runTest {
        enterCredentials()
        val fresh = HumanVerificationChallenge(token = "fresh", methods = listOf("captcha"))
        outcomes += ProtonLoginOutcome.VerificationRequired(CHALLENGE)
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.HUMAN_VERIFICATION_FAILED)
        outcomes += ProtonLoginOutcome.VerificationRequired(fresh)

        signIn.submit()
        signIn.onHumanVerified(TOKEN)

        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.HUMAN_VERIFICATION)
        assertThat(signIn.state.value.challenge).isEqualTo(fresh)
        assertThat(signIn.state.value.error).isEqualTo(ProtonSignInError.HUMAN_VERIFICATION_FAILED)
        assertThat(requests.map { it.humanVerification }).containsExactly(null, TOKEN, null)
    }

    @Test
    fun `dismissing the CAPTCHA returns to the interrupted step`() = runTest {
        enterCredentials()
        outcomes += ProtonLoginOutcome.VerificationRequired(CHALLENGE)
        signIn.submit()

        signIn.onHumanVerificationCancelled()

        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.PASSWORD)
        assertThat(signIn.state.value.error).isEqualTo(ProtonSignInError.HUMAN_VERIFICATION_CANCELLED)
        assertThat(signIn.state.value.challenge).isNull()
    }

    @Test
    fun `an unsolvable challenge and an unreachable server are errors`() = runTest {
        enterCredentials()
        outcomes += ProtonLoginOutcome.VerificationRequired(null)
        outcomes += ProtonLoginOutcome.Unreachable

        signIn.submit()
        assertThat(signIn.state.value.error).isEqualTo(ProtonSignInError.HUMAN_VERIFICATION_UNAVAILABLE)
        signIn.submit()
        assertThat(signIn.state.value.error).isEqualTo(ProtonSignInError.UNREACHABLE)
        assertThat(signIn.state.value.step).isEqualTo(ProtonSignInStep.PASSWORD)
    }

    @Test
    fun `nothing is sent without an address and a password`() = runTest {
        signIn.setEmail(EMAIL)

        signIn.submit()

        assertThat(requests.size).isEqualTo(0)
    }

    private fun enterCredentials() {
        signIn.setEmail(" $EMAIL ")
        signIn.setPassword(PASSWORD)
    }

    private companion object {
        const val EMAIL = "alice@proton.me"
        const val PASSWORD = "correct horse"
        const val SESSION = """{"login":"alice@proton.me"}"""
        val CHALLENGE = HumanVerificationChallenge(token = "challenge", methods = listOf("captcha", "email"))
        val TOKEN = HumanVerificationToken(token = "solved", type = "captcha")
    }
}
