buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP 9.1.1 has built-in Kotlin support (bundling KGP 2.2.10 by default).
        // We override the classpath KGP version to 2.4.21 so the Kotlin compiler and
        // Compose compiler plugin are aligned. Do NOT apply org.jetbrains.kotlin.android.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
