plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.tunaos.mail.feature.proton"
    resourcePrefix = "proton_"
}

dependencies {
    api(projects.backend.proton)
    api(projects.feature.tunamail.provider)

    implementation(projects.backend.tunasession)
    implementation(projects.feature.account.common)
    implementation(projects.legacy.logging)
    implementation(projects.mail.common)

    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
