plugins {
    alias(libs.plugins.convention.coreDataPlugin)
}

android {
    namespace = "com.rtbishop.look4sat.core.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(project(":core:ft4-native"))
}
