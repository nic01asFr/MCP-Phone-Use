import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signature de release : chaque personne qui compile genere sa propre cle locale,
// jamais commitee (voir keystore.properties.example). Si absente, seul le build
// debug reste disponible -- pas d'echec de compilation pour qui n'en a pas besoin.
val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
val hasReleaseSigning = keystorePropertiesFile.exists()
if (hasReleaseSigning) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "fr.nicolaslaval.deviceagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.nic01asfr.mcpphoneuse"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.currentTimeMillis() / 1000).toInt()  // toujours croissant, jamais de recollision entre builds
        versionName = "0.2-" + (System.currentTimeMillis() / 1000)
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Sans keystore.properties, ce type de build reste declarable mais
            // non signe -- documente dans README/CONTRIBUTING plutot que de
            // faire echouer la compilation pour qui n'en a pas besoin.
        }
        // Canal de test parallele : identifiant d'app different, cohabite avec le
        // debug "stable" sans jamais l'ecraser. Utilise pour toute iteration a risque
        // (comme la refonte UI) — la stable ne bouge que quand le canary est valide.
        create("canary") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".canary"
            versionNameSuffix = "-canary"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "MCP Phone Use (test)")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
