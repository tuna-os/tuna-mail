plugins {
    id(ThunderbirdPlugins.Library.android)
}

android {
    namespace = "org.tunaos.mail.backend.proton"
}

dependencies {
    api(projects.backend.api)
    api(libs.tb.mobile.components.core.outcome)
    // Fetched into third_party/maven by tools/tunamail/tunamail.settings.gradle.kts, which pins its digest.
    api("org.tunaos.proton:proton-sdk-android:0.1.2")

    implementation(projects.core.common)
    implementation(projects.feature.mail.folder.api)
    implementation(projects.legacy.logging)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.backend.testing)
    testImplementation(projects.mail.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
