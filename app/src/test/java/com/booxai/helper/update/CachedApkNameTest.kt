package com.booxai.helper.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedApkNameTest {

    private fun info(versionName: String, versionCode: Int?) = UpdateInfo(
        versionName = versionName,
        versionCode = versionCode,
        notes = "",
        apkUrl = "https://github.com/example/repo/releases/latest/download/app.apk",
        sha256 = null,
        sizeBytes = null,
    )

    @Test
    fun `file name includes version code`() {
        assertEquals("bandu-1.1.0-c5.apk", apkFileName(info("1.1.0", 5)))
        assertEquals("bandu-1.1.0.apk", apkFileName(info("1.1.0", null)))
    }

    @Test
    fun `parses version name and code`() {
        assertEquals(CachedApkName("1.1.0", 5), parseCachedApkName("bandu-1.1.0-c5.apk"))
        assertEquals(CachedApkName("1.1.0", null), parseCachedApkName("bandu-1.1.0.apk"))
    }

    @Test
    fun `rejects part and foreign names`() {
        assertNull(parseCachedApkName("bandu-1.1.0-c5.apk.part"))
        assertNull(parseCachedApkName("other.apk"))
        assertNull(parseCachedApkName("bandu-.apk"))
    }

    @Test
    fun `sanitizes unsafe version names`() {
        assertEquals("bandu-1.1.0_1-c5.apk", apkFileName(info("1.1.0+1", 5)))
    }
}
