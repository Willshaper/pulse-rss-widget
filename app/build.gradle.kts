plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pulse.rsswidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pulse.rsswidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Sign with the local keystore if present (kept out of git). A fresh clone without it
    // still builds — it just produces an unsigned release you can sign with your own key.
    val releaseKeystore = file("pulse.keystore")
    val canSign = releaseKeystore.exists()

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = (project.findProperty("PULSE_STORE_PASSWORD") as String?) ?: "pulse123"
                keyAlias = (project.findProperty("PULSE_KEY_ALIAS") as String?) ?: "pulse"
                keyPassword = (project.findProperty("PULSE_KEY_PASSWORD") as String?) ?: "pulse123"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    // Give the built APK a recognizable name instead of the default "app-release.apk".
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "Pulse-RSS-Widget-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
