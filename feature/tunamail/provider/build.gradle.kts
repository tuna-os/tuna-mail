plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "org.tunaos.mail.provider"
}

dependencies {
    api(projects.backend.api)
    api(projects.backend.tunasession)
    api(projects.feature.account.setup)
    api(projects.feature.autodiscovery.api)
    api(projects.core.android.account)
    api(projects.feature.account.api)
    api(projects.feature.mail.account.api)
    api(projects.core.ui.navigation)

    implementation(projects.legacy.logging)

    testImplementation(projects.core.android.account.fake)
}
