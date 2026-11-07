package org.tunaos.mail.feature.tuta

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.k9mail.feature.account.common.data.InMemoryAccountStateRepository
import app.k9mail.feature.account.common.domain.entity.AccountState
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.legacy.logging.Log
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tunaos.mail.provider.SetupSignInTarget
import org.tunaos.mail.provider.TunaSignInHandoff
import org.tunaos.mail.provider.TunaSignInTarget
import org.tunaos.mail.provider.tunaServerSettings
import org.tunaos.tuta.TutaErrorCode

@OptIn(ExperimentalCoroutinesApi::class)
class TutaSignInViewModelTest {
    private val signIn = FakeSignIn()
    private val repository = InMemoryAccountStateRepository(
        AccountState(
            emailAddress = EMAIL,
            incomingServerSettings = tunaServerSettings(TUTA_SERVER_TYPE, "tuta.com", EMAIL, PASSWORD),
            outgoingServerSettings = tunaServerSettings(TUTA_SERVER_TYPE, "tuta.com", EMAIL, PASSWORD),
        ),
    )
    private val handoff = TunaSignInHandoff()
    private val viewModel by lazy {
        TutaSignInViewModel(signIn, SetupSignInTarget(repository, handoff, TUTA_SERVER_TYPE))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Log.logger = TestLogger()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signs in with the setup's password and hands the session over`() {
        signIn.results += TutaSignInResult.SignedIn(SESSION)

        viewModel.start()

        assertThat(signIn.calls).containsExactly(Call(EMAIL, PASSWORD, totp = null, pending = null))
        assertThat(viewModel.state.value.isSignedIn).isTrue()
        assertThat(viewModel.state.value.password).isEqualTo("")
        assertThat(repository.getState().incomingServerSettings?.password).isNull()
        assertThat(repository.getState().outgoingServerSettings?.password).isNull()
        assertThat(handoff.take(TUTA_SERVER_TYPE, EMAIL)).isEqualTo(SESSION)
    }

    @Test
    fun `a second factor is asked for and sent on the pending sign-in`() {
        val pending = PendingSignIn("pending")
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, pending)
        signIn.results += TutaSignInResult.SignedIn(SESSION)

        viewModel.start()
        assertThat(viewModel.state.value.step).isEqualTo(Step.SECOND_FACTOR)
        assertThat(viewModel.state.value.error).isNull()

        viewModel.onTotpChanged("12 34-56")
        viewModel.submit()

