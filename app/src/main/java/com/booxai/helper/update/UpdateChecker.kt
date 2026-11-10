package com.booxai.helper.update

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private const val REPO_SLUG = "ricardochen1996/ebook-helper"
private const val REPO_BASE = "https://github.com/$REPO_SLUG"
private const val UPDATE_JSON_URL = "$REPO_BASE/releases/latest/download/update.json"
private const val RELEASE_API_URL = "https://api.github.com/repos/$REPO_SLUG/releases/latest"
private const val DOWNLOAD_BASE = "$REPO_BASE/releases/latest/download/"
private const val RELEASE_APK_NAME = "reader-helper-release.apk"
private const val USER_AGENT = "BanDu-Updater"
private const val MAX_NOTES_LENGTH = 2000

// 版本名会进入弹窗标题和缓存文件名，长度必须有上限
private const val MAX_VERSION_NAME_LENGTH = 64

// 更新元数据只有几十 KB；给读取设置上限，避免异常响应把内存打满
private const val MAX_METADATA_BYTES = 1024 * 1024
private const val READ_BUFFER_SIZE = 8 * 1024

// 安装包只允许来自 GitHub 官方域名；解析后按 host 精确比较，
// 不用字符串前缀判断，避免被相似域名误导
private val ALLOWED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com")

private val gson = Gson()

/**
 * 检查 GitHub Release 上的新版本。
 *
 * 主路径读取 Release 资产里的 update.json（体积小，可带 sha256）；
 * 旧版本还没有 update.json 时回退到 GitHub API，但 API 没有构建号和 sha256。
 */
