plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.rtbishop.look4sat.core.ft4"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndkVersion.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "com.rtbishop.look4sat.core.ft4.Ft4NativeInstrument"
        ndk.abiFilters += if(providers.gradleProperty("ft4Emulator").orNull=="true") listOf("x86_64") else listOf("arm64-v8a", "armeabi-v7a")
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdkVersion.get().toInt())
}

dependencies {
    implementation(libs.kotlin.coroutines)
    testImplementation(libs.bundles.unitTest)
}
