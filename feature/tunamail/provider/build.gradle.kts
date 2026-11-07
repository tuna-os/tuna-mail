plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "org.tunaos.mail.provider"
    resourcePrefix = "tunamail_"
}

dependencies {
    api(projects.backend.api)
    api(projects.backend.tunasession)
    api(projects.feature.account.setup)
    api(projects.feature.account.edit)
    api(projects.feature.autodiscovery.api)
    api(projects.core.android.account)
    api(projects.feature.account.api)
    api(projects.feature.mail.account.api)
    api(projects.core.ui.navigation)

    implementation(projects.legacy.logging)
    implementation(libs.minidns.hla)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(projects.core.android.account.fake)
}
