/** Compose Multiplatform screens shared by the Android and iOS apps. */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "app.quietmap.ui"
        compileSdk = 37
        minSdk = 26
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // The iOS app links this one framework; it also exposes the :shared core to Swift.
        target.binaries.framework {
            baseName = "QuietmapUI"
            isStatic = true
            export(project(":shared"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.material3)
            implementation(libs.cmp.lifecycle.runtime.compose)
            implementation(libs.maplibre.compose)
            implementation(libs.qrose)
        }
        androidMain.dependencies {
            // maplibre-compose ships no renderer on Android; without one the map crashes with
            // "libmaplibre-native-c.so not found". iOS gets Metal through maplibre-compose itself.
            implementation(libs.maplibre.native.runtime.opengl)
            implementation(libs.androidx.activity.compose)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
        iosMain.dependencies {
            // Encrypts the saved state file (same library :shared uses for ChaCha20 on iOS).
            implementation(libs.libsodium.bindings)
        }
    }
}

