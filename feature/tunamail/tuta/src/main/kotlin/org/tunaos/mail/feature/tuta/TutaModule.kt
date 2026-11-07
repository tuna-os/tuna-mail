package org.tunaos.mail.feature.tuta

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tunaos.mail.backend.tuta.SdkTutaApi
import org.tunaos.mail.backend.tuta.TutaApi
import org.tunaos.mail.provider.TunaMailProvider
import org.tunaos.tuta.TutaClient

/** Tuta accounts: the SDK client, the backend and the setup screens. */
val featureTunaMailTutaModule = module {
    // One client for the app; it loads the native library on first use.
    single<TutaApi> { SdkTutaApi(TutaClient(clientName = get<String>(named("ClientInfoAppName")))) }
    single<TutaSignIn> { SdkTutaSignIn(api = get()) }

    single<TunaMailProvider>(named(TUTA_SERVER_TYPE)) {
        TutaProvider(
            backendFactory = TutaBackendFactory(
                accountManager = get(),
                backendStorageFactory = get(),
                api = get(),
                sessions = get(),
                flagLabelNotifier = TutaFlagLabelNotifier(androidContext(), get()),
            ),
            api = get(),
        )
    }

    viewModel {
        TutaSignInViewModel(
            signIn = get(),
            accountStateRepository = get(),
            handoff = get(),
        )
    }
}
