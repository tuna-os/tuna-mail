plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "org.tunaos.mail.feature.tuta"
    resourcePrefix = "tuta_"
}

dependencies {
    api(projects.feature.tunamail.provider)
    implementation(projects.backend.tuta)
    implementation(projects.legacy.logging)

    implementation(libs.androidx.activity.compose)

    testImplementation(projects.feature.account.common)
}
