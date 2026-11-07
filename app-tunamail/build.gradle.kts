import java.util.Properties

plugins {
    id(ThunderbirdPlugins.App.androidCompose)
}

// The app's name and application id come from brand.properties and nowhere else.
val brand = Properties().apply {
    providers.fileContents(layout.projectDirectory.file("brand.properties")).asText.get().reader().use { load(it) }
}
val brandApplicationId: String = brand.getProperty("applicationId")
val brandAppName: String = brand.getProperty("appName")

/**
 * Reads a build setting from the Gradle property [property], falling back to the environment variable [env],
 * and to an empty string. Empty disables whatever the setting configures (see docs/tunamail/oauth.md).
 */
fun buildSetting(property: String, env: String): String {
    val value = providers.gradleProperty(property)
        .orElse(providers.environmentVariable(env))
        .getOrElse("")
        .trim()
    require(value.matches(Regex("[A-Za-z0-9._~%+/=-]*"))) { "$property contains unexpected characters" }
    return value
}

val googleClientId = buildSetting("tunamail.oauth.google.clientId", "TUNAMAIL_GOOGLE_CLIENT_ID")
val microsoftClientId = buildSetting("tunamail.oauth.microsoft.clientId", "TUNAMAIL_MICROSOFT_CLIENT_ID")
val microsoftSignatureHash = buildSetting(
    "tunamail.oauth.microsoft.signatureHash",
    "TUNAMAIL_MICROSOFT_SIGNATURE_HASH",
)

val testCoverageEnabled = providers
    .gradleProperty("testCoverageEnabled")
    .isPresent

android {
    namespace = "org.tunaos.mail"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = brandApplicationId
        testApplicationId = "$brandApplicationId.tests"

        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "CLIENT_INFO_APP_NAME", "\"$brandAppName\"")
        resValue("string", "app_name", brandAppName)

        buildConfigField("String", "OAUTH_GOOGLE_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "OAUTH_MICROSOFT_CLIENT_ID", "\"$microsoftClientId\"")
        buildConfigField("String", "OAUTH_MICROSOFT_SIGNATURE_HASH", "\"$microsoftSignatureHash\"")

        ndk {
            // The native Tuta and Proton libraries are built for these two ABIs only.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        resValues = true
    }

    androidResources {
        // Keep in sync with the resource string array "supported_languages"
        localeFilters += listOf(
            "ar",
            "be",
            "bg",
            "br",
            "ca",
            "co",
            "cs",
            "cy",
            "da",
            "de",
            "el",
            "en",
            "en-rGB",
            "eo",
            "es",
            "et",
            "eu",
            "fa",
            "fi",
            "fr",
            "fy",
            "ga",
            "gd",
            "gl",
            "hr",
            "hu",
            "in",
            "is",
            "it",
            "iw",
            "ja",
            "ko",
            "lt",
            "lv",
            "nb",
            "nl",
            "nn",
            "pl",
            "pt-rBR",
            "pt-rPT",
            "ro",
            "ru",
            "sk",
            "sl",
            "sq",
            "sr",
            "sv",
            "ta-rIN",
            "tr",
            "uk",
            "vi",
            "zh-rCN",
            "zh-rTW",
        )
    }

    signingConfigs {
        // Release signing is configured with the tunamail.signing.* Gradle properties; without them the
        // release APK is built unsigned.
        val storeFile = providers.gradleProperty("tunamail.signing.storeFile").orNull
        if (storeFile != null) {
            create("tunamailRelease") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("tunamail.signing.storePassword").get()
                keyAlias = providers.gradleProperty("tunamail.signing.keyAlias").get()
                keyPassword = providers.gradleProperty("tunamail.signing.keyPassword").get()
            }
        }
    }

    buildTypes {
        val minify = providers.gradleProperty("minify")
            .map(String::toBoolean)
            .orElse(true)
        release {
            signingConfig = signingConfigs.findByName("tunamailRelease")

            isMinifyEnabled = minify.get()
            isShrinkResources = minify.get()

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "$brandAppName Debug")

            enableUnitTestCoverage = testCoverageEnabled
            enableAndroidTestCoverage = testCoverageEnabled

            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("kotlin/**")
        }

        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.ui.compose.common)
    implementation(projects.core.ui.legacy.theme2.common)
    implementation(projects.feature.launcher)
    implementation(projects.feature.mail.message.list.api)
    implementation(projects.feature.mail.message.list.internal)
    implementation(projects.feature.mail.message.reader.api)

    implementation(projects.legacy.core)
    implementation(projects.legacy.ui.legacy)

    implementation(projects.core.featureflag)

    implementation(projects.feature.autodiscovery.api)
    implementation(projects.feature.account.settings.impl)

    // No funding prompts, no telemetry, no Thunderbird migration and no Thundermail in Tuna Mail.
    implementation(projects.feature.funding.noop)
    implementation(projects.feature.migration.launcher.noop)
    implementation(projects.feature.onboarding.migration.noop)
    implementation(projects.feature.thundermail.api)
    implementation(projects.feature.thundermail.k9mail)
    implementation(projects.feature.telemetry.noop)

    implementation(projects.feature.widget.messageList)
    implementation(projects.feature.widget.messageListGlance)
    implementation(projects.feature.widget.shortcut)
    implementation(projects.feature.widget.unread)

    implementation(libs.androidx.work.runtime)

    debugImplementation(projects.backend.demo)
    debugImplementation(projects.feature.autodiscovery.demo)

    // Required for DependencyInjectionTest
    testImplementation(projects.feature.account.api)
    testImplementation(projects.feature.account.common)
    testImplementation(projects.feature.thundermail.internal.common)
    testImplementation(projects.plugins.openpgpApiLib.openpgpApi)
    testImplementation(projects.feature.changelog.api)
    testImplementation(projects.feature.changelog.internal)

    testImplementation(libs.appauth)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
