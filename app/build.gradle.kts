plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionCode = 5
val appVersionName = "0.4.1"

base {
    archivesName.set("OpenClone-$appVersionName")
}

android {
    namespace = "dev.bill.openclone"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.bill.openclone"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // Print full assertion messages in CI logs; the default only shows the
    // exception type and file:line, which hides which command or input failed.
    testOptions {
        unitTests.all { test ->
            test.testLogging {
                exceptionFormat =
                    org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    // ARSCLib / apksig pull in large Java libraries. Keep the packaging simple.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // The three BouncyCastle jars each ship this file.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Binary AndroidManifest.xml + resources.arsc read/write.
    // REAndroid ARSCLib, Apache-2.0, pure Java, works on Android.
    // https://github.com/REAndroid/ARSCLib
    implementation("io.github.reandroid:ARSCLib:1.4.0")

    // APK v1/v2/v3 signing, from AOSP apksigner.
    implementation("com.android.tools.build:apksig:8.5.2")

    // Self-signed certificate generation for the clone signing key.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
}
