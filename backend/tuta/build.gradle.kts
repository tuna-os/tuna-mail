plugins {
    id(ThunderbirdPlugins.Library.android)
}

android {
    namespace = "org.tunaos.mail.backend.tuta"
}

dependencies {
    api(projects.backend.api)
    // Fetched into third_party/maven by tools/tunamail/tunamail.settings.gradle.kts (GPL-3.0-or-later).
    api("org.tunaos.tuta:tuta-sdk-android:0.2.1")
    implementation(libs.kotlinx.serialization.json)

    implementation(projects.core.common)
    implementation(projects.feature.mail.folder.api)
    implementation(projects.legacy.logging)

    testImplementation(projects.backend.testing)
    testImplementation(projects.mail.testing)
}
