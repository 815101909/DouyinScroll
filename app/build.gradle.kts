import java.util.Properties
import org.gradle.api.Project

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun Project.resolveVoiceSdkProperty(key: String, defaultValue: String = ""): String {
    val value = providers.gradleProperty(key).orNull
        ?: localProperties.getProperty(key)
        ?: defaultValue
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.example.douflow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.douflow"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        buildConfigField(
            "String",
            "VOICE_SDK_API_KEY",
            "\"${project.resolveVoiceSdkProperty("voice.sdk.apiKey")}\""
        )
        buildConfigField(
            "String",
            "VOICE_SDK_URL",
            "\"${project.resolveVoiceSdkProperty("voice.sdk.url", "wss://dashscope.aliyuncs.com/api-ws/v1/inference")}\""
        )
        buildConfigField(
            "String",
            "VOICE_SDK_MODEL",
            "\"${project.resolveVoiceSdkProperty("voice.sdk.model", "fun-asr-realtime")}\""
        )
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    compileOnly(files("libs/nuisdk-classes.jar"))
    implementation(mapOf("name" to "nuisdk-release", "ext" to "aar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
