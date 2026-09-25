import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Upload-key settings for Play releases; kept out of git in keystore.properties next to settings.gradle.kts. */
val uploadKey = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val reportEndpoint = providers.gradleProperty("sundial.reportEndpoint").getOrElse("").trim()
val reportFields = providers.gradleProperty("sundial.reportFields").getOrElse("").trim()

android {
    namespace = "com.metavirtuoso.sundial"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.metavirtuoso.sundial"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "3.0.0"
        buildConfigField("String", "REPORT_ENDPOINT", "\"$reportEndpoint\"")
        buildConfigField("String", "REPORT_FIELDS", "\"$reportFields\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (uploadKey.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// The Play bundle must be signed with the upload key, and must be able to send "report this
// reading" submissions (Google Play's AI-generated content policy).
tasks.configureEach {
    if (name == "bundleRelease") {
        doFirst {
            check(uploadKey.isNotEmpty()) { "Create keystore.properties with the upload key before building a Play bundle." }
            check(reportEndpoint.isNotEmpty()) { "Set sundial.reportEndpoint in gradle.properties before building a Play bundle." }
        }
    }
}
