package org.tunaos.mail.backend.tuta

import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.legacy.logging.Log
import org.tunaos.tuta.Label
import org.tunaos.tuta.TutaErrorCode
import org.tunaos.tuta.TutaException

/**
 * Tuta stores no star, so the app's "flagged" is the Tuta label [FLAGGED_LABEL]: flagging a mail applies the label,
 * and a mail carrying it is flagged, here and in Tuta's own apps. The label is created the first time a mail is
 * flagged. If the account cannot have it (its plan allows no more labels), flags stay on this device.
 */
internal class TutaLabels(private val connection: TutaConnection) {
    @Volatile
    private var isKnown = false

    @Volatile
    private var flaggedLabelId: String? = null

    /** The id of the flagged label as last listed; null when the account has none (flags are not synced then). */
    val flaggedLabel: String? get() = flaggedLabelId

    fun remember(labels: List<Label>) {
        flaggedLabelId = labels.firstOrNull { it.name.equals(FLAGGED_LABEL, ignoreCase = true) }?.id
        isKnown = true
    }

    /** The flagged label's id, created when missing; null when the account cannot have it. */
    fun flaggedLabelForWriting(): String? {
        if (!isKnown) remember(connection.call { listLabels(it) })
        flaggedLabelId?.let { return it }

        return try {
            connection.call { createLabel(it, FLAGGED_LABEL, FLAGGED_COLOR) }.also { flaggedLabelId = it }
        } catch (e: MessagingException) {
            when ((e.cause as? TutaException)?.code) {
                TutaErrorCode.LABEL_EXISTS -> {
                    remember(connection.call { listLabels(it) })
                    flaggedLabelId
                }

                TutaErrorCode.LIMIT_REACHED, TutaErrorCode.LABEL_FAILED -> {
                    Log.w(TAG, "Tuta account cannot have the %s label; flags stay on this device", FLAGGED_LABEL)
                    null
                }

                else -> throw e
            }
        }
    }

    companion object {
        const val FLAGGED_LABEL = "Flagged"
        private const val FLAGGED_COLOR = "#f5a623"
        private const val TAG = "TutaLabels"
    }
}
