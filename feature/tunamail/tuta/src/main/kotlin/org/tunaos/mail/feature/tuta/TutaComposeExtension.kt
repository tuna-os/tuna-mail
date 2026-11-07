package org.tunaos.mail.feature.tuta

import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.message.composer.extension.ComposeExtension
import net.thunderbird.feature.mail.message.composer.extension.ComposeOptions
import net.thunderbird.legacy.logging.Log
import org.tunaos.mail.backend.tuta.TUTA_CONFIDENTIAL_HEADER
import org.tunaos.mail.backend.tuta.TutaApi
import org.tunaos.mail.backend.tuta.TutaRecipientPasswords
import org.tunaos.mail.provider.TunaAccountSessions
import org.tunaos.tuta.ContactPassword
import org.tunaos.tuta.TutaException
import org.tunaos.tuta.TutaSession

/** Tuta's confidential mail in the composer, for Tuta accounts only. */
class TutaComposeExtension(
    private val accountManager: LegacyAccountManager,
    private val lookup: TutaRecipientLookup,
    private val passwords: TutaRecipientPasswords,
) : ComposeExtension {
    override fun optionsFor(activity: FragmentActivity, accountUuid: String): ComposeOptions? {
        val account = accountManager.findById(AccountIdFactory.of(accountUuid))
            ?.takeIf { it.incomingServerSettings.type == TUTA_SERVER_TYPE }
            ?: return null
        return TutaComposeOptions(
            activity = activity,
            accountUuid = accountUuid,
            sender = listOfNotNull(account.email, account.name),
            lookup = lookup,
            passwords = passwords,
        )
    }
}

/** Asks a Tuta account which of some addresses are outside Tuta, and the passwords their contacts keep. */
fun interface TutaRecipientLookup {
    /** Per address, as `contact_passwords` answers; null when the account could not say. */
    suspend fun lookup(accountUuid: String, addresses: List<String>): List<ContactPassword>?
}

/** [TutaRecipientLookup] through the account's session. */
class SdkTutaRecipientLookup(
    private val accountManager: LegacyAccountManager,
    private val api: TutaApi,
    private val sessions: TunaAccountSessions,
) : TutaRecipientLookup {
    override suspend fun lookup(accountUuid: String, addresses: List<String>): List<ContactPassword>? =
        withContext(Dispatchers.IO) {
            val accountId = AccountIdFactory.of(accountUuid)
            val email = accountManager.findById(accountId)?.incomingServerSettings?.username
            val session = email?.let { sessions.sessionFor(accountId, TUTA_SERVER_TYPE, it) }
            if (session == null) {
                Log.w(TAG, "No Tuta session to look up the recipients of a message (account %s)", accountUuid)
                return@withContext null
            }
            try {
                val tutaSession = TutaSession.fromJson(session)
                addresses.chunked(LOOKUP_SIZE).flatMap { api.contactPasswords(tutaSession, it) }
            } catch (e: TutaException) {
                Log.w(TAG, "Could not look up a Tuta message's recipients (account %s): %s", accountUuid, e.wireCode)
                null
            }
        }

    private companion object {
        const val TAG = "TutaRecipientLookup"

        /** The most addresses one `contact_passwords` request takes. */
        const val LOOKUP_SIZE = 100
    }
}

/** A composer's [TutaComposeState], kept across configuration changes, in memory only. */
class TutaComposeViewModel : ViewModel() {
    val state = TutaComposeState()
    var isLookingUp = false
}

private class TutaComposeOptions(
    private val activity: FragmentActivity,
    private val accountUuid: String,
    private val sender: List<String>,
    private val lookup: TutaRecipientLookup,
    private val passwords: TutaRecipientPasswords,
) : ComposeOptions {
    private val viewModel = ViewModelProvider(activity)
        .get("$VIEW_MODEL_KEY:$accountUuid", TutaComposeViewModel::class.java)
    private val state get() = viewModel.state

    /** The recipients of the send that last passed [beforeSend], whose passwords go with the built message. */
    private var sendRecipients: List<String> = emptyList()

    override fun onPrepareOptionsMenu(menu: Menu) {
        val item = menu.findItem(R.id.tuta_compose_confidential)
            ?: menu.add(Menu.NONE, R.id.tuta_compose_confidential, Menu.NONE, R.string.tuta_compose_confidential)
                .setCheckable(true)
                .apply { setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
        item.isChecked = state.isConfidential
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.tuta_compose_confidential) return false
        state.setConfidential(!state.isConfidential)
        item.isChecked = state.isConfidential
        val explanation = if (state.isConfidential) {
            R.string.tuta_compose_confidential_on
        } else {
            R.string.tuta_compose_confidential_off
        }
        Toast.makeText(activity, explanation, Toast.LENGTH_LONG).show()
        return true
    }

    override fun beforeSend(recipients: List<String>, send: Runnable): Boolean =
        when (val check = state.check(recipients)) {
            SendCheck.Ready -> {
                sendRecipients = recipients
                true
            }

            is SendCheck.Lookup -> {
                lookUp(check.addresses, send)
                false
            }

            is SendCheck.AskPasswords -> {
                TutaPasswordsDialog(activity, state, sender).show(recipients, check.addresses) { send.run() }
                false
            }
        }

    override fun headersFor(messageId: String, isDraft: Boolean): Map<String, String> {
        if (!isDraft && state.isConfidential) {
            passwords.put(messageId, state.passwordsFor(sendRecipients))
        }
        return state.headers()
    }

    override fun onDraftOpened(header: (name: String) -> String?) {
        state.onDraftOpened(header(TUTA_CONFIDENTIAL_HEADER))
    }

    private fun lookUp(addresses: List<String>, send: Runnable) {
        if (viewModel.isLookingUp) return
        viewModel.isLookingUp = true
        activity.lifecycleScope.launch {
            try {
                val rows = lookup.lookup(accountUuid, addresses)
                state.adopt(addresses, rows)
                if (rows == null) {
                    Toast.makeText(activity, R.string.tuta_compose_lookup_failed, Toast.LENGTH_LONG).show()
                }
            } finally {
                viewModel.isLookingUp = false
            }
            send.run()
        }
    }

    private companion object {
        const val VIEW_MODEL_KEY = "org.tunaos.mail.feature.tuta.compose"
    }
}
