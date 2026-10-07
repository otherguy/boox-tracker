import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val signingFile =
    file(System.getenv("READING_SYNC_SIGNING_PROPERTIES") ?: "${System.getProperty("user.home")}/.config/reading-sync/signing.properties")
val signingValues = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
android {
    namespace = "dev.otherguy.booxtracker"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.otherguy.booxtracker"
        minSdk = 26
        targetSdk = 36
        versionCode =
            providers
                .gradleProperty("versionCode")
                .orElse("12")
                .get()
                .toInt()
        versionName = "0.4.0"
    }
    signingConfigs {
        create("diagnostic") {
            if (signingValues.isNotEmpty()) {
                storeFile = file(signingValues.getProperty("storeFile"))
                storePassword = signingValues.getProperty("storePassword")
                keyAlias = signingValues.getProperty("keyAlias")
                keyPassword = signingValues.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Boox Tracker (dev)")
        }
        create("diagnostic") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("diagnostic")
            isDebuggable = false
            isMinifyEnabled = false
            resValue("string", "app_name", "Boox Tracker")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.configureEach {
    if (name == "validateSigningDiagnostic") {
        doFirst {
            check(signingValues.isNotEmpty()) { "Configure Boox Tracker diagnostic signing; see docs/build-and-release.md" }
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.2")
}
