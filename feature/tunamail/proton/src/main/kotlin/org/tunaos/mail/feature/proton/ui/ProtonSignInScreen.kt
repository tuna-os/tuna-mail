package org.tunaos.mail.feature.proton.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import net.thunderbird.components.ui.bolt.template.Scaffold
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun ProtonSignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: ProtonSignInViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnSignedIn by rememberUpdatedState(onSignedIn)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ProtonSignInEffect.Back -> currentOnBack()
                ProtonSignInEffect.SignedIn -> currentOnSignedIn()
            }
        }
    }

    BackHandler { viewModel.onEvent(ProtonSignInEvent.Back) }

    Scaffold { contentPadding ->
        ProtonSignInContent(
            state = state,
            onEvent = viewModel::onEvent,
            contentPadding = contentPadding,
        )
    }
}
