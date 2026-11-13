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
    val sunlightBenchmark = providers.environmentVariable("SUNLIGHT_BENCHMARK")
    val astroArchive = providers.environmentVariable("ASTRO_TEST_DATA_ZIP").orNull
        ?.let { file(it) } ?: listOf(
            rootProject.file("dist/orekit-full/orekit-data.zip"),
            rootProject.file("dist/orekit-data/orekit-data.zip")
        ).firstOrNull { it.isFile }
    inputs.property("l4sSimulatorPort", simulatorPort.orElse(""))
    inputs.property("sunlightBenchmark", sunlightBenchmark.orElse(""))
    if (astroArchive != null && astroArchive.isFile) {
        inputs.file(astroArchive).withPathSensitivity(PathSensitivity.NONE)
    }
    // A running external C-core simulator must be exercised, not reused from a skipped/cached run.
    outputs.upToDateWhen { simulatorPort.orNull.isNullOrBlank() && sunlightBenchmark.orNull != "1" }
}
