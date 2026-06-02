import groovy.json.JsonSlurper
import java.util.Properties
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
}

val appApplicationId = "com.assistant"

private fun escapeBuildConfigValue(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

private fun mapValue(value: Any?, key: String): Any? {
    return (value as? Map<*, *>)?.get(key)
}

private fun firstString(value: Any?, key: String): String? {
    return (value as? List<*>)?.mapNotNull { mapValue(it, key) as? String }?.firstOrNull()
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

val openAiApiKey = escapeBuildConfigValue(localProperties.getProperty("OPENAI_API_KEY", ""))

val openRouterApiKey = escapeBuildConfigValue(localProperties.getProperty("OPENROUTER_API_KEY", ""))

val anthropicApiKey = escapeBuildConfigValue(localProperties.getProperty("ANTHROPIC_API_KEY", ""))

val deepgramApiKey = escapeBuildConfigValue(localProperties.getProperty("DEEPGRAM_API_KEY", ""))

val assemblyAiApiKey = escapeBuildConfigValue(localProperties.getProperty("ASSEMBLY_AI_API_KEY", ""))

val mapsApiKey = escapeBuildConfigValue(localProperties.getProperty("MAPS_API_KEY", ""))
val backendApiBaseUrl = escapeBuildConfigValue(localProperties.getProperty("MARVIN_API_BASE_URL", ""))
val billingUrl = escapeBuildConfigValue(localProperties.getProperty("MARVIN_BILLING_URL", ""))

val googleServicesFile = project.file("google-services.json")
val googleServicesJson = googleServicesFile
    .takeIf { it.exists() }
    ?.let { JsonSlurper().parse(it) as? Map<*, *> }
val googleServicesClient = (googleServicesJson?.get("client") as? List<*>)
    ?.mapNotNull { it as? Map<*, *> }
    ?.firstOrNull {
        val clientInfo = it["client_info"] as? Map<*, *>
        val androidInfo = clientInfo?.get("android_client_info") as? Map<*, *>
        androidInfo?.get("package_name") == appApplicationId
    }
val googleServicesProjectInfo = googleServicesJson?.get("project_info") as? Map<*, *>
val googleServicesClientInfo = googleServicesClient?.get("client_info") as? Map<*, *>
val googleServicesApiKey = firstString(googleServicesClient?.get("api_key"), "current_key")
val googleServicesWebClientId = (googleServicesClient?.get("oauth_client") as? List<*>)
    ?.mapNotNull { it as? Map<*, *> }
    ?.firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }
    ?.get("client_id") as? String

val firebaseApiKey = escapeBuildConfigValue(
    googleServicesApiKey ?: localProperties.getProperty("FIREBASE_API_KEY", "")
)
val firebaseAppId = escapeBuildConfigValue(
    (googleServicesClientInfo?.get("mobilesdk_app_id") as? String)
        ?: localProperties.getProperty("FIREBASE_APP_ID", "")
)
val firebaseProjectId = escapeBuildConfigValue(
    (googleServicesProjectInfo?.get("project_id") as? String)
        ?: localProperties.getProperty("FIREBASE_PROJECT_ID", "")
)
val firebaseWebClientId = escapeBuildConfigValue(
    googleServicesWebClientId ?: localProperties.getProperty("FIREBASE_WEB_CLIENT_ID", "")
)

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
        applicationId = appApplicationId
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openRouterApiKey\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        buildConfigField("String", "DEEPGRAM_API_KEY", "\"$deepgramApiKey\"")
        buildConfigField("String", "ASSEMBLY_AI_API_KEY", "\"$assemblyAiApiKey\"")
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        buildConfigField("String", "MARVIN_API_BASE_URL", "\"$backendApiBaseUrl\"")
        buildConfigField("String", "MARVIN_BILLING_URL", "\"$billingUrl\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"$firebaseWebClientId\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"$spotifyRedirectUri\"")
        manifestPlaceholders["spotifyRedirectScheme"] = spotifyRedirectScheme
        manifestPlaceholders["spotifyRedirectHost"] = spotifyRedirectHost

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.play.services.auth)
    implementation(libs.firebase.auth)
    implementation(libs.androidsvg)
    testImplementation(libs.junit)
    // Real org.json for unit tests (the Android stub throws "not mocked").
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
