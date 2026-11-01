import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Client ID and redirect URI live in local.properties (git-ignored) and reach the app via BuildConfig.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val viessmannClientId: String = localProps.getProperty("viessmann.clientId", "").trim()
val viessmannRedirectUri: String =
    localProps.getProperty("viessmann.redirectUri", "si.vicare.customapp://oauth2redirect").trim()
val viessmannRedirectScheme: String = URI(viessmannRedirectUri).scheme
    ?: error("viessmann.redirectUri must contain a scheme, e.g. si.vicare.customapp://oauth2redirect")

android {
    namespace = "si.vicare.customapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "si.vicare.customapp"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "VIESSMANN_CLIENT_ID", "\"$viessmannClientId\"")
        buildConfigField("String", "VIESSMANN_REDIRECT_URI", "\"$viessmannRedirectUri\"")
        manifestPlaceholders["appAuthRedirectScheme"] = viessmannRedirectScheme
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
            manifestPlaceholders["appAuthRedirectScheme"] = "si.vicare.customapp.mock"
        }
    }

    sourceSets {
        getByName("mock").assets.srcDir("../docs/feature-dump")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideloaded app: sign release with the debug key so `assembleRelease` is installable.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.tink.android)
    implementation(libs.appauth)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
