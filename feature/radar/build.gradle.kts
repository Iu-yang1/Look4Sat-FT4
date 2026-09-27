plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.radar"
}

dependencies {
    implementation(project(":feature:mutual"))
    implementation(project(":feature:cw"))
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    testImplementation(libs.test.junit4)
    testImplementation(libs.test.coroutines)
}
