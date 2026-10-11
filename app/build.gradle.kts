import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI provides a fixed keystore so every build can be installed over the previous one.
val devKeystore = System.getenv("BEKA_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Tag builds set BEKA_VERSION_NAME (the tag without its leading "v"); other builds are 0.1.<run>.
val versionLabel = System.getenv("BEKA_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.$buildNumber"

android {
    namespace = "dev.beka"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.beka"
        minSdk = 30
        targetSdk = 36
        versionCode = buildNumber
        versionName = versionLabel
        // GitHub repo for issues and update checks.
        buildConfigField("String", "REPO", "\"bnjyar/Beka\"")
        // Recording Beka's whole log to files starts on by default only in nightly/dev builds.
        buildConfigField("boolean", "RECORD_LOG", (System.getenv("BEKA_VERSION_NAME").isNullOrBlank()).toString())
    }

    signingConfigs {
        if (devKeystore != null) {
            create("dev") {
                storeFile = devKeystore
                storeType = "pkcs12"
                storePassword = System.getenv("BEKA_KEYSTORE_PASSWORD")
                keyAlias = "beka"
                keyPassword = System.getenv("BEKA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("dev")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:agent"))
    implementation(project(":core:rules"))
    implementation(project(":core:web"))
    implementation(project(":core:mcp"))

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Beka's own Wireless debugging client (pairing + TLS), so Shizuku is optional.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // TLS 1.3 key export for ADB pairing; the platform's Conscrypt is hidden API.
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Self-signed certificate for Beka's ADB key.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
