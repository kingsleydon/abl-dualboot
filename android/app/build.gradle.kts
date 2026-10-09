import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The ROCKNIX ABL release the app downloads on demand (shared with CI and abl-watch.yml).
val abl = Properties().apply { rootDir.resolve("../abl.properties").reader().use { load(it) } }

android {
    namespace = "io.github.kingsleydon.abldualboot"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kingsleydon.abldualboot"
        minSdk = 31
        targetSdk = 37
        versionCode = providers.environmentVariable("VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.environmentVariable("VERSION_NAME").orElse("dev").get()
        buildConfigField("String", "ABL_VERSION", "\"${abl.getProperty("ABL_VERSION")}\"")
        buildConfigField("String", "ABL_TARBALL_SHA256", "\"${abl.getProperty("ABL_TARBALL_SHA256")}\"")
    }

    val keystore = providers.environmentVariable("KEYSTORE_FILE").orNull?.takeIf { it.isNotEmpty() }
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
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
    sourceSets["main"].assets.srcDirs("../../shared")
}

dependencies {
    testImplementation(libs.junit)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libsu.core)
    implementation(libs.androidx.work.runtime.ktx)
}
