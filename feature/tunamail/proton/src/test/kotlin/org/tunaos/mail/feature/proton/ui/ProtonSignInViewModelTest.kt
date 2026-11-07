package org.tunaos.mail.feature.proton.ui

import app.k9mail.feature.account.common.data.InMemoryAccountStateRepository
import app.k9mail.feature.account.common.domain.entity.AccountState
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.tunaos.mail.feature.proton.PROTON_SERVER_TYPE
import org.tunaos.mail.feature.proton.ProtonLoginOutcome
import org.tunaos.mail.feature.proton.ProtonLoginRequest
import org.tunaos.mail.feature.proton.ProtonSignIn
import org.tunaos.mail.feature.proton.ProtonSignInError
import org.tunaos.mail.feature.proton.ProtonSignInStep
import org.tunaos.mail.provider.SetupSignInTarget
import org.tunaos.mail.provider.TunaSignInHandoff
import org.tunaos.mail.provider.TunaSignInTarget
import org.tunaos.mail.provider.tunaServerSettings
import org.tunaos.proton.ProtonErrorCode

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonSignInViewModelTest {
    private val outcomes = ArrayDeque<ProtonLoginOutcome>()
    private val requests = mutableListOf<ProtonLoginRequest>()
    private val signIn = ProtonSignIn { request ->
        requests += request
        outcomes.removeFirst()
    }
    private val settings = tunaServerSettings(PROTON_SERVER_TYPE, "proton.me", EMAIL, PASSWORD)
    private val repository = InMemoryAccountStateRepository(
        AccountState(incomingServerSettings = settings, outgoingServerSettings = settings),
    )
    private val handoff = TunaSignInHandoff()
    private val setupTarget = SetupSignInTarget(repository, handoff, PROTON_SERVER_TYPE)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signs in at once with the setup's credentials and hands the session over`() = runTest {
        outcomes += ProtonLoginOutcome.SignedIn(SESSION)
        val effects = mutableListOf<ProtonSignInEffect>()

        val viewModel = ProtonSignInViewModel(signIn, setupTarget)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }

        assertThat(requests.single().email).isEqualTo(EMAIL)
        assertThat(handoff.take(PROTON_SERVER_TYPE, EMAIL)).isEqualTo(SESSION)
        assertThat(repository.getState().incomingServerSettings?.password).isNull()
        assertThat(repository.getState().outgoingServerSettings?.password).isNull()
        assertThat(viewModel.state.value.step).isEqualTo(ProtonSignInStep.SIGNED_IN)
        assertThat(effects).containsExactly(ProtonSignInEffect.SignedIn)
    }

    @Test
    fun `asks for the TOTP code and finishes once it is accepted`() = runTest {
        outcomes += ProtonLoginOutcome.Refused(ProtonErrorCode.SECOND_FACTOR_REQUIRED)
        outcomes += ProtonLoginOutcome.SignedIn(SESSION)
        val viewModel = ProtonSignInViewModel(signIn, setupTarget)
        val effects = mutableListOf<ProtonSignInEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        assertThat(viewModel.state.value.step).isEqualTo(ProtonSignInStep.TOTP)

        viewModel.onEvent(ProtonSignInEvent.TotpChanged("123456"))
        viewModel.onEvent(ProtonSignInEvent.Submit)

        assertThat(effects).containsExactly(ProtonSignInEffect.SignedIn)
        assertThat(requests.last().totp).isEqualTo("123456")
    }

    @Test
    fun `back from the CAPTCHA returns to the form, back from the form leaves`() = runTest {
        outcomes += ProtonLoginOutcome.VerificationRequired(
            org.tunaos.proton.HumanVerificationChallenge(token = "t", methods = listOf("captcha")),
        )
        val viewModel = ProtonSignInViewModel(signIn, setupTarget)
        val effects = mutableListOf<ProtonSignInEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }

        viewModel.onEvent(ProtonSignInEvent.Back)
        assertThat(viewModel.state.value.step).isEqualTo(ProtonSignInStep.PASSWORD)
        viewModel.onEvent(ProtonSignInEvent.Back)

        assertThat(effects).containsExactly(ProtonSignInEffect.Back)
    }

    @Test
    fun `a WebView too old for the CAPTCHA says so instead of reporting a cancel`() = runTest {
        outcomes += ProtonLoginOutcome.VerificationRequired(
            org.tunaos.proton.HumanVerificationChallenge(token = "t", methods = listOf("captcha")),
        )
        val viewModel = ProtonSignInViewModel(signIn, setupTarget)
        assertThat(viewModel.state.value.step).isEqualTo(ProtonSignInStep.HUMAN_VERIFICATION)

        viewModel.onEvent(ProtonSignInEvent.VerificationUnsupported)

        assertThat(viewModel.state.value.step).isEqualTo(ProtonSignInStep.PASSWORD)
        assertThat(viewModel.state.value.error).isEqualTo(ProtonSignInError.HUMAN_VERIFICATION_UNSUPPORTED)
        assertThat(viewModel.state.value.challenge).isNull()
    }

    @Test
    fun `an existing account signing in again waits for the password and keeps the new session`() = runTest {
        outcomes += ProtonLoginOutcome.SignedIn(SESSION)
        val target = FakeTarget()
        val viewModel = ProtonSignInViewModel(signIn, target)
        val effects = mutableListOf<ProtonSignInEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        assertThat(requests).isEmpty()
        assertThat(viewModel.state.value.email).isEqualTo(EMAIL)

        viewModel.onEvent(ProtonSignInEvent.PasswordChanged(PASSWORD))
        viewModel.onEvent(ProtonSignInEvent.Submit)

        assertThat(target.completed).containsExactly(SESSION)
        assertThat(effects).containsExactly(ProtonSignInEffect.SignedIn)
    }

    @Test
    fun `a session that cannot be saved starts the sign-in over`() = runTest {
        outcomes += ProtonLoginOutcome.SignedIn(SESSION)
        val viewModel = ProtonSignInViewModel(signIn, FakeTarget(failure = IOException("disk full")))
        val effects = mutableListOf<ProtonSignInEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }

        viewModel.onEvent(ProtonSignInEvent.PasswordChanged(PASSWORD))
        viewModel.onEvent(ProtonSignInEvent.Submit)

        assertThat(effects).isEmpty()
        assertThat(viewModel.state.value.step).isEqualTo(ProtonSignInStep.PASSWORD)
        assertThat(viewModel.state.value.error).isEqualTo(ProtonSignInError.FAILED)
        assertThat(viewModel.state.value.sessionJson).isNull()
    }

    private class FakeTarget(private val failure: IOException? = null) : TunaSignInTarget {
        val completed = mutableListOf<String>()
        override val email = EMAIL
        override val password = ""

        override suspend fun complete(session: String) {
            failure?.let { throw it }
            completed += session
        }
    }

    private companion object {
        const val EMAIL = "alice@proton.me"
        const val PASSWORD = "secret"
        const val SESSION = """{"uid":"u","key_pass":"k"}"""
    }
}
