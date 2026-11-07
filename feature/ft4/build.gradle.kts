plugins {
    alias(libs.plugins.convention.featurePlugin)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rtbishop.look4sat.feature.ft4"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(libs.compose.navigation3)
    implementation(libs.kotlin.serialization)
    testImplementation(libs.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.debug.manifest)
}
