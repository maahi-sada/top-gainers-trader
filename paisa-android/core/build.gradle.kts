import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "app.paisa"
version = "1.0"

repositories { mavenCentral() }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

/* Java 17 bytecode: what the Android build expects. Compiled by whatever JDK is
 * installed (17 or newer), so no separate toolchain download is needed. */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.test { useJUnitPlatform() }
