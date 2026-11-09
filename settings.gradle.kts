import java.io.File
import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "eventken"

// The Android parts build only where an Android SDK with an accepted licence
// is present. The server, the shared code, the desktop app then still build on
// any machine with a JDK. Pass -Peventken.android=false to skip Android on purpose.
fun androidSdkDir(): File? {
    val localProperties = File(settingsDir, "local.properties")
    val fromFile =
        if (localProperties.isFile) {
            Properties().apply { localProperties.inputStream().use { load(it) } }.getProperty("sdk.dir")
        } else {
            null
        }
    val path = fromFile ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    return path?.let(::File)?.takeIf { it.isDirectory }
}

val androidWanted = providers.gradleProperty("eventken.android").orNull != "false"
val androidSdk = androidSdkDir()
val androidEnabled = androidWanted && androidSdk != null && File(androidSdk, "licenses/android-sdk-license").isFile
gradle.extra["eventken.androidEnabled"] = androidEnabled

include(":shared", ":server", ":desktopApp")
if (androidEnabled) {
    include(":androidApp")
} else {
    logger.lifecycle("eventken: no Android SDK with an accepted licence found, so :androidApp is skipped.")
}
