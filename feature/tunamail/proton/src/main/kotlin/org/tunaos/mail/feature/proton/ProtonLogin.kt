package org.tunaos.mail.feature.proton

import net.thunderbird.legacy.logging.Log
import org.tunaos.proton.HumanVerificationChallenge
import org.tunaos.proton.HumanVerificationToken
import org.tunaos.proton.ProtonClient
import org.tunaos.proton.ProtonErrorCode
import org.tunaos.proton.ProtonException
import org.tunaos.proton.ProtonSessionStore

/** One sign-in attempt; every attempt is a complete SRP login, Proton keeps nothing in between. */
data class ProtonLoginRequest(
    val email: String,
    val password: String,
    val totp: String?,
    val mailboxPassword: String?,
    val humanVerification: HumanVerificationToken?,
) {
    override fun toString() = "ProtonLoginRequest(email=$email, <secrets redacted>)"
}

sealed interface ProtonLoginOutcome {
    /** [sessionJson] is the session to store for the new account; it is a secret. */
    class SignedIn(val sessionJson: String) : ProtonLoginOutcome {
        override fun toString() = "SignedIn(<redacted>)"
    }

    /** Credentials, a factor, the mailbox password or the client were refused; [code] says which. */
    data class Refused(val code: ProtonErrorCode) : ProtonLoginOutcome

    /** Proton wants a CAPTCHA first; a null [challenge] cannot be solved from this reply. */
    data class VerificationRequired(val challenge: HumanVerificationChallenge?) : ProtonLoginOutcome

    /** Network failure, throttling or timeout: retrying later may work. */
    data object Unreachable : ProtonLoginOutcome
}

fun interface ProtonLogin {
    suspend fun login(request: ProtonLoginRequest): ProtonLoginOutcome
}

/** [ProtonLogin] through the SDK. The session is only held in memory until the account exists. */
class SdkProtonLogin(private val client: ProtonClient) : ProtonLogin {
    override suspend fun login(request: ProtonLoginRequest): ProtonLoginOutcome = try {
        val account = client.login(
            email = request.email,
            password = request.password,
            store = ProtonSessionStore { },
            totp = request.totp,
            mailboxPassword = request.mailboxPassword,
            humanVerification = request.humanVerification,
        )
        ProtonLoginOutcome.SignedIn(account.session.json)
    } catch (e: ProtonException.HumanVerificationRequired) {
        ProtonLoginOutcome.VerificationRequired(e.challenge)
    } catch (e: ProtonException.Transport) {
        Log.w(TAG, e, "Proton sign-in could not reach the server")
        ProtonLoginOutcome.Unreachable
    } catch (e: ProtonException) {
        ProtonLoginOutcome.Refused(e.code)
    }
}

private const val TAG = "SdkProtonLogin"
