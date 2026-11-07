package org.tunaos.mail.feature.tuta

import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

/**
 * Asks for the password of each recipient outside Tuta of a confidential message, with Tuta's strength hint under
 * each. A missing password holds the send; a weak one holds it once, and goes on the next tap. What is typed goes
 * into the composer's [state] only.
 */
internal class TutaPasswordsDialog(
    private val activity: FragmentActivity,
    private val state: TutaComposeState,
    private val sender: List<String>,
) {
    fun show(recipients: List<String>, needing: List<String>, onConfirmed: () -> Unit) {
        val inflater = LayoutInflater.from(activity)
        val content = inflater.inflate(R.layout.tuta_compose_passwords, null)
        val rows = content.findViewById<LinearLayout>(R.id.tuta_compose_password_rows)
        val fields = needing.associateWith { address ->
            val field = inflater.inflate(R.layout.tuta_compose_password_row, rows, false) as TextInputLayout
            field.hint = address
            field.editText?.setText(state.password(address))
            field.helperText = activity.getString(hintFor(address))
            field.editText?.doAfterTextChanged { text ->
                state.setPassword(address, text?.toString().orEmpty())
                field.error = null
                field.helperText = activity.getString(hintFor(address))
            }
            rows.addView(field)
            field
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tuta_compose_passwords_title)
            .setView(content)
            .setPositiveButton(R.string.tuta_compose_passwords_send, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Typed passwords stay in the composer's memory; the dialog itself goes with the activity.
        val dismissOnDestroy = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        activity.lifecycle.addObserver(dismissOnDestroy)
        dialog.setOnDismissListener { activity.lifecycle.removeObserver(dismissOnDestroy) }

        val weakAccepted = mutableMapOf<String, String>()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirm(fields, weakAccepted)) {
                    state.confirm(recipients)
                    dialog.dismiss()
                    onConfirmed()
                }
            }
        }
        dialog.show()
    }

    /** Whether every field holds a password the user may send with; marks those that don't. */
    private fun confirm(fields: Map<String, TextInputLayout>, weakAccepted: MutableMap<String, String>): Boolean {
        var isReady = true
        for ((address, field) in fields) {
            val password = state.password(address)
            when {
                password.isEmpty() -> {
                    field.error = activity.getString(R.string.tuta_compose_password_missing)
                    isReady = false
                }

                !TutaPasswordStrength.isSecure(strength(address)) && weakAccepted[address] != password -> {
                    field.error = activity.getString(R.string.tuta_compose_password_weak_confirm)
                    weakAccepted[address] = password
                    isReady = false
                }
            }
        }
        return isReady
    }

    private fun strength(address: String): Int = TutaPasswordStrength.score(state.password(address), sender + address)

    @StringRes
    private fun hintFor(address: String): Int = strengthHint(strength(address))

    companion object {
        /** The hint under a password of strength [score], in Tuta's three bands. */
        @StringRes
        fun strengthHint(score: Int): Int = when {
            score < TutaPasswordStrength.MEDIUM -> R.string.tuta_compose_password_strength_weak
            !TutaPasswordStrength.isSecure(score) -> R.string.tuta_compose_password_strength_fair
            else -> R.string.tuta_compose_password_strength_strong
        }
    }
}
