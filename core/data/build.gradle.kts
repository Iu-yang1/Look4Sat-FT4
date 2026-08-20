plugins {
    alias(libs.plugins.convention.coreDataPlugin)
}

android {
    namespace = "com.rtbishop.look4sat.core.data"
}

dependencies {
    implementation(project(":core:ft4-native"))
}
