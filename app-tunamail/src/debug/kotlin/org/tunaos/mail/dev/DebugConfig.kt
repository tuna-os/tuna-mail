package org.tunaos.mail.dev

import app.k9mail.autodiscovery.api.AutoDiscovery
import app.k9mail.autodiscovery.demo.DemoAutoDiscovery
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.core.featureflag.inject.qualifier.InjectQualifier
import net.thunderbird.core.featureflag.provider.CatalogFeatureFlagProvider
import net.thunderbird.core.featureflag.provider.RuntimeDebugOverrideFeatureFlagProvider
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.tunaos.mail.featureflag.TunaMailFeatureFlagProvider
import org.tunaos.mail.provider.tunaAutoDiscoveries
import org.tunaos.mail.provider.tunaBackendFactories

fun Module.developmentModuleAdditions() {
    single {
        DemoBackendFactory(
            backendStorageFactory = get(),
        )
    }
    single<Map<String, BackendFactory>>(named("developmentBackends")) {
        mapOf("demo" to get<DemoBackendFactory>()) + tunaBackendFactories()
    }
    single<List<AutoDiscovery>>(named("extraAutoDiscoveries")) {
        tunaAutoDiscoveries() + DemoAutoDiscovery()
    }
    single {
        RuntimeDebugOverrideFeatureFlagProvider(
            configStore = get(),
            logger = get(),
        )
    }
    single<CatalogFeatureFlagProvider>(named(InjectQualifier.InMemory)) {
        TunaMailFeatureFlagProvider(debugOverrides = get<RuntimeDebugOverrideFeatureFlagProvider>())
    }
}
