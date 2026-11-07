package org.tunaos.mail.feature.proton

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tunaos.mail.feature.proton.ui.ProtonSignInViewModel
import org.tunaos.mail.provider.TunaMailProvider
import org.tunaos.proton.ProtonClient

/** Binds Proton Mail as a [TunaMailProvider]; one SDK client serves every account. */
val featureTunaMailProtonModule = module {
    single { ProtonClient() }
    single<TunaMailProvider>(named(PROTON_SERVER_TYPE)) {
        ProtonMailProvider(
            client = get(),
            backendFactory = ProtonBackendFactory(
                backendStorageFactory = get(),
                accountManager = get(),
                sessions = get(),
                client = get(),
                pollConditions = AndroidProtonPollConditions(androidContext()),
            ),
        )
    }
    single<ProtonLogin> { SdkProtonLogin(client = get()) }
    factory { ProtonSignIn(login = get()) }
    viewModel {
        ProtonSignInViewModel(
            signIn = get(),
            accountStateRepository = get(),
            handoff = get(),
        )
    }
}
