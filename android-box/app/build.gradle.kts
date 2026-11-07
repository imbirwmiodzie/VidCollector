plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vidcollector.uploadbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vidcollector.uploadbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // PoC-only key committed to the repo so every build (local or CI) has the same
        // signature and a newer APK installs over an older one. NOT a secret: replace it
        // with a private keystore before distributing beyond testing.
        create("poc") {
            storeFile = rootProject.file("keystore/upload-box-poc.jks")
            storePassword = "uploadbox-poc"
            keyAlias = "uploadbox"
            keyPassword = "uploadbox-poc"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("poc")
        }
        debug {
            signingConfig = signingConfigs.getByName("poc")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "1g"
                it.testLogging {
                    events("passed", "failed", "skipped")
                    showStandardStreams = true
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
