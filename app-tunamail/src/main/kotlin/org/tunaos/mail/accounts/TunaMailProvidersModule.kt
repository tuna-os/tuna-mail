package org.tunaos.mail.accounts

import org.koin.dsl.module
import org.tunaos.mail.feature.proton.featureTunaMailProtonModule
import org.tunaos.mail.feature.tuta.featureTunaMailTutaModule
import org.tunaos.mail.provider.tunaMailProviderModule

/**
 * Tuna Mail's native accounts: the provider-neutral wiring and one module per provider. The backends and the address
 * discovery reach the app through `developmentModuleAdditions` (`tunaBackendFactories`, `tunaAutoDiscoveries`).
 */
val tunaMailProvidersModule = module {
    includes(tunaMailProviderModule)
    includes(featureTunaMailProtonModule)
    includes(featureTunaMailTutaModule)
}
