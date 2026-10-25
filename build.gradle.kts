// Readium 3.4.0 publishes Kotlin 2.4 metadata; upgrade AGP's built-in Kotlin explicitly.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
