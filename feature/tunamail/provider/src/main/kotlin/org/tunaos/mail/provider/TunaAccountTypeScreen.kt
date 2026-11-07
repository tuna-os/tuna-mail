package org.tunaos.mail.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.k9mail.feature.account.common.ui.AppTitleTopHeader
import app.k9mail.feature.account.common.ui.WizardNavigationBar
import app.k9mail.feature.account.common.ui.WizardNavigationBarState
import kotlinx.collections.immutable.ImmutableList
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.template.ResponsiveWidthContainer
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.common.provider.BrandNameProvider
import org.koin.compose.koinInject

/**
 * Asks which kind of account an address is when the setup is configured by hand: one of the [providers], whose
 * sign-in follows, or any other ([onOtherClick], the IMAP and POP3 server settings).
 */
@Composable
internal fun TunaAccountTypeScreen(
    providers: ImmutableList<TunaMailProvider>,
    onProviderClick: (TunaMailProvider) -> Unit,
    onOtherClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    brandNameProvider: BrandNameProvider = koinInject(),
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            AppTitleTopHeader(
                title = brandNameProvider.brandName,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
            )
        },
        bottomBar = {
            WizardNavigationBar(
                onNextClick = {},
                onBackClick = onBack,
                state = WizardNavigationBarState(showNext = false),
            )
        },
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars),
    ) { innerPadding ->
        ResponsiveWidthContainer(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) { responsiveWidthPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(responsiveWidthPadding)
                    .padding(BoltTheme.spacings.quadruple),
            ) {
                TextTitleMedium(text = stringResource(R.string.tunamail_account_type_title))
                Spacer(modifier = Modifier.height(BoltTheme.spacings.default))
                TextBodyMedium(text = stringResource(R.string.tunamail_account_type_explanation))
                Spacer(modifier = Modifier.height(BoltTheme.spacings.double))

                for (provider in providers) {
                    AccountTypeButton(text = provider.displayName, onClick = { onProviderClick(provider) })
                }
                AccountTypeButton(text = stringResource(R.string.tunamail_account_type_other), onClick = onOtherClick)
            }
        }
    }
}

@Composable
private fun AccountTypeButton(text: String, onClick: () -> Unit) {
    ButtonOutlined(
        text = text,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BoltTheme.spacings.half),
        contentPadding = PaddingValues(BoltTheme.spacings.double),
    )
}
