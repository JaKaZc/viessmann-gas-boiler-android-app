import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// Each user registers their own API client on the Viessmann developer portal and enters the
// client ID in the app's setup wizard. The redirect URI is fixed: the package name as scheme.
fun redirectUri(scheme: String) = "$scheme://oauth2redirect"

// Play upload key (git-ignored keystore.properties + upload-keystore.jks). Without it, release
// builds fall back to the debug key so anyone can build the project.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.vigasboiler.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vigasboiler.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["appAuthRedirectScheme"] = "com.vigasboiler.app"
        buildConfigField("String", "REDIRECT_URI", "\"${redirectUri("com.vigasboiler.app")}\"")
    }

    flavorDimensions += "api"
    productFlavors {
        create("live") {
            dimension = "api"
        }
        // Serves docs/feature-dump instead of the API: UI work without spending API calls.
        create("mock") {
            dimension = "api"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
            // Must not claim the live app's OAuth redirect scheme.
            manifestPlaceholders["appAuthRedirectScheme"] = "com.vigasboiler.app.mock"
            buildConfigField("String", "REDIRECT_URI", "\"${redirectUri("com.vigasboiler.app.mock")}\"")
        }
    }

    sourceSets {
        // The masked sample installation: answers every call in the mock flavor and in demo mode.
        getByName("main").assets.srcDir("../docs/feature-dump")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Google Play: signed with the upload key; Play App Signing re-signs for distribution.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Firebase config: app/src/live/google-services.json (git-ignored). Without it the app builds and
// runs without analytics; the mock flavor never has one.
googleServices {
    missingGoogleServicesStrategy = com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy.IGNORE
}

// Inputs for the opt-in ExportLocalDumpTest (redacting a dump on the dev machine).
tasks.withType<Test>().configureEach {
    providers.gradleProperty("rawDumpDir").orNull?.let { systemProperty("rawDumpDir", it) }
    providers.gradleProperty("redactedOutDir").orNull?.let { systemProperty("redactedOutDir", it) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.vico.compose.m3)
    implementation(libs.glance.appwidget)
    implementation(libs.tink.android)
    implementation(libs.appauth)
    // Auth Tab (Chrome) for sign-in; AppAuth alone brings an old version without it.
    implementation(libs.androidx.browser)

    // Firebase Analytics only in the live flavor; the mock flavor has a no-op implementation.
    "liveImplementation"(platform(libs.firebase.bom))
    "liveImplementation"(libs.firebase.analytics)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
