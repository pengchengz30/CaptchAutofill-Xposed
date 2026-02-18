import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
}

fun getGitTagName(): String {
    return providers.exec {
        commandLine("git", "describe", "--tags", "--always")
    }.standardOutput.asText.map { it.trim().removePrefix("v") }.getOrElse("0.1.0")
}

fun getGitCommitCount(): Int {
    return try {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.map { it.trim().toInt() }.getOrElse(1)
    } catch (e: Exception) {
        1
    }
}

android {
    namespace = "uk.co.pzhang.autofill"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "uk.co.pzhang.autofill"
        minSdk = 30
        targetSdk = 35
        versionCode = getGitCommitCount()
        versionName = getGitTagName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        println("--> Build Version: $versionName ($versionCode)")
    }

    signingConfigs {
        create("release") {
            val explicitFilePath = System.getenv("KEYSTORE")
            val keyStoreBase64 = System.getenv("KEYSTORE_BASE64")

            val sPassword = System.getenv("KEYSTORE_PASSWORD")
            val kAlias = System.getenv("KEY_ALIAS")
            val kPassword = System.getenv("KEY_PASSWORD")

            val keystoreFile = when {
                !explicitFilePath.isNullOrEmpty() -> file(explicitFilePath)
                !keyStoreBase64.isNullOrEmpty() -> file("/dev/shm/release.jks")
                else -> null
            }

            if (keystoreFile != null && explicitFilePath.isNullOrEmpty() && !keystoreFile.exists() && !keyStoreBase64.isNullOrEmpty()) {
                println("!!! Security: Restoring keystore to RAM (/dev/shm)...")
                if (!keystoreFile.parentFile.exists()) keystoreFile.parentFile.mkdirs()
                keystoreFile.writeBytes(Base64.getDecoder().decode(keyStoreBase64.trim()))
                keystoreFile.deleteOnExit()
            }

            val isCredentialsPresent = !sPassword.isNullOrEmpty() && !kAlias.isNullOrEmpty() && !kPassword.isNullOrEmpty()
            if (keystoreFile != null && keystoreFile.exists() && isCredentialsPresent) {
                storeFile = keystoreFile
                storePassword = sPassword
                keyAlias = kAlias
                keyPassword = kPassword
                println("--> Signing: All credentials detected. Release signing configured.")
            } else {
                println("--- Signing: Missing keystore or environment variables. Skipping release signing... ---")
            }
        }
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.getByName("release")

            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            } else {
                println("WARNING: Release signing skipped, APK will be UNSIGNED!")
                signingConfig = null
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            versionNameSuffix = "-debug"

            isMinifyEnabled = false
            isShrinkResources = false

            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    compileOnly("de.robv.android.xposed:api:82")
}
