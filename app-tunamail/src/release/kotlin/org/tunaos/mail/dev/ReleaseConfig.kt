package org.tunaos.mail.dev

import app.k9mail.autodiscovery.api.AutoDiscovery
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.core.featureflag.inject.qualifier.InjectQualifier
import net.thunderbird.core.featureflag.provider.CatalogFeatureFlagProvider
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.tunaos.mail.featureflag.TunaMailFeatureFlagProvider
import org.tunaos.mail.provider.tunaAutoDiscoveries
import org.tunaos.mail.provider.tunaBackendFactories

fun Module.developmentModuleAdditions() {
    // Despite the name, these are also where Tuna Mail's native providers plug into the backends and the setup.
    single<Map<String, BackendFactory>>(named("developmentBackends")) {
        tunaBackendFactories()
    }
    single<List<AutoDiscovery>>(named("extraAutoDiscoveries")) {
        tunaAutoDiscoveries()
    }
    single<CatalogFeatureFlagProvider>(named(InjectQualifier.InMemory)) {
        TunaMailFeatureFlagProvider()
    }
}
