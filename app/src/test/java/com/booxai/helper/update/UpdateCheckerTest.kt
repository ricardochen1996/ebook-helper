package com.booxai.helper.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    private val sha256 = "0123456789abcdef".repeat(4)

    @Test
    fun `parses update json`() {
        val info = parseUpdateJson(
            """
            {
              "versionName": "1.1.0",
              "versionCode": 5,
              "notes": "修复若干问题",
              "apk": "reader-helper-release.apk",
              "sha256": "${sha256.uppercase()}",
              "size": 12345678
            }
            """.trimIndent()
        )
        assertNotNull(info)
        assertEquals("1.1.0", info?.versionName)
        assertEquals(5, info?.versionCode)
        assertEquals("修复若干问题", info?.notes)
        assertEquals(
            "https://github.com/ricardochen1996/ebook-helper/releases/latest/download/" +
                "reader-helper-release.apk",
            info?.apkUrl
        )
        assertEquals(sha256, info?.sha256)
        assertEquals(12345678L, info?.sizeBytes)
    }

    @Test
    fun `update json ignores unknown fields`() {
        val info = parseUpdateJson(
            """
            {
              "versionName": "1.2.0",
              "apk": "reader-helper-release.apk",
              "somethingNew": true
            }
            """.trimIndent()
        )
        assertNotNull(info)
        assertNull(info?.versionCode)
        assertNull(info?.sha256)
        assertNull(info?.sizeBytes)
    }

    @Test
    fun `update json requires version name and apk`() {
        assertNull(parseUpdateJson("""{"versionCode": 5, "apk": "a.apk"}"""))
        assertNull(parseUpdateJson("""{"versionName": "1.1.0"}"""))
        assertNull(parseUpdateJson("{}"))
    }

    @Test
    fun `update json rejects apk url`() {
        assertNull(
            parseUpdateJson(
                """{"versionName": "1.1.0", "apk": "https://evil.example/app.apk"}"""
            )
        )
        assertNull(
            parseUpdateJson(
                """{"versionName": "1.1.0", "apk": "../other/app.apk"}"""
            )
        )
    }

    @Test
    fun `update json requires apk suffix`() {
        assertNull(
            parseUpdateJson(
                """{"versionName": "1.1.0", "apk": "reader-helper-release.bin"}"""
            )
        )
    }

    @Test
    fun `update json rejects overly long version name`() {
        assertNull(
            parseUpdateJson(
                """{"versionName": "${"1".repeat(65)}", "apk": "reader-helper-release.apk"}"""
            )
        )
    }

    @Test
    fun `update json accepts 64 character version name`() {
        val info = parseUpdateJson(
            """{"versionName": "${"1".repeat(64)}", "apk": "reader-helper-release.apk"}"""
        )
        assertNotNull(info)
    }

    @Test
    fun `update json rejects malformed sha256`() {
        assertNull(
            parseUpdateJson(
                """{"versionName": "1.1.0", "apk": "reader-helper-release.apk", "sha256": "not-a-sha"}"""
            )
        )
        assertNull(
            parseUpdateJson(
                """{"versionName": "1.1.0", "apk": "reader-helper-release.apk", "sha256": "${"z".repeat(64)}"}"""
            )
        )
    }

    @Test
    fun `update json drops non-positive code and size`() {
        val info = parseUpdateJson(
            """{"versionName": "1.1.0", "apk": "reader-helper-release.apk", "versionCode": 0, "size": 0}"""
        )
        assertNotNull(info)
        assertNull(info?.versionCode)
        assertNull(info?.sizeBytes)
    }

    @Test
    fun `update json with bad syntax returns null`() {
        assertNull(parseUpdateJson("{not valid json"))
        assertNull(parseUpdateJson(""))
    }

    @Test
    fun `parses github release with named asset`() {
        val info = parseGitHubRelease(
            """
            {
              "tag_name": "v1.1.0",
              "body": "更新说明",
              "assets": [
                {
                  "name": "notes.txt",
                  "browser_download_url": "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.1.0/notes.txt",
                  "size": 10
                },
                {
                  "name": "reader-helper-release.apk",
                  "browser_download_url": "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.1.0/reader-helper-release.apk",
                  "size": 12345678
                }
              ]
            }
            """.trimIndent()
        )
        assertNotNull(info)
        assertEquals("1.1.0", info?.versionName)
        assertNull(info?.versionCode)
        assertEquals("更新说明", info?.notes)
        assertEquals(
            "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.1.0/" +
                "reader-helper-release.apk",
            info?.apkUrl
        )
        assertNull(info?.sha256)
        assertEquals(12345678L, info?.sizeBytes)
    }

    @Test
    fun `github release requires the named apk asset`() {
        val info = parseGitHubRelease(
            """
            {
              "tag_name": "v1.2.0",
              "assets": [
                {
                  "name": "other.apk",
                  "browser_download_url": "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.2.0/other.apk",
                  "size": 100
                },
                {
                  "name": "another.apk",
                  "browser_download_url": "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.2.0/another.apk",
                  "size": 200
                }
              ]
            }
            """.trimIndent()
        )
        // 不猜测第一个 apk：误传 debug/其他 APK 时宁可失败，也不能引导用户装错包
        assertNull(info)
    }

    @Test
    fun `github release parses sha256 digest`() {
        val info = parseGitHubRelease(
            """
            {
              "tag_name": "v1.1.0",
              "assets": [
                {
                  "name": "reader-helper-release.apk",
                  "browser_download_url": "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.1.0/reader-helper-release.apk",
                  "size": 12345678,
                  "digest": "sha256:${sha256.uppercase()}"
                }
              ]
            }
            """.trimIndent()
        )
        assertNotNull(info)
        assertEquals(sha256, info?.sha256)
    }

    @Test
    fun `github release ignores non sha256 digest`() {
        val info = parseGitHubRelease(
            """
            {
              "tag_name": "v1.1.0",
              "assets": [
                {
                  "name": "reader-helper-release.apk",
                  "browser_download_url": "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.1.0/reader-helper-release.apk",
                  "digest": "md5:abc"
                }
              ]
            }
            """.trimIndent()
        )
        assertNotNull(info)
        assertNull(info?.sha256)
    }

    @Test
    fun `github release ignores malformed sha256 digest`() {
        val info = parseGitHubRelease(
            """
            {
              "tag_name": "v1.1.0",
              "assets": [
                {
                  "name": "reader-helper-release.apk",
                  "browser_download_url": "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.1.0/reader-helper-release.apk",
                  "digest": "sha256:${"z".repeat(64)}"
                }
              ]
            }
            """.trimIndent()
        )
        assertNotNull(info)
        assertNull(info?.sha256)
    }

    @Test
    fun `github release rejects non github download url`() {
        assertNull(
            parseGitHubRelease(
                """
                {
                  "tag_name": "v9.9.9",
                  "assets": [
                    {
                      "name": "reader-helper-release.apk",
                      "browser_download_url": "https://evil.example/app.apk"
                    }
                  ]
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `github release truncates body`() {
        val longBody = "x".repeat(3000)
        val info = parseGitHubRelease(
            """
            {
              "tag_name": "v1.3.0",
              "body": "$longBody",
              "assets": [
                {
                  "name": "reader-helper-release.apk",
                  "browser_download_url": "https://github.com/ricardochen1996/ebook-helper/releases/download/v1.3.0/reader-helper-release.apk"
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(2000, info?.notes?.length)
    }

    @Test
    fun `github release with missing or bad fields returns null`() {
        assertNull(parseGitHubRelease("{not valid json"))
        assertNull(parseGitHubRelease(""))
        assertNull(parseGitHubRelease("""{"assets": []}"""))
        assertNull(parseGitHubRelease("""{"tag_name": "v1.1.0"}"""))
        assertNull(
            parseGitHubRelease(
                """{"tag_name": "v1.1.0", "assets": [{"name": "notes.txt"}]}"""
            )
        )
    }
}
