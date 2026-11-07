package org.tunaos.mail.feature.proton.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.k9mail.feature.account.common.ui.AppTitleTopHeader
import app.k9mail.feature.account.common.ui.WizardNavigationBar
import app.k9mail.feature.account.common.ui.WizardNavigationBarState
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.textfield.TextFieldOutlinedPassword
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.molecule.input.PasswordInput
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.template.ResponsiveWidthContainer
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import org.tunaos.mail.feature.proton.ProtonSignInError
import org.tunaos.mail.feature.proton.ProtonSignInState
import org.tunaos.mail.feature.proton.ProtonSignInStep
import org.tunaos.mail.feature.proton.R

@Composable
internal fun ProtonSignInContent(
    state: ProtonSignInState,
    onEvent: (ProtonSignInEvent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    ResponsiveWidthContainer(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .imePadding(),
    ) { responsiveWidthPadding ->
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(responsiveWidthPadding),
            ) {
                AppTitleTopHeader(title = stringResource(R.string.proton_sign_in_title))
                when {
                    state.isLoading -> LoadingView(
                        message = stringResource(R.string.proton_sign_in_signing_in),
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.step == ProtonSignInStep.HUMAN_VERIFICATION -> VerificationStep(state, onEvent)
                    else -> InputStep(state, onEvent)
                }
            }

            WizardNavigationBar(
                onNextClick = { onEvent(ProtonSignInEvent.Submit) },
                onBackClick = { onEvent(ProtonSignInEvent.Back) },
                nextButtonText = stringResource(R.string.proton_sign_in_sign_in),
                state = WizardNavigationBarState(
                    showNext = state.step != ProtonSignInStep.HUMAN_VERIFICATION,
                    isNextEnabled = !state.isLoading,
                ),
            )
        }
    }
}

@Composable
private fun InputStep(state: ProtonSignInState, onEvent: (ProtonSignInEvent) -> Unit) {
    val error = state.error?.let { stringResource(it.messageRes) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(BoltTheme.spacings.quadruple),
    ) {
        TextBodyMedium(text = state.email)
        Spacer(modifier = Modifier.height(BoltTheme.spacings.double))
        when (state.step) {
            ProtonSignInStep.TOTP -> {
                TextBodyMedium(text = stringResource(R.string.proton_sign_in_totp_hint))
                TextInput(
                    text = state.totp,
                    label = stringResource(R.string.proton_sign_in_totp_label),
                    errorMessage = error,
                    onTextChange = { onEvent(ProtonSignInEvent.TotpChanged(it)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    contentPadding = PaddingValues(),
                )
            }
            ProtonSignInStep.MAILBOX_PASSWORD -> {
                TextBodyMedium(text = stringResource(R.string.proton_sign_in_mailbox_password_hint))
                TextFieldOutlinedPassword(
                    value = state.mailboxPassword,
                    onValueChange = { onEvent(ProtonSignInEvent.MailboxPasswordChanged(it)) },
                    label = stringResource(R.string.proton_sign_in_mailbox_password_label),
                    hasError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { TextBodyMedium(text = it, color = BoltTheme.colors.error) }
            }
            else -> {
                TextBodyMedium(text = stringResource(R.string.proton_sign_in_password_hint))
                PasswordInput(
                    password = state.password,
                    errorMessage = error,
                    onPasswordChange = { onEvent(ProtonSignInEvent.PasswordChanged(it)) },
                    contentPadding = PaddingValues(),
                )
            }
        }
    }
}

@Composable
private fun VerificationStep(state: ProtonSignInState, onEvent: (ProtonSignInEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TextBodyMedium(
            text = stringResource(R.string.proton_sign_in_verification_hint),
            modifier = Modifier.padding(BoltTheme.spacings.double),
        )
        state.error?.let {
            TextBodyMedium(
                text = stringResource(it.messageRes),
                color = BoltTheme.colors.error,
                modifier = Modifier.padding(horizontal = BoltTheme.spacings.double),
            )
        }
        val url = state.verificationUrl ?: return@Column
        HumanVerificationView(
            url = url,
            onMessage = { onEvent(ProtonSignInEvent.VerificationMessage(it)) },
            onCannotShow = { onEvent(ProtonSignInEvent.VerificationUnsupported) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@get:StringRes
internal val ProtonSignInError.messageRes: Int
    get() = when (this) {
        ProtonSignInError.WRONG_PASSWORD -> R.string.proton_sign_in_error_wrong_password
        ProtonSignInError.INVALID_TOTP -> R.string.proton_sign_in_error_invalid_totp
        ProtonSignInError.TOTP_REJECTED -> R.string.proton_sign_in_error_totp_rejected
        ProtonSignInError.UNSUPPORTED_SECOND_FACTOR -> R.string.proton_sign_in_error_unsupported_second_factor
        ProtonSignInError.MAILBOX_PASSWORD_REJECTED -> R.string.proton_sign_in_error_mailbox_password_rejected
        ProtonSignInError.HUMAN_VERIFICATION_FAILED -> R.string.proton_sign_in_error_verification_failed
        ProtonSignInError.HUMAN_VERIFICATION_UNAVAILABLE -> R.string.proton_sign_in_error_verification_unavailable
        ProtonSignInError.HUMAN_VERIFICATION_CANCELLED -> R.string.proton_sign_in_error_verification_cancelled
        ProtonSignInError.CLIENT_OUTDATED -> R.string.proton_sign_in_error_client_outdated
        ProtonSignInError.UNREACHABLE -> R.string.proton_sign_in_error_unreachable
        ProtonSignInError.FAILED -> R.string.proton_sign_in_error_failed
    }
