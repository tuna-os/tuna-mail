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

    // Confidential mail in the composer.
    implementation(projects.feature.mail.message.composer.api)
    implementation(libs.android.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    testImplementation(projects.feature.account.common)
}
