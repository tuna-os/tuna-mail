plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "org.tunaos.mail.feature.proton"
    resourcePrefix = "proton_"
}

dependencies {
    api(projects.backend.proton)
    api(projects.feature.tunamail.provider)

    implementation(projects.feature.account.common)
    implementation(projects.legacy.logging)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.feature.account.api)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
