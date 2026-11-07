package org.tunaos.mail.backend.tuta

import com.fsck.k9.backend.api.BackendStorage
import org.tunaos.tuta.Label

/**
 * Which of a Tuta account's labels stands for "flagged" ([TutaLabels]). For an account that cannot create the
 * `Flagged` label, the user picks an existing label instead; the account's backend uses it from its next flag change
 * or sync on.
 */
class TutaFlagLabelChoice(
    private val backendStorage: BackendStorage,
    api: TutaApi,
    sessions: TutaSessionSource,
) {
    private val connection = TutaConnection(api, sessions)

    /** The account's labels; throws the app's `MessagingException` when Tuta cannot be asked. */
    suspend fun labels(): List<Label> = connection.suspendCall { listLabels(it) }

    /** The id of the label chosen for flags, or null when none was. */
    fun chosen(): String? = backendStorage.getExtraString(TutaLabels.EXTRA_FLAG_LABEL)?.takeIf { it.isNotEmpty() }

    fun choose(labelId: String) {
        backendStorage.setExtraString(TutaLabels.EXTRA_FLAG_LABEL, labelId)
    }
}
