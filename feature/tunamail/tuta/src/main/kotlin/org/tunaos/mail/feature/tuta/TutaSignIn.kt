package org.tunaos.mail.feature.tuta

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.tuta.TutaApi
import org.tunaos.tuta.TutaErrorCode
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaPendingSession

/** Signing in to Tuta, for the sign-in screens; [SdkTutaSignIn] is the real one. */
interface TutaSignIn {
    /**
     * Signs [email] in. [totp] answers a second-factor request, on the [pending] sign-in the previous attempt
     * returned.
     */
    suspend fun signIn(email: String, password: String, totp: String?, pending: PendingSignIn?): TutaSignInResult

    /**
     * Gives up [pending], a sign-in waiting for its second factor that the user left, so Tuta closes it instead of
     * keeping it open until it expires. Returns at once: the request runs in the background, and a failure is only
     * logged.
     */
    fun cancel(pending: PendingSignIn)
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

/** [TutaSignIn] through the SDK; cancellations run in [scope], which outlives the sign-in screens. */
class SdkTutaSignIn(private val api: TutaApi, private val scope: CoroutineScope) : TutaSignIn {
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

    override fun cancel(pending: PendingSignIn) {
        val pendingSession = pending.handle as? TutaPendingSession ?: return
        scope.launch(Dispatchers.IO) {
            try {
                api.cancelSignIn(pendingSession)
            } catch (e: TutaException) {
                Log.w(TAG, "Could not cancel a Tuta sign-in waiting for its second factor: %s", e.wireCode)
            }
        }
    }

    private companion object {
        const val TAG = "SdkTutaSignIn"
    }
}
