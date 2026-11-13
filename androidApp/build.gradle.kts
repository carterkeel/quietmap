import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.quietmap"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.quietmap"
        minSdk = 26
        targetSdk = 35
        // CI builds count up, so each new APK installs over the previous one.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI signs test builds with one long-lived key (kept in the Actions cache, never in the repo),
    // so a new APK can be installed over an older one. Local builds use the normal debug key.
    val ciKeystore = providers.environmentVariable("QUIETMAP_KEYSTORE").orNull?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = ciKeystore
                storePassword = providers.environmentVariable("QUIETMAP_KEYSTORE_PASSWORD").get()
                keyAlias = "quietmap-ci"
                keyPassword = providers.environmentVariable("QUIETMAP_KEYSTORE_PASSWORD").get()
            }
        }
    }
    val testSigning = if (ciKeystore != null) signingConfigs.getByName("ci") else signingConfigs.getByName("debug")

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = testSigning
        }
        release {
            isMinifyEnabled = false
        }
        // Optimised like a real release (R8, no debugging) but signed with the debug key, so it
        // installs anywhere. Compose is much smoother in this build than in debug.
        create("staging") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = testSigning
            applicationIdSuffix = ".staging"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.zxing.android.embedded)
    implementation(libs.cmp.runtime)
    implementation(libs.cmp.ui)

    // UI smoke test that runs on an emulator in CI (see androidTest/).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
