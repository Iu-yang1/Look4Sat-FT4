plugins {
    alias(libs.plugins.convention.applicationPlugin)
}

val releaseStoreFile = providers.environmentVariable("LOOK4SAT_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("LOOK4SAT_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("LOOK4SAT_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LOOK4SAT_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() } && file(releaseStoreFile.orEmpty()).isFile

android {
    namespace = libs.versions.packageName.get()
    defaultConfig {
        applicationId = "cn.ba7opf.look4sat"
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
