plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "app.quietmap.shared"
        compileSdk = 37
        minSdk = 26
        // JVM unit tests (commonTest + androidHostTest) run on the build machine.
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.secp256k1.kmp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.jni.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.bouncycastle)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.libsodium.bindings)
        }
        named("androidHostTest") {
            dependencies {
                implementation(libs.junit)
                implementation(libs.secp256k1.jni.jvm)
            }
        }
    }
}

