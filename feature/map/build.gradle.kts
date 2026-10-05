plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.map"
}

dependencies {
    implementation(libs.other.osmdroid)
    // Real org.json for unit tests (android.jar stub throws "not mocked")
    testImplementation(libs.test.json)
}
