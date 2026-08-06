plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun officialStatsEndpointFromFile(): String {
    val envFile = rootProject.file("scripts/official-build.env")
    if (!envFile.isFile) return ""

    return envFile.readLines()
        .firstNotNullOfOrNull { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2 && parts[0].trim() == "LYRICS_PLUS_STATS_ENDPOINT") {
                parts[1].trim().trim('"', '\'')
            } else {
                null
            }
        }
        .orEmpty()
}

val explicitStatsEndpoint = providers.gradleProperty("lyricsPlusStatsEndpoint")
val environmentStatsEndpoint = providers.environmentVariable("LYRICS_PLUS_STATS_ENDPOINT")
val debugStatsEndpoint = explicitStatsEndpoint
    .orElse(environmentStatsEndpoint)
    .orElse("")
    .get()
val releaseStatsEndpoint = explicitStatsEndpoint
    .orElse(environmentStatsEndpoint)
    .orElse(providers.provider { officialStatsEndpointFromFile() })
    .orElse("")
    .get()

android {
    namespace = "com.lyricsplus.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lyricsplus.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.1"
        
        resConfigs("en", "zh", "zh-rCN", "zh-rTW", "zh-rHK")
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
        resources {
            excludes += setOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }

    signingConfigs {
        create("shared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            buildConfigField("String", "STATS_ENDPOINT", releaseStatsEndpoint.asBuildConfigString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            buildConfigField("String", "STATS_ENDPOINT", debugStatsEndpoint.asBuildConfigString())
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}


kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.palette:palette:1.0.0")
    implementation("com.xzakota.hyper.notification:focus-api:1.4")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
