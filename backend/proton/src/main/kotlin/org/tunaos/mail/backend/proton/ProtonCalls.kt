package org.tunaos.mail.backend.proton

import com.fsck.k9.mail.AuthenticationFailedException
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.exception.MessagingException
import org.tunaos.proton.ProtonErrorCode
import org.tunaos.proton.ProtonException
import org.tunaos.proton.ProtonOutcome

/**
 * Runs a Proton request from the backend's blocking API and turns a [ProtonException] into what
 * the messaging controller understands: an [AuthenticationFailedException] for a session or
 * credential refusal (the user must sign in again), a temporary [MessagingException] for a
 * network failure, and a permanent one for everything else.
 */
internal fun <T> protonCall(block: suspend () -> T): T = runBlocking { protonSuspendCall(block) }

internal suspend fun <T> protonSuspendCall(block: suspend () -> T): T {
    return try {
        block()
    } catch (e: ProtonException) {
        throw e.toMessagingException()
    }
}

internal fun ProtonException.toMessagingException(): MessagingException = when (this) {
    is ProtonException.Session,
    is ProtonException.Auth,
    is ProtonException.HumanVerificationRequired,
    -> AuthenticationFailedException("Proton refused the session: $wire", this)
    is ProtonException.Transport -> MessagingException("Proton request failed: $wire", this)
    is ProtonException.SendUncertain -> MessagingException(
        "Proton may have sent this message already; not retrying ($wire)",
        true,
        this,
    )
    else -> MessagingException("Proton refused the request: $wire", true, this)
}

/**
 * Checks the per-id outcomes of a batch. An outage that stopped the batch is temporary, so the
 * pending command is kept and retried; a refusal of individual ids is permanent.
 */
internal fun List<ProtonOutcome>.requireApplied(operation: String) {
    val failures = filterNot { it.isApplied }
    if (failures.isEmpty()) return

    val codes = failures.joinToString { "${it.id}: ${it.wire}" }
    if (failures.any { it.code == ProtonErrorCode.SESSION_REVOKED }) {
        throw AuthenticationFailedException("Proton ended the session during $operation: $codes")
    }
    val isTemporary = failures.any { it.code in TEMPORARY_CODES }
    throw MessagingException("Proton $operation failed for ${failures.size} message(s): $codes", !isTemporary)
}

private val TEMPORARY_CODES = setOf(
    ProtonErrorCode.TRANSPORT_FAILED,
    ProtonErrorCode.TIMEOUT,
    ProtonErrorCode.NOT_ATTEMPTED,
)
