plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.rtbishop.look4sat.core.ft4"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndkVersion.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk.abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOfNotNull(
                    System.getenv("FT4_FLANG_PATH")?.let { "-DFT4_FLANG_PATH=$it" },
                    System.getenv("FT4_BOOST_HEADERS")?.let { "-DFT4_BOOST_HEADERS=$it" },
                    System.getenv("FT4_LLVM_SOURCE_ROOT")?.let { "-DFT4_LLVM_SOURCE_ROOT=$it" }
                )
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
