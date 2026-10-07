plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.map"
    testOptions {
        unitTests {
            // Robolectric needs real Android resources for Compose UI tests.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.other.osmdroid)
    // Real org.json for unit tests (android.jar stub throws "not mocked")
    testImplementation(libs.test.json)
    testImplementation(libs.test.junit4)
    testImplementation(libs.test.coroutines)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.debug.manifest)
}
