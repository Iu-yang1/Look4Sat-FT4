plugins {
    alias(libs.plugins.convention.coreDataPlugin)
}

android {
    namespace = "com.rtbishop.look4sat.core.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    testImplementation(testFixtures(project(":core:domain")))
    implementation(libs.orekit)
    implementation(project(":core:ft4-native"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val simulatorPort = providers.environmentVariable("L4S_SIMULATOR_PORT")
    inputs.property("l4sSimulatorPort", simulatorPort.orElse(""))
    // A running external C-core simulator must be exercised, not reused from a skipped/cached run.
    outputs.upToDateWhen { simulatorPort.orNull.isNullOrBlank() }
}
