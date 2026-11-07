package org.tunaos.mail.feature.tuta

import org.tunaos.mail.backend.tuta.TutaApi
import org.tunaos.tuta.TutaErrorCode
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaPendingSession

/** Signing in to Tuta, for the setup screens; [SdkTutaSignIn] is the real one. */
fun interface TutaSignIn {
    /**
     * Signs [email] in. [totp] answers a second-factor request, on the [pending] sign-in the previous attempt
     * returned.
     */
    suspend fun signIn(email: String, password: String, totp: String?, pending: PendingSignIn?): TutaSignInResult
}

sealed interface TutaSignInResult {
    /** Signed in; [session] is the session object to keep (a credential). */
    data class SignedIn(val session: String) : TutaSignInResult {
        override fun toString() = "SignedIn(<redacted>)"
    }

    /**
     * Not signed in, for [code]. [pending] is the sign-in Tuta keeps open for a second-factor code (asked for, refused,
     * or interrupted); null when the sign-in has to start over.
     */
    data class Failed(val code: TutaErrorCode, val pending: PendingSignIn?) : TutaSignInResult
}

/** A sign-in waiting for its second factor. A credential: it lives in memory only and is never logged. */
class PendingSignIn(internal val handle: Any) {
    override fun toString() = "PendingSignIn(<redacted>)"
}

/** [TutaSignIn] through the SDK. */
class SdkTutaSignIn(private val api: TutaApi) : TutaSignIn {
    override suspend fun signIn(
        email: String,
        password: String,
        totp: String?,
        pending: PendingSignIn?,
    ): TutaSignInResult {
        return try {
            val session = api.login(email, password, totp, pending?.handle as? TutaPendingSession)
            TutaSignInResult.SignedIn(session.json)
        } catch (e: TutaException) {
            TutaSignInResult.Failed(e.code, e.pendingSession?.let(::PendingSignIn))
        }
    }
}
