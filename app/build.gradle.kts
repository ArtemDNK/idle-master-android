plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.idlemaster.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.idlemaster.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Personal sideload key, kept in the repo so every build (local or CI)
        // is signed the same way and updates install over each other.
        create("personal") {
            storeFile = file("app-signing.keystore")
            storePassword = "idlemaster"
            keyAlias = "idlemaster"
            keyPassword = "idlemaster"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            // JavaSteam loads BouncyCastle and protobuf classes via reflection,
            // so shrinking is left off on purpose.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/io.netty.versions.properties",
                "META-INF/native-image/**",
                "**/*.proto",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Steam network protocol (Java port of SteamKit2) + crypto provider it needs
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")

    // Steam Community pages (badges / gamecards)
    implementation("org.jsoup:jsoup:1.21.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("io.coil-kt:coil-compose:2.7.0")
}
