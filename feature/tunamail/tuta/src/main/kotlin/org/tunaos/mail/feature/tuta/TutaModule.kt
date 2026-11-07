package org.tunaos.mail.feature.tuta

import net.thunderbird.feature.mail.message.composer.extension.ComposeExtension
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tunaos.mail.backend.tuta.SdkTutaApi
import org.tunaos.mail.backend.tuta.TutaApi
import org.tunaos.mail.backend.tuta.TutaRecipientPasswords
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
                passwords = get(),
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

    // Confidential mail in the composer; the passwords go from it to the backend in memory only.
    single { TutaRecipientPasswords() }
    single<TutaRecipientLookup> { SdkTutaRecipientLookup(accountManager = get(), api = get(), sessions = get()) }
    single<ComposeExtension>(named(TUTA_SERVER_TYPE)) {
        TutaComposeExtension(accountManager = get(), lookup = get(), passwords = get())
    }
}
