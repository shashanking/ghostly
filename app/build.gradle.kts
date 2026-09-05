import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.github.triplet.play")
}

// Release signing details live in keystore.properties (kept out of version control).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.shashank.ghostly"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shashank.ghostly"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProperties.containsKey("storeFile")) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Google Sign-In (Credential Manager, Google's current recommended API) is the one deliberate
    // exception to this app's zero-dependency policy — there's no platform SDK path to it.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Credential Manager's getCredential() is a suspend function; MainActivity/OnboardingActivity
    // are plain Activities (not ComponentActivity), so lifecycleScope isn't available — a small
    // manually-managed CoroutineScope needs this instead.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// Gradle Play Publisher — `./gradlew publishBundle` uploads the signed release AAB straight to
// the internal testing track via the Play Developer API. Needs a service account JSON key at
// play-service-account.json (kept out of version control, like keystore.properties); until that
// file exists, publish tasks simply aren't usable but every other build task is unaffected.
play {
    val serviceAccountFile = rootProject.file("play-service-account.json")
    if (serviceAccountFile.exists()) {
        serviceAccountCredentials.set(serviceAccountFile)
    }
    track.set("internal")
    defaultToAppBundles.set(true)
}
