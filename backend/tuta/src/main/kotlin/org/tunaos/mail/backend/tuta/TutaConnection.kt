package org.tunaos.mail.backend.tuta

import com.fsck.k9.mail.AuthenticationFailedException
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.exception.MessagingException
import org.tunaos.tuta.TutaErrorCode
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaSession

/** Where a Tuta account's session comes from. */
interface TutaSessionSource {
    /** The account's session object (`TutaSession.json`), or null when it has none and must sign in again. */
    fun current(): String?

    /** Tuta refused the session for good (revoked or expired): forget it. */
    fun invalidate()
}

/**
 * Runs SDK calls with the account's session and turns their failures into the app's exceptions: a session Tuta
 * refused into [AuthenticationFailedException] (and the session is forgotten), anything that may work on a retry
 * into a temporary [MessagingException], everything else into a permanent one.
 */
internal class TutaConnection(
    val api: TutaApi,
    private val sessions: TutaSessionSource,
) {
    /** Calls [block] with the session, blocking the calling (background) thread. */
    fun <T> call(block: suspend TutaApi.(TutaSession) -> T): T = runBlocking { suspendCall(block) }

    suspend fun <T> suspendCall(block: suspend TutaApi.(TutaSession) -> T): T {
        val session = session()
        return try {
            api.block(session)
        } catch (e: TutaException) {
            throw toMessagingException(e)
        }
    }

    /** The account's session; throws [AuthenticationFailedException] when it has none. */
    fun session(): TutaSession {
        val json = sessions.current() ?: throw AuthenticationFailedException("No Tuta session; sign in again")
        return try {
            TutaSession.fromJson(json)
        } catch (e: TutaException) {
            throw toMessagingException(e)
        }
    }

    /** The app's exception for [e], forgetting the session when Tuta refused it. */
    fun toMessagingException(e: TutaException): MessagingException {
        val message = "Tuta: ${e.wireCode}"
        return when {
            e.code in REFUSED_SESSION -> {
                sessions.invalidate()
                AuthenticationFailedException(message, e)
            }

            e is TutaException.Session && e.code != TutaErrorCode.RESUME_FAILED ->
                AuthenticationFailedException(message, e)

            e is TutaException.Transport || e.code == TutaErrorCode.RESUME_FAILED ->
                MessagingException(message, isPermanentFailure = false, cause = e)

            else -> MessagingException(message, isPermanentFailure = true, cause = e)
        }
    }

    private companion object {
        val REFUSED_SESSION = setOf(TutaErrorCode.SESSION_REVOKED, TutaErrorCode.SESSION_INVALID)
    }
}
