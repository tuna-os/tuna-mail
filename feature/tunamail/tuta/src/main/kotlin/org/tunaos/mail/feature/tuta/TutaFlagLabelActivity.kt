package org.tunaos.mail.feature.tuta

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.components.ui.bolt.atom.RadioGroup
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.organism.BasicDialog
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.legacy.logging.Log
import org.koin.android.ext.android.inject
import org.tunaos.mail.backend.tuta.TutaApi
import org.tunaos.mail.backend.tuta.TutaFlagLabelChoice
import org.tunaos.mail.provider.TunaAccountSessions
import org.tunaos.tuta.Label

/**
 * Lets the user pick which existing label of a Tuta account stands for "flagged", for an account that cannot create
 * the `Flagged` label. Opened from [TutaFlagLabelNotifier]'s notice.
 */
class TutaFlagLabelActivity : ComponentActivity() {
    private val accountManager: LegacyAccountManager by inject()
    private val backendStorageFactory: BackendStorageFactory by inject()
    private val api: TutaApi by inject()
    private val sessions: TunaAccountSessions by inject()
    private val themeProvider: FeatureThemeProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = intent.getStringExtra(EXTRA_ACCOUNT_ID)
            ?.let { runCatching { AccountIdFactory.of(it) }.getOrNull() }
            ?.let(accountManager::findById)
        if (account == null) {
            finish()
            return
        }

        val choice = TutaFlagLabelChoice(
            backendStorage = backendStorageFactory.createBackendStorage(account.id),
            api = api,
            sessions = sessions.tutaSessionSource(account.id, account.incomingServerSettings.username),
        )
        setContent {
            themeProvider.WithTheme {
                TutaFlagLabelDialog(choice = choice, onDone = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_ACCOUNT_ID = "accountId"

        fun intent(context: Context, accountId: AccountId): Intent =
            Intent(context, TutaFlagLabelActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_ID, accountId.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private sealed interface LabelsState {
    data object Loading : LabelsState

    data object Failed : LabelsState

    data class Loaded(val labels: ImmutableList<Label>, val chosen: String?) : LabelsState
}

@Composable
private fun TutaFlagLabelDialog(choice: TutaFlagLabelChoice, onDone: () -> Unit) {
    var state by remember { mutableStateOf<LabelsState>(LabelsState.Loading) }
    var selected by remember { mutableStateOf<Label?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(choice) {
        state = try {
            withContext(Dispatchers.IO) { LabelsState.Loaded(choice.labels().toImmutableList(), choice.chosen()) }
        } catch (e: MessagingException) {
            Log.w(TAG, e, "Could not list the labels of a Tuta account")
            LabelsState.Failed
        }
    }

    BasicDialog(
        headlineText = stringResource(R.string.tuta_flag_label_title),
        supportingText = stringResource(R.string.tuta_flag_label_explanation),
        onDismissRequest = onDone,
        content = {
            when (val current = state) {
                LabelsState.Loading -> TextBodyMedium(stringResource(R.string.tuta_flag_label_loading))

                LabelsState.Failed -> TextBodyMedium(stringResource(R.string.tuta_flag_label_error))

                is LabelsState.Loaded -> if (current.labels.isEmpty()) {
                    TextBodyMedium(stringResource(R.string.tuta_flag_label_none))
                } else {
                    RadioGroup(
                        onClick = { selected = it },
                        options = current.labels,
                        optionTitle = { it.name },
                        selectedOption = selected ?: current.labels.firstOrNull { it.id == current.chosen },
                    )
                }
            }
        },
        buttons = {
            ButtonText(text = stringResource(R.string.tuta_flag_label_cancel), onClick = onDone)
            ButtonText(
                text = stringResource(R.string.tuta_flag_label_use),
                enabled = selected != null,
                onClick = {
                    val label = selected ?: return@ButtonText
                    scope.launch {
                        withContext(Dispatchers.IO) { choice.choose(label.id) }
                        onDone()
                    }
                },
            )
        },
        contentPadding = PaddingValues(horizontal = BoltTheme.spacings.triple),
    )
}

private const val TAG = "TutaFlagLabelActivity"
