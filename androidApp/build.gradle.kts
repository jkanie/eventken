plugins {
    // AGP 9 compiles Kotlin itself, so the kotlin-android plugin is not applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jkanie.eventken.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jkanie.eventken"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // 10.0.2.2 is the host computer as the Android emulator sees it.
        buildConfigField("String", "API_URL", "\"${providers.gradleProperty("eventken.apiUrl").getOrElse("http://10.0.2.2:8080")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric reads the merged manifest and resources, so the tests run on the JVM without an emulator.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.logging.enabled", "false")
            // Robolectric's Android 16 runtime reaches into FileDescriptor through jdk.internal.access,
            // which JDK 21 keeps closed unless the test JVM exports it.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    // Compose tests inject touches through Espresso. The 3.5.0 that Compose brings in calls InputManager.getInstance(),
    // which the Android 17 (API 37) runtime lacks, so every tap failed. 3.7.0 works.
    testImplementation(libs.androidx.test.espresso)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
