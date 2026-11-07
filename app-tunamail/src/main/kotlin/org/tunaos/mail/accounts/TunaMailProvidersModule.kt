package org.tunaos.mail.accounts

import com.fsck.k9.backend.BackendManager
import org.koin.dsl.module
import org.tunaos.mail.feature.proton.featureTunaMailProtonModule
import org.tunaos.mail.feature.tuta.featureTunaMailTutaModule
import org.tunaos.mail.provider.TunaBackendReset
import org.tunaos.mail.provider.tunaMailProviderModule

/**
 * Tuna Mail's native accounts: the provider-neutral wiring and one module per provider. The backends and the address
 * discovery reach the app through `developmentModuleAdditions` (`tunaBackendFactories`, `tunaAutoDiscoveries`).
 */
val tunaMailProvidersModule = module {
    includes(tunaMailProviderModule)
    includes(featureTunaMailProtonModule)
    includes(featureTunaMailTutaModule)

    // An account that signed in again drops its open backend, so the next use (and push) runs on the new session.
    single { TunaBackendReset(get<BackendManager>()::removeBackend) }
}
