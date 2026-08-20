plugins {
    alias(libs.plugins.convention.featurePlugin)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rtbishop.look4sat.feature.ft4"
}

dependencies {
    implementation(libs.compose.navigation3)
    implementation(libs.kotlin.serialization)
}
