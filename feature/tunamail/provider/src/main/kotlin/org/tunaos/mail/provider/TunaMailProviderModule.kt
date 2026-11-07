package org.tunaos.mail.provider

import app.k9mail.autodiscovery.api.AutoDiscovery
import app.k9mail.feature.account.edit.navigation.AccountEditExtension
import app.k9mail.feature.account.setup.navigation.AccountSetupExtension
import net.thunderbird.backend.api.BackendFactory
import org.koin.core.module.dsl.viewModel
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.tunaos.mail.backend.session.tunaSessionModule

/**
 * The provider-neutral part of Tuna Mail's native accounts; each provider's own module binds a [TunaMailProvider], and
 * the app binds the [TunaBackendReset].
 */
val tunaMailProviderModule = module {
    includes(tunaSessionModule)

    single { TunaSignInHandoff() }
    single { TunaAccountSessions(store = get(), handoff = get(), accountManager = get()) }
    single { TunaAccountCleaner(store = get(), accountManager = get(), providers = getAll()) }
    single<AccountSetupExtension> {
        TunaAccountSetupExtension(providers = getAll(), accountStateRepository = get(), handoff = get())
    }
    single<AccountEditExtension> {
        TunaAccountEditExtension(
            providers = getAll(),
            accountManager = get(),
            sessions = get(),
            backendReset = get(),
        )
    }
    single<TunaMxLookup> { MiniDnsMxLookup() }
    viewModel { TunaAccountTypeViewModel(accountStateRepository = get()) }
}

/** The backend factories of every bound [TunaMailProvider], by server type. */
fun Scope.tunaBackendFactories(): Map<String, BackendFactory> =
    getAll<TunaMailProvider>().associate { it.serverType to it.backendFactory }

/** The address discovery of the bound [TunaMailProvider]s. */
fun Scope.tunaAutoDiscoveries(): List<AutoDiscovery> = listOf(TunaProviderAutoDiscovery(getAll(), get()))
