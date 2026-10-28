plugins {
    alias(libs.plugins.convention.featurePlugin)
}
android {
    namespace = "com.rtbishop.look4sat.feature.jtty"
    testOptions.unitTests.isIncludeAndroidResources = true
}
dependencies {
    testImplementation(libs.test.junit4)
    testImplementation(libs.test.coroutines)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.debug.manifest)
}
