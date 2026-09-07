/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateRepositoryTest {
    @Test
    fun selectsCompatibleSignedReleaseAssetInsteadOfFirstApk() {
        val base = "https://github.com/Iu-yang1/Look4Sat-FT4/releases/download/v4.4.9/"
        val assets = listOf(
            asset("other-app.apk", base),
            asset("Look4Sat-FT4-v4.4.9-unsigned.apk", base),
            asset("Look4Sat-FT4-v4.4.9-x86_64-release.apk", base),
            asset("Look4Sat-FT4-v4.4.9-arm64-v8a-release.apk", base)
        )

        val selected = selectReleaseAsset(assets, "v4.4.9", listOf("arm64-v8a", "armeabi-v7a"))

        assertEquals("Look4Sat-FT4-v4.4.9-arm64-v8a-release.apk", selected?.name)
    }

    @Test
    fun rejectsAssetsFromOtherRepositories() {
        val asset = ReleaseAsset(
            name = "Look4Sat-FT4-v4.4.9-universal-release.apk",
            url = "https://github.com/attacker/repo/releases/download/v4.4.9/app.apk",
            contentType = "application/vnd.android.package-archive",
            state = "uploaded",
            size = 20_000_000L
        )

        assertEquals(null, selectReleaseAsset(listOf(asset), "v4.4.9", listOf("arm64-v8a")))
    }

    private fun asset(name: String, base: String) = ReleaseAsset(
        name = name,
        url = base + name,
        contentType = "application/vnd.android.package-archive",
        state = "uploaded",
        size = 20_000_000L
    )
}
