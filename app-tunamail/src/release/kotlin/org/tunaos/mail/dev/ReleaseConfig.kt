package org.tunaos.mail.dev

import app.k9mail.autodiscovery.api.AutoDiscovery
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.core.featureflag.inject.qualifier.InjectQualifier
import net.thunderbird.core.featureflag.provider.CatalogFeatureFlagProvider
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.tunaos.mail.featureflag.TunaMailFeatureFlagProvider

fun Module.developmentModuleAdditions() {
    single<Map<String, BackendFactory>>(named("developmentBackends")) {
        emptyMap()
    }
    single<List<AutoDiscovery>>(named("extraAutoDiscoveries")) {
        emptyList()
    }
    single<CatalogFeatureFlagProvider>(named(InjectQualifier.InMemory)) {
        TunaMailFeatureFlagProvider()
    }
}
