import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.convention.applicationPlugin)
}

// Release 签名信息只从被忽略的 local.properties 或环境变量读取，禁止写入版本库。
val releaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) FileInputStream(propsFile).use { load(it) }
}

fun releaseCred(name: String): String? =
    (releaseProps.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseCred("LOOK4SAT_RELEASE_STORE_FILE")
val releaseStorePassword = releaseCred("LOOK4SAT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseCred("LOOK4SAT_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseCred("LOOK4SAT_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null } && file(releaseStoreFile.orEmpty()).isFile

val l4sDevelopment = providers.gradleProperty("l4sDevelopment").orNull == "true"

android {
    namespace = libs.versions.packageName.get()
    defaultConfig {
        applicationId = if (l4sDevelopment) "cn.ba7opf.look4sat.l4s" else "cn.ba7opf.look4sat"
        if (l4sDevelopment) versionCode = libs.versions.appVersionCode.get().toInt() + 5
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        release {
            if (providers.gradleProperty("catBluetoothTest").orNull == "true") versionNameSuffix = "-ic705-test1"
            if (l4sDevelopment) versionNameSuffix = "-l4s-alpha7"
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    if (providers.gradleProperty("orbitBenchmark").orNull == "true") {
        sourceSets.getByName("release") {
            kotlin.directories += "src/benchmark/java"
            manifest.srcFile("src/benchmark/AndroidManifest.xml")
        }
    }
}
