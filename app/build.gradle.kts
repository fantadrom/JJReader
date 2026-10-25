plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fantadrom.jjreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fantadrom.jjreader"
        minSdk = 31
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.4-p0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    providers.environmentVariable("JJREADER_DEBUG_KEYSTORE").orNull?.let { debugKey ->
        signingConfigs.getByName("debug") {
            storeFile = file(debugKey)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions.unitTests.all {
        // Readium's real injector uses android.net.Uri. Robolectric supplies that API;
        // downloaded JVM Android artifacts stay inside the ignored workspace directory.
        it.systemProperty("maven.repo.local", rootProject.file("work/robolectric-m2").absolutePath)
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        it.systemProperty("user.home", rootProject.file("work/robolectric-home").apply { mkdirs() }.absolutePath)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":edge-protocol"))
    implementation("org.readium.kotlin-toolkit:readium-shared:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.4.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
