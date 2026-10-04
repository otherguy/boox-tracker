import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val signingFile =
    file(System.getenv("READING_SYNC_SIGNING_PROPERTIES") ?: "${System.getProperty("user.home")}/.config/reading-sync/signing.properties")
val signingValues = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
android {
    namespace = "org.readingsync.diagnostic"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "org.readingsync.diagnostic"
        minSdk = 26
        targetSdk = 36
        versionCode =
            providers
                .gradleProperty("versionCode")
                .orElse("1")
                .get()
                .toInt()
        versionName = "0.1.0"
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
            resValue("string", "app_name", "Reading Sync (dev)")
        }
        create("diagnostic") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("diagnostic")
            isDebuggable = false
            isMinifyEnabled = false
            resValue("string", "app_name", "Reading Sync")
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
            check(signingValues.isNotEmpty()) { "Configure Reading Sync diagnostic signing; see docs/build-and-release.md" }
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
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.2")
}
