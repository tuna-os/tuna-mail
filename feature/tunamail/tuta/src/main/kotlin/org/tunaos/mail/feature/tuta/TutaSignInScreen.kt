package org.tunaos.mail.feature.tuta

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.k9mail.feature.account.common.ui.AppTitleTopHeader
import app.k9mail.feature.account.common.ui.WizardNavigationBar
import app.k9mail.feature.account.common.ui.WizardNavigationBarState
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.molecule.input.PasswordInput
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.template.ResponsiveWidthContainer
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.common.provider.BrandNameProvider
import org.koin.compose.koinInject

/** Signs a Tuta account in during setup: password, then a TOTP code if the account asks for one. */
@Composable
internal fun TutaSignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: TutaSignInViewModel,
    modifier: Modifier = Modifier,
    brandNameProvider: BrandNameProvider = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(state.isSignedIn) {
        if (state.isSignedIn) onSignedIn()
    }

    val goBack = { if (!viewModel.onBack()) onBack() }
    BackHandler(onBack = goBack)

    Scaffold(
        topBar = {
            AppTitleTopHeader(
                title = brandNameProvider.brandName,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
            )
        },
        bottomBar = {
            WizardNavigationBar(
                onNextClick = viewModel::submit,
                onBackClick = goBack,
                state = WizardNavigationBarState(
                    isNextEnabled = !state.isLoading && !state.isSignedIn,
                    isBackEnabled = !state.isLoading,
                ),
                modifier = Modifier.imePadding(),
            )
        },
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars),
    ) { innerPadding ->
        TutaSignInContent(
            state = state,
            onPasswordChange = viewModel::onPasswordChanged,
            onTotpChange = viewModel::onTotpChanged,
            contentPadding = innerPadding,
        )
    }
}

@Composable
private fun TutaSignInContent(
    state: TutaSignInState,
    onPasswordChange: (String) -> Unit,
    onTotpChange: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    ResponsiveWidthContainer(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .imePadding(),
    ) { responsiveWidthPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(responsiveWidthPadding)
                .padding(BoltTheme.spacings.quadruple),
        ) {
            TextTitleMedium(text = stringResource(R.string.tuta_sign_in_title))
            Spacer(modifier = Modifier.height(BoltTheme.spacings.default))
            TextBodyMedium(text = state.email)
            Spacer(modifier = Modifier.height(BoltTheme.spacings.double))

            if (state.isLoading || state.isSignedIn) {
                LoadingView(message = stringResource(R.string.tuta_sign_in_progress))
            } else {
                SignInInputs(state, onPasswordChange, onTotpChange)
            }

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(BoltTheme.spacings.double))
                TextBodyMedium(
                    text = stringResource(error.messageResource()),
                    color = BoltTheme.colors.error,
                )
            }
        }
    }
}

@Composable
private fun SignInInputs(
    state: TutaSignInState,
    onPasswordChange: (String) -> Unit,
    onTotpChange: (String) -> Unit,
) {
    when (state.step) {
        Step.PASSWORD -> PasswordInput(
            password = state.password,
            onPasswordChange = onPasswordChange,
            isRequired = true,
            contentPadding = PaddingValues(),
        )

        Step.SECOND_FACTOR -> {
            TextBodyMedium(text = stringResource(R.string.tuta_sign_in_totp_explanation))
            Spacer(modifier = Modifier.height(BoltTheme.spacings.double))
            TextInput(
                text = state.totp,
                onTextChange = onTotpChange,
                label = stringResource(R.string.tuta_sign_in_totp_label),
                isRequired = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                contentPadding = PaddingValues(),
            )
        }
    }
}

private fun TutaSignInError.messageResource(): Int = when (this) {
    TutaSignInError.PASSWORD_REQUIRED -> R.string.tuta_sign_in_error_password_required
    TutaSignInError.TOTP_REQUIRED -> R.string.tuta_sign_in_error_totp_required
    TutaSignInError.LOGIN_FAILED -> R.string.tuta_sign_in_error_login_failed
    TutaSignInError.INVALID_TOTP -> R.string.tuta_sign_in_error_invalid_totp
    TutaSignInError.RATE_LIMITED -> R.string.tuta_sign_in_error_rate_limited
    TutaSignInError.PENDING_SESSION_EXPIRED -> R.string.tuta_sign_in_error_pending_session_expired
    TutaSignInError.UNSUPPORTED_SECOND_FACTOR -> R.string.tuta_sign_in_error_unsupported_second_factor
    TutaSignInError.NETWORK -> R.string.tuta_sign_in_error_network
    TutaSignInError.CLIENT_OUTDATED -> R.string.tuta_sign_in_error_client_outdated
    TutaSignInError.UNKNOWN -> R.string.tuta_sign_in_error_unknown
}
