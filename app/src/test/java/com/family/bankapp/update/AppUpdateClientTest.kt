package com.family.bankapp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateClientTest {

    @Test
    fun newestManifest_keepsHighestVersionCode() {
        val cached = manifest(50, "1.8.2")
        val published = manifest(52, "1.8.4")

        assertEquals(published, AppUpdateClient.newestManifest(listOf(cached, published)))
        assertEquals(published, AppUpdateClient.newestManifest(listOf(published, cached)))
    }

    @Test
    fun newestManifest_emptyIsNull() {
        assertNull(AppUpdateClient.newestManifest(emptyList()))
    }

    private fun manifest(versionCode: Int, versionName: String) = AppUpdateManifest(
        versionCode = versionCode,
        versionName = versionName,
        apkUrl = "https://example.com/FamilyBank.apk"
    )
}
