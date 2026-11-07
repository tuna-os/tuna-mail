package org.tunaos.mail

import app.k9mail.feature.widget.shortcut.LauncherShortcutActivity
import com.fsck.k9.AppConfig
import com.fsck.k9.DefaultAppConfig
import com.fsck.k9.activity.MessageCompose
import net.thunderbird.app.common.appCommonModule
import net.thunderbird.core.common.oauth.OAuthConfigurationFactory
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tunaos.mail.accounts.tunaMailProvidersModule
import org.tunaos.mail.auth.TunaMailOAuthConfigurationFactory
import org.tunaos.mail.dev.developmentModuleAdditions
import org.tunaos.mail.feature.featureModule
import org.tunaos.mail.featureflag.tunaMailFeatureFlagModule
import org.tunaos.mail.provider.providerModule
import org.tunaos.mail.widget.MessageListWidgetProvider
import org.tunaos.mail.widget.UnreadWidgetProvider
import org.tunaos.mail.widget.widgetModule

val appModule = module {
    includes(tunaMailFeatureFlagModule)
    includes(appCommonModule)

    includes(widgetModule)
    includes(featureModule)
    includes(providerModule)
    includes(tunaMailProvidersModule)

    single(named("ClientInfoAppName")) { BuildConfig.CLIENT_INFO_APP_NAME }
    single(named("ClientInfoAppVersion")) { BuildConfig.VERSION_NAME }
    single<AppConfig> { appConfig }
    single<OAuthConfigurationFactory> {
        TunaMailOAuthConfigurationFactory(
            applicationId = BuildConfig.APPLICATION_ID,
            googleClientId = BuildConfig.OAUTH_GOOGLE_CLIENT_ID,
            microsoftClientId = BuildConfig.OAUTH_MICROSOFT_CLIENT_ID,
            microsoftSignatureHash = BuildConfig.OAUTH_MICROSOFT_SIGNATURE_HASH,
        )
    }

    developmentModuleAdditions()
}

val appConfig = DefaultAppConfig(
    componentsToDisable = listOf(
        MessageCompose::class.java,
        LauncherShortcutActivity::class.java,
        UnreadWidgetProvider::class.java,
        MessageListWidgetProvider::class.java,
    ),
)
