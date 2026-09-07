plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.radar"
}

dependencies {
    implementation(project(":core:cw"))
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}
