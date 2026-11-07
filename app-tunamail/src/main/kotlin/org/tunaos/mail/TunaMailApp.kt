package org.tunaos.mail

import kotlinx.coroutines.CoroutineScope
import net.thunderbird.app.common.FeatureFlagApplication
import org.koin.android.ext.android.get
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.tunaos.mail.provider.TunaAccountCleaner

class TunaMailApp : FeatureFlagApplication() {
    // The feature flag catalog only knows the "k9" and "thunderbird" apps. Tuna Mail is wired like K-9 Mail, so it
    // takes K-9 Mail's catalog overrides; flags Tuna Mail must not follow are pinned by TunaMailFeatureFlagProvider.
    override val appName: String = "k9"
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val buildType: String = BuildConfig.BUILD_TYPE

    override fun provideAppModule(): Module = appModule

    override fun onCreate() {
        super.onCreate()
        // Revoke and delete the sessions of removed Tuta and Proton accounts.
        get<TunaAccountCleaner>().start(get<CoroutineScope>(named("AppCoroutineScope")))
    }
}
