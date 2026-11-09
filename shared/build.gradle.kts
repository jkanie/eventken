plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.android.kmp.library) apply false
}

val androidEnabled = gradle.extra["eventken.androidEnabled"] as Boolean

kotlin {
    jvmToolchain(21)

    // The desktop app and the server both use this JVM target.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("desktopMain").dependencies {
            api(libs.ktor.client.okhttp)
            api(libs.sqldelight.sqlite.driver)
        }
    }
}

// Android is added only when the build found an Android SDK (see settings.gradle.kts).
if (androidEnabled) {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
    kotlin.extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("android") {
        namespace = "io.github.jkanie.eventken.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }
    kotlin.sourceSets.getByName("androidMain").dependencies {
        api(libs.ktor.client.okhttp)
        api(libs.sqldelight.android.driver)
    }
}

sqldelight {
    databases {
        create("EventkenDatabase") {
            packageName.set("io.github.jkanie.eventken.shared.cache")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
