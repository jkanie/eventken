plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.jkanie.eventken.server.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.jsoup)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
    // Tests read the sample feeds from the fixtures folder at the repository root.
    systemProperty("eventken.fixtures", rootProject.file("fixtures").absolutePath)
    // Set EVENTKEN_TEST_JDBC_URL to test against a running PostgreSQL instead of Testcontainers.
    environment("EVENTKEN_TEST_JDBC_URL", System.getenv("EVENTKEN_TEST_JDBC_URL") ?: "")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// Loads the sample feeds from the fixtures folder into the database named by EVENTKEN_DB_URL.
tasks.register<JavaExec>("devSeed") {
    group = "application"
    description = "Loads the sample feeds in fixtures/ into the database."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.jkanie.eventken.server.dev.DevSeedKt")
    workingDir = rootDir
    args("fixtures")
}

// Serves the fixtures folder on 127.0.0.1:8089, so a local server can fetch real HTTP feeds.
tasks.register<JavaExec>("fixtureHost") {
    group = "application"
    description = "Serves fixtures/ over HTTP on 127.0.0.1:8089."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.jkanie.eventken.server.dev.FixtureHostKt")
    workingDir = rootDir
    args("fixtures", "8089")
}
