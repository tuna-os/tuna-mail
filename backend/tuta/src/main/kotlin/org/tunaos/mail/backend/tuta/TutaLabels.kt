package org.tunaos.mail.backend.tuta

import com.fsck.k9.backend.api.BackendStorage
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.legacy.logging.Log
import org.tunaos.tuta.Label
import org.tunaos.tuta.TutaErrorCode
import org.tunaos.tuta.TutaException

/** What a Tuta account tells the user about, outside of any one operation. */
fun interface TutaNotices {
    /**
     * Flagging needs a label and the account has none it can use: it cannot create [TutaLabels.FLAGGED_LABEL] (its
     * plan allows no more labels) and no other label was chosen for flags. Flags stay on this device until the user
     * picks one ([TutaFlagLabelChoice]).
     */
    fun onFlagLabelUnavailable()
}

/**
 * Tuta stores no star, so the app's "flagged" is a Tuta label: flagging a mail applies the label, and a mail carrying
 * it is flagged, here and in Tuta's own apps. The label is the one the user chose for flags ([TutaFlagLabelChoice]),
 * or else [FLAGGED_LABEL], created the first time a mail is flagged. If the account cannot have it (its plan allows
 * no more labels) and no other label was chosen, flags stay on this device and [notices] says so, once.
 */
internal class TutaLabels(
    private val backendStorage: BackendStorage,
    private val connection: TutaConnection,
    private val notices: TutaNotices,
) {
    @Volatile
    private var isKnown = false

    @Volatile
    private var flaggedLabelId: String? = null

    @Volatile
    private var hasNoticed = false

    /** The id of the flagged label as last listed; null when the account has none (flags are not synced then). */
    val flaggedLabel: String? get() = flaggedLabelId

    fun remember(labels: List<Label>) {
        val chosen = backendStorage.getExtraString(EXTRA_FLAG_LABEL)
        flaggedLabelId = labels.firstOrNull { it.id == chosen }?.id
            ?: labels.firstOrNull { it.name.equals(FLAGGED_LABEL, ignoreCase = true) }?.id
        isKnown = true
    }

    /** The flagged label's id, listing the labels first if they are not known yet; null when the account has none. */
    fun flaggedLabelForReading(): String? {
        if (!isKnown) remember(connection.call { listLabels(it) })
        return flaggedLabelId
    }

    /**
     * The flagged label's id, created when missing; null when the account cannot have it. Without a label the list is
     * read again each time, so a label chosen since is picked up.
     */
    fun flaggedLabelForWriting(): String? {
        if (!isKnown || flaggedLabelId == null) remember(connection.call { listLabels(it) })
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
                    if (!hasNoticed) {
                        hasNoticed = true
                        notices.onFlagLabelUnavailable()
                    }
                    null
                }

                else -> throw e
            }
        }
    }

    companion object {
        const val FLAGGED_LABEL = "Flagged"

        /** The backend extra holding the id of the label the user chose for flags. */
        internal const val EXTRA_FLAG_LABEL = "tutaFlagLabel"
        private const val FLAGGED_COLOR = "#f5a623"
        private const val TAG = "TutaLabels"
    }
}