class UpdateChecker {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersionName: String, currentVersionCode: Long): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            try {
                when (val primary = fetchPrimary()) {
                    is Fetch.Success ->
                        evaluate(primary.info, currentVersionName, currentVersionCode)
                    is Fetch.Failure -> {
                        // 主路径 404 往往只是没上传 update.json，不代表真的没有发布版本，
                        // 这种情况走 API 兜底；但已经拿到 update.json 却内容非法时不能兜底：
                        // API 路径没有 sha256，回退等于悄悄放弃完整性校验。
                        if (!primary.fallbackAllowed) {
                            UpdateCheckResult.Failed(primary.message)
                        } else {
                            // 协程已取消时不再多发一次网络请求
                            currentCoroutineContext().ensureActive()
                            when (val fallback = fetchGitHubRelease()) {
                                is Fetch.Success ->
                                    evaluate(fallback.info, currentVersionName, currentVersionCode)
                                is Fetch.Failure -> UpdateCheckResult.Failed(fallback.message)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateCheckResult.Failed("检查更新失败，请稍后重试")
            }
        }

    private suspend fun fetchPrimary(): Fetch {
        val body = when (val response = get(UPDATE_JSON_URL, accept = null)) {
            is Http.Ok -> response.body
            is Http.Error -> return Fetch.Failure(response.message, response.fallbackAllowed)
        }
        val info = parseUpdateJson(body)
            ?: return Fetch.Failure("更新信息格式不正确")
        if (!isAllowedDownloadUrl(info.apkUrl)) {
            return Fetch.Failure("更新信息中的安装包地址无效")
        }
        return Fetch.Success(info)
    }

    private suspend fun fetchGitHubRelease(): Fetch {
        val body = when (val response = get(RELEASE_API_URL, accept = "application/vnd.github+json")) {
            is Http.Ok -> response.body
            is Http.Error -> return Fetch.Failure(response.message)
        }
        val info = parseGitHubRelease(body) ?: return Fetch.Failure("无法解析 GitHub 发布信息")
        if (!isAllowedDownloadUrl(info.apkUrl)) {
            return Fetch.Failure("下载地址不是 GitHub 官方域名，已拒绝")
        }
        return Fetch.Success(info)
    }

    private suspend fun get(url: String, accept: String?): Http {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (accept != null) builder.header("Accept", accept)
        val request = builder.build()
        val call = client.newCall(request)
        val watcher = startCallCancellationWatcher(call)
        return try {
            call.execute().use { response ->
                // OkHttp 会自动跟随重定向；校验最终落点，避免响应把请求引到非 GitHub 域名
                if (!isTrustedRequestUrl(response.request.url)) {
                    return Http.Error("更新请求被重定向到不受信任的地址，已拒绝")
                }
                when {
                    response.isSuccessful -> readMetadata(response.body)
                    response.code == 404 ->
                        Http.Error("还没有发布版本", fallbackAllowed = true)
                    response.code == 403 || response.code == 429 ->
                        Http.Error("GitHub 访问被限流，请稍后再试", fallbackAllowed = true)
                    response.code >= 500 ->
                        Http.Error("GitHub 服务暂时不可用，请稍后再试", fallbackAllowed = true)
                    else ->
                        Http.Error("检查更新失败（HTTP ${response.code}）", fallbackAllowed = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 取消时 watcher 会 call.cancel()，底层通常抛 IOException；
            // 这里先确认协程状态，避免把「取消」误报成网络失败。
            currentCoroutineContext().ensureActive()
            when (e) {
                is SocketTimeoutException ->
                    Http.Error("连接 GitHub 超时，请稍后重试", fallbackAllowed = true)
                is UnknownHostException ->
                    Http.Error("无法连接 GitHub，请检查网络", fallbackAllowed = true)
                is SSLException ->
                    Http.Error("与 GitHub 的安全连接失败", fallbackAllowed = true)
                is IOException ->
                    Http.Error("网络异常，请稍后重试", fallbackAllowed = true)
                else ->
                    Http.Error("检查更新失败，请稍后重试", fallbackAllowed = true)
            }
        } finally {
            // 响应体已经读完（或异常退出），取消 watcher；
            // 它和 call.cancel() 都是幂等的。
            watcher.cancel()
        }
    }

    private fun readMetadata(body: ResponseBody?): Http {
        if (body == null) return Http.Error("更新服务器未返回内容")
        if (body.contentLength() > MAX_METADATA_BYTES) {
            return Http.Error("更新信息过大，已拒绝")
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            body.byteStream().use { input: InputStream ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > MAX_METADATA_BYTES) {
                        return Http.Error("更新信息过大，已拒绝")
                    }
                }
            }
        } catch (_: IOException) {
            // 读一半断流属于网络问题，可以换 API 再试一次
            return Http.Error("读取更新信息失败，请稍后重试", fallbackAllowed = true)
        }
        val text = output.toByteArray().toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return Http.Error("更新信息为空，请稍后重试")
        return Http.Ok(text)
    }

    private fun evaluate(
        info: UpdateInfo,
        currentVersionName: String,
        currentVersionCode: Long,
    ): UpdateCheckResult {
        // 有构建号时以构建号为准（改名/带后缀的版本名不影响判断）
        val newer = info.versionCode?.let { it.toLong() > currentVersionCode }
            ?: VersionComparator.isNewer(info.versionName, currentVersionName)
        return if (newer) UpdateCheckResult.Available(info) else UpdateCheckResult.UpToDate
    }

    private sealed interface Fetch {
        data class Success(val info: UpdateInfo) : Fetch

        data class Failure(
            val message: String,
            // 元数据非法时为 false：回退到 API 会绕过 sha256/大小校验
            val fallbackAllowed: Boolean = false,
        ) : Fetch
    }

    private sealed interface Http {
        data class Ok(val body: String) : Http

        data class Error(
            val message: String,
            // 网络/HTTP 层失败可以换 API 再试；响应内容异常时不应回退
            val fallbackAllowed: Boolean = false,
        ) : Http
    }
}

/**
 * 解析主路径的 update.json。
 *
 * apk 只允许是 .apk 文件名：带 "://"、路径分隔符或查询串的值一律拒绝，
 * 下载地址固定拼到本仓库的 latest/download 下，避免被元数据带到第三方站点。
 */
internal fun parseUpdateJson(json: String): UpdateInfo? {
    return try {
        val dto = gson.fromJson(json, UpdateJsonDto::class.java) ?: return null
        val versionName = dto.versionName?.trim().orEmpty()
        val apk = dto.apk?.trim().orEmpty()
        if (versionName.isEmpty() || apk.isEmpty()) return null
        if (versionName.length > MAX_VERSION_NAME_LENGTH) return null
        if (!isSafeApkName(apk)) return null
        val sha256 = normalizeSha256(dto.sha256)
        // 写了 sha256 却格式非法时直接拒绝：静默忽略等于放弃完整性校验
        if (sha256 == null && !dto.sha256.isNullOrBlank()) return null
        UpdateInfo(
            versionName = versionName,
            versionCode = dto.versionCode?.takeIf { it > 0 },
            notes = truncateNotes(dto.notes),
            apkUrl = DOWNLOAD_BASE + apk,
            sha256 = sha256,
            sizeBytes = dto.size?.takeIf { it > 0 },
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * 解析 GitHub API 的 latest release。没有构建号与 sha256，
 * 只取 tag、正文和安装包资产；未知字段由 Gson 自动忽略。
 */
internal fun parseGitHubRelease(json: String): UpdateInfo? {
    return try {
        val dto = gson.fromJson(json, GitHubReleaseDto::class.java) ?: return null
        val versionName = dto.tag_name
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.trim()
            .orEmpty()
        if (versionName.isEmpty() || versionName.length > MAX_VERSION_NAME_LENGTH) return null
        // 只认 CI 产出的正式资产名：避免 Release 误传 debug/其他 APK 时把用户带到错误安装包
        val asset = dto.assets.orEmpty().firstOrNull { asset ->
            asset.name?.trim() == RELEASE_APK_NAME &&
                isAllowedDownloadUrl(asset.browser_download_url?.trim().orEmpty())
        } ?: return null
        UpdateInfo(
            versionName = versionName,
            versionCode = null,
            notes = truncateNotes(dto.body),
            apkUrl = asset.browser_download_url?.trim().orEmpty(),
            // GitHub API 的 digest 形如 "sha256:<hex>"，有就顺带拿到校验和
            sha256 = parseDigest(asset.digest),
            sizeBytes = asset.size?.takeIf { it > 0 },
        )
    } catch (_: Exception) {
        null
    }
}

private fun isAllowedDownloadUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.scheme == "https" && parsed.host in ALLOWED_DOWNLOAD_HOSTS
}

/**
 * 网络请求允许的落点：GitHub 主站、API 以及其资产 CDN。
 * Release 资产会从 github.com 跳到 *.githubusercontent.com，属于正常重定向。
 */
private fun isTrustedRequestUrl(url: HttpUrl): Boolean {
    if (url.scheme != "https") return false
    val host = url.host
    return host == "github.com" ||
        host == "api.github.com" ||
        host.endsWith(".githubusercontent.com")
}

private fun isSafeApkName(name: String): Boolean {
    if (name.contains("://")) return false
    // 只接受当前 Release 里的 .apk 文件名，避免元数据被改成下载其它资产
    if (!name.endsWith(".apk", ignoreCase = true)) return false
    // GitHub 资产名不会包含路径分隔符/查询串；出现即视为元数据异常
    return name.none {
        it == '/' || it == '\\' || it == '?' || it == '#' || it.isISOControl()
    }
}

/** 截断说明文字；避免从代理对中间切开，留下无效字符。 */
private fun truncateNotes(raw: String?): String {
    val notes = raw.orEmpty()
    if (notes.length <= MAX_NOTES_LENGTH) return notes
    val end = if (notes[MAX_NOTES_LENGTH - 1].isHighSurrogate()) {
        MAX_NOTES_LENGTH - 1
    } else {
        MAX_NOTES_LENGTH
    }
    return notes.substring(0, end)
}

/** GitHub 资产 digest 目前是 "sha256:<64hex>"；其它算法或格式忽略。 */
private fun parseDigest(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (!value.startsWith("sha256:", ignoreCase = true)) return null
    return normalizeSha256(value.substringAfter(':'))
}

private fun normalizeSha256(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.length != 64) return null
    if (value.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return value
}

private data class UpdateJsonDto(
    val versionName: String? = null,
    val versionCode: Int? = null,
    val notes: String? = null,
    val apk: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
)

private data class GitHubReleaseDto(
    val tag_name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAssetDto>? = null,
)

private data class GitHubAssetDto(
    val name: String? = null,
    val browser_download_url: String? = null,
    val size: Long? = null,
    val digest: String? = null,
)
