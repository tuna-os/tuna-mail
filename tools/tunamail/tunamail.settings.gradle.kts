// Tuna Mail's additions to the build settings, applied from settings.gradle.kts (the fork's one line there; see
// docs/tunamail/MAINTAINING.md). It includes the fork-only modules and provides the prebuilt Tuta and Proton
// SDKs as a local Maven repository in third_party/maven.
//
// The SDKs are GitHub release assets of tuna-os/tuta-unofficial-sdk and tuna-os/proton-unofficial-sdk. They are
// downloaded on the first build, checked against the SHA-256 pinned below and unzipped; a later build reuses them
// as long as the marker file written next to them names the same digest. To move to a new SDK release, change
// its version, URL and digest here (`sha256sum` of the `-maven.zip` asset) and in the module that depends on it.

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

include(
    ":app-tunamail",
    ":backend:tunasession",
    ":backend:tuta",
    ":feature:tunamail:provider",
    ":feature:tunamail:tuta",
)

class TunaSdk(val name: String, val url: String, val sha256: String)

val tunaSdks = listOf(
    TunaSdk(
        name = "tuta-sdk-android-0.2.1",
        url = "https://github.com/tuna-os/tuta-unofficial-sdk/releases/download/v0.2.1/" +
            "tuta-sdk-android-0.2.1-maven.zip",
        sha256 = "b4d12f3fd70fed7f792f2553d85d751828652ca1285f1c34f63c4adc5ab451dc",
    ),
    TunaSdk(
        name = "proton-sdk-android-0.1.0",
        url = "https://github.com/tuna-os/proton-unofficial-sdk/releases/download/v0.1.0/" +
            "proton-sdk-android-0.1.0-maven.zip",
        sha256 = "b0228a3e74cd3c02a3412d5fc4a4996edd71acbd0eb59e8ae1aedda880cc613e",
    ),
)

val tunaMavenDirectory: File = settingsDir.resolve("third_party/maven")

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun unzipInto(archive: File, directory: File) {
    val root = directory.canonicalFile
    ZipInputStream(archive.inputStream().buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val target = root.resolve(entry.name).canonicalFile
            check(target.toPath().startsWith(root.toPath())) { "${archive.name}: entry outside the repository" }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
    }
}

fun fetchTunaSdk(sdk: TunaSdk) {
    val markerPath = "third_party/maven/.${sdk.name}.sha256"
    val marker = settingsDir.resolve(markerPath)
    val fetched = providers.fileContents(layout.settingsDirectory.file(markerPath)).asText.orNull?.trim()
    if (fetched == sdk.sha256) return

    tunaMavenDirectory.mkdirs()
    val archive = File.createTempFile(sdk.name, ".zip")
    try {
        logger.lifecycle("Tuna Mail: fetching ${sdk.name} from ${sdk.url}")
        try {
            URI.create(sdk.url).toURL().openStream().use { input ->
                archive.outputStream().use { input.copyTo(it) }
            }
        } catch (e: java.io.IOException) {
            throw GradleException(
                "Tuna Mail: could not download ${sdk.name} from ${sdk.url}. Download it by hand, check its " +
                    "SHA-256 (${sdk.sha256}), unzip it into $tunaMavenDirectory and write the digest to $marker.",
                e,
            )
        }
        val actual = sha256Of(archive)
        if (actual != sdk.sha256) {
            throw GradleException("Tuna Mail: ${sdk.name} has SHA-256 $actual, expected ${sdk.sha256}; not using it.")
        }
        unzipInto(archive, tunaMavenDirectory)
        marker.writeText(sdk.sha256 + "\n")
    } finally {
        archive.delete()
    }
}

tunaSdks.forEach(::fetchTunaSdk)

dependencyResolutionManagement {
    repositories {
        maven(url = tunaMavenDirectory.toURI()) {
            name = "tunaSdks"
            mavenContent {
                includeGroup("org.tunaos.tuta")
                includeGroup("org.tunaos.proton")
            }
        }
    }
}
