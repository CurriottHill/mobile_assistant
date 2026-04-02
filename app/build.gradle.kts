import java.util.Properties
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
}

private fun escapeBuildConfigValue(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

val openAiApiKey = escapeBuildConfigValue(localProperties.getProperty("OPENAI_API_KEY", ""))

val cartesiaApiKey = escapeBuildConfigValue(localProperties.getProperty("CARTESIA_API_KEY", ""))

val spotifyClientId = escapeBuildConfigValue(localProperties.getProperty("SPOTIFY_CLIENT_ID", ""))
val spotifyRedirectUriRaw = localProperties.getProperty("SPOTIFY_REDIRECT_URI", "mobile_assistant://spotify-auth-callback")
val spotifyRedirectUri = escapeBuildConfigValue(spotifyRedirectUriRaw)
val spotifyRedirect = runCatching { URI(spotifyRedirectUriRaw) }.getOrNull()
val spotifyRedirectScheme = spotifyRedirect?.scheme ?: "mobile_assistant"
val spotifyRedirectHost = spotifyRedirect?.host ?: "spotify-auth-callback"

android {
    namespace = "com.example.mobile_assistant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.mobile_assistant"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "CARTESIA_API_KEY", "\"$cartesiaApiKey\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"$spotifyRedirectUri\"")
        manifestPlaceholders["spotifyRedirectScheme"] = spotifyRedirectScheme
        manifestPlaceholders["spotifyRedirectHost"] = spotifyRedirectHost

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.squareup.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