        assertThat(signIn.calls.last()).isEqualTo(Call(EMAIL, PASSWORD, totp = "123456", pending = pending))
        assertThat(viewModel.state.value.isSignedIn).isTrue()
    }

    @Test
    fun `a refused code is retried on the sign-in Tuta hands back`() {
        val first = PendingSignIn("first")
        val second = PendingSignIn("second")
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, first)
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.INVALID_TOTP, second)
        signIn.results += TutaSignInResult.SignedIn(SESSION)
        viewModel.start()

        viewModel.onTotpChanged("000000")
        viewModel.submit()

        assertThat(viewModel.state.value.step).isEqualTo(Step.SECOND_FACTOR)
        assertThat(viewModel.state.value.error).isEqualTo(TutaSignInError.INVALID_TOTP)
        assertThat(viewModel.state.value.totp).isEqualTo("")

        viewModel.onTotpChanged("123456")
        viewModel.submit()

        assertThat(signIn.calls.last().pending).isSameInstanceAs(second)
    }

    @Test
    fun `changing the password cancels the pending sign-in`() {
        val pending = PendingSignIn("pending")
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, pending)
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.LOGIN_FAILED, null)
        viewModel.start()

        viewModel.onPasswordChanged("other password")

        assertThat(signIn.cancelled).containsExactly(pending)

        assertThat(viewModel.state.value.step).isEqualTo(Step.PASSWORD)
        viewModel.submit()
        assertThat(signIn.calls.last()).isEqualTo(Call(EMAIL, "other password", totp = null, pending = null))
        assertThat(viewModel.state.value.error).isEqualTo(TutaSignInError.LOGIN_FAILED)
    }

    @Test
    fun `stepping back from the code cancels the pending sign-in`() {
        val first = PendingSignIn("pending")
        val second = PendingSignIn("again")
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, first)
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, second)
        viewModel.start()

        assertThat(viewModel.onBack()).isTrue()
        assertThat(viewModel.state.value.step).isEqualTo(Step.PASSWORD)
        assertThat(signIn.cancelled).containsExactly(first)
        viewModel.submit()

        assertThat(signIn.calls.last().pending).isNull()
        assertThat(viewModel.onBack()).isTrue()
        assertThat(viewModel.onBack()).isFalse()
        assertThat(signIn.cancelled).containsExactly(first, second)
    }

    @Test
    fun `leaving the screen cancels the sign-in waiting for its code`() {
        val pending = PendingSignIn("pending")
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, pending)
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer { TutaSignInViewModel(signIn, SetupSignInTarget(repository, handoff, TUTA_SERVER_TYPE)) }
        }
        val viewModel = ViewModelProvider(store, factory)[TutaSignInViewModel::class.java]
        viewModel.start()

        store.clear()

        assertThat(signIn.cancelled).containsExactly(pending)
    }

    @Test
    fun `throttling without a pending sign-in starts over with a clear message`() {
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, PendingSignIn("pending"))
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.RATE_LIMITED, null)
        viewModel.start()

        viewModel.onTotpChanged("123456")
        viewModel.submit()

        assertThat(viewModel.state.value.step).isEqualTo(Step.PASSWORD)
        assertThat(viewModel.state.value.error).isEqualTo(TutaSignInError.RATE_LIMITED)
        // Tuta ended that sign-in itself; there is nothing to cancel.
        assertThat(signIn.cancelled).isEmpty()
    }

    @Test
    fun `an expired pending sign-in starts over with a clear message`() {
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, PendingSignIn("pending"))
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.PENDING_SESSION_EXPIRED, null)
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, PendingSignIn("new"))
        viewModel.start()

        viewModel.onTotpChanged("123456")
        viewModel.submit()
        assertThat(viewModel.state.value.error).isEqualTo(TutaSignInError.PENDING_SESSION_EXPIRED)
        assertThat(viewModel.state.value.step).isEqualTo(Step.PASSWORD)

        viewModel.submit()
        assertThat(signIn.calls.last()).isEqualTo(Call(EMAIL, PASSWORD, totp = null, pending = null))
    }

    @Test
    fun `an incomplete code is not sent`() {
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.SECOND_FACTOR_REQUIRED, PendingSignIn("pending"))
        viewModel.start()

        viewModel.onTotpChanged("123")
        viewModel.submit()

        assertThat(signIn.calls.size).isEqualTo(1)
        assertThat(viewModel.state.value.error).isEqualTo(TutaSignInError.TOTP_REQUIRED)
    }

    @Test
    fun `security keys are reported as unsupported`() {
        signIn.results += TutaSignInResult.Failed(TutaErrorCode.UNSUPPORTED_WEBAUTHN, null)

        viewModel.start()

        assertThat(viewModel.state.value.error).isEqualTo(TutaSignInError.UNSUPPORTED_SECOND_FACTOR)
        assertThat(handoff.take(TUTA_SERVER_TYPE, EMAIL)).isNull()
    }

    @Test
    fun `an existing account signing in again asks for the password and keeps the new session`() {
        val target = FakeTarget()
        val viewModel = TutaSignInViewModel(signIn, target)
        signIn.results += TutaSignInResult.SignedIn(SESSION)

        viewModel.start()
        assertThat(signIn.calls).isEmpty()
        assertThat(viewModel.state.value.email).isEqualTo(EMAIL)

        viewModel.onPasswordChanged(PASSWORD)
        viewModel.submit()

        assertThat(signIn.calls).containsExactly(Call(EMAIL, PASSWORD, totp = null, pending = null))
        assertThat(target.completed).containsExactly(SESSION)
        assertThat(viewModel.state.value.isSignedIn).isTrue()
    }

    @Test
    fun `a session that cannot be saved is reported instead of finishing the sign-in`() {
        val target = FakeTarget(failure = IOException("disk full"))
        val viewModel = TutaSignInViewModel(signIn, target)
        signIn.results += TutaSignInResult.SignedIn(SESSION)
        viewModel.start()

        viewModel.onPasswordChanged(PASSWORD)
        viewModel.submit()

        assertThat(viewModel.state.value.isSignedIn).isFalse()
        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(viewModel.state.value.error).isEqualTo(TutaSignInError.UNKNOWN)
    }

    @Test
    fun `Tuta's own domains are recognized`() {
        val tuta = listOf("tuta.com", "TUTA.IO", "tutanota.com", "tutanota.de", "tutamail.com", "keemail.me")
        val other = listOf("example.com", "tuta.com.evil.test", "nottuta.com", "tutanota")

        assertThat(tuta.filterNot(::isTutaDomain)).isEmpty()
        assertThat(other.filter(::isTutaDomain)).isEmpty()
    }

    data class Call(val email: String, val password: String, val totp: String?, val pending: PendingSignIn?)

    private class FakeSignIn : TutaSignIn {
        val results = ArrayDeque<TutaSignInResult>()
        val calls = mutableListOf<Call>()
        val cancelled = mutableListOf<PendingSignIn>()

        override suspend fun signIn(
            email: String,
            password: String,
            totp: String?,
            pending: PendingSignIn?,
        ): TutaSignInResult {
            calls += Call(email, password, totp, pending)
            return results.removeFirst()
        }

        override fun cancel(pending: PendingSignIn) {
            cancelled += pending
        }
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
        const val EMAIL = "alice@tuta.com"
        const val PASSWORD = "correct horse"
        const val SESSION = "{\"login\":\"alice@tuta.com\"}"
    }
}
