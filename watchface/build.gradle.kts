import java.util.Properties

plugins {
    id("com.android.application")
}

/** The same upload key as the app; Play keeps the watch face in its own listing. */
val uploadKey = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.metavirtuoso.sundial.watchface"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metavirtuoso.sundial.watchface"
        // Watch Face Format 1 runs on Wear OS 4 (API 33) and later.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
        debug {
            // A watch face carries no code; minifying leaves the package without a dex.
            isMinifyEnabled = true
        }
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = true
            // The face refers to its images by name from XML, which resource shrinking cannot see.
            isShrinkResources = false
        }
    }

    lint {
        // The images are named in res/raw/watchface.xml, which lint does not read.
        disable += "UnusedResources"
    }
}

tasks.configureEach {
    if (name == "bundleRelease") {
        doFirst {
            check(uploadKey.isNotEmpty()) { "Create keystore.properties with the upload key before building a Play bundle." }
        }
    }
}
