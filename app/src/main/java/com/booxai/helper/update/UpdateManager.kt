package com.booxai.helper.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.booxai.helper.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val USER_AGENT = "BanDu-Updater"
private const val APK_MIME = "application/vnd.android.package-archive"
private const val BUFFER_SIZE = 64 * 1024
private const val MAX_APK_BYTES = 200L * 1024 * 1024

// 安装包只允许从 GitHub 官方域名下载；重定向后的最终地址也要复核
private val ALLOWED_DOWNLOAD_HOSTS = setOf(
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)

/**
 * 更新的检查、下载与安装入口。
 *
 * 安装包只写到 cacheDir/updates，不申请存储权限，也不碰外部存储；
 * 所有网络/文件异常都转成带中文说明的失败结果，不向调用方抛出。
 */
class UpdateManager(private val appContext: Context) {

    private val checker = UpdateChecker()

    // 检查与下载各用一把锁：下载大文件时不应阻塞用户在设置页手动检查更新
    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()

    private val downloadClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 不设整包 callTimeout：慢网下载大安装包时 40 秒会误杀
        .build()

    suspend fun check(): UpdateCheckResult = checkMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val packageInfo = appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0)
                checker.check(BuildConfig.VERSION_NAME, packageInfo.longVersionCode)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateCheckResult.Failed("无法读取当前版本信息")
            }
        }
    }

    suspend fun download(
        info: UpdateInfo,
        onProgress: (percent: Int) -> Unit = {},
    ): DownloadResult = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                downloadInternal(info, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                DownloadResult.Failed(downloadErrorText(e))
            } catch (_: Exception) {
                DownloadResult.Failed("下载失败，请稍后重试")
            }
        }
    }

    /**
     * 返回缓存目录下比当前版本新的安装包，供设置页「安装已下载的更新」使用。
     * 已经安装过的旧包、以及下载中的 .part 文件都不算可用更新。
     */
    fun cachedApk(): File? {
        val dir = updateDir()
        if (!dir.isDirectory) return null
        val currentCode = BuildConfig.VERSION_CODE.toLong()
        var best: File? = null
        dir.listFiles()?.forEach { file ->
            // .part 可能正在被下载协程写入，不能在这里删；只忽略。
            // 遗留的 .part 会在下一次 downloadInternal 的 cleanupOldApks 里清掉。
            if (!file.isFile || !file.name.endsWith(".apk", ignoreCase = true)) {
                return@forEach
            }
            val parsed = parseCachedApkName(file.name)
            if (parsed == null) {
                file.delete()
                return@forEach
            }
            // 与 UpdateChecker.evaluate 保持一致：有构建号时以构建号为准
            val newer = parsed.versionCode?.let { it.toLong() > currentCode }
                ?: !VersionComparator.isNewer(BuildConfig.VERSION_NAME, parsed.versionName)
            if (!newer) {
                // 升级完成后旧安装包没有保留价值，顺手清理
                file.delete()
            } else if (best == null || file.lastModified() > best!!.lastModified()) {
                best = file
            }
        }
        return best
    }

    fun install(file: File): InstallResult {
        if (!file.isFile) return InstallResult.Failed("安装包不存在，请重新下载")
        val packageManager = appContext.packageManager
        // 校验包名、版本号和签名：即使缓存目录被替换，也不会把无关 APK 交给安装器。
        // 系统安装器也会做这些检查，这里提前做是为了给出可读的错误提示。
        val archive = try {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } catch (_: Exception) {
            null
        }
        if (archive == null || archive.packageName != appContext.packageName) {
            return InstallResult.Failed("安装包无效，请重新下载")
        }
        val installed = try {
            packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } catch (_: Exception) {
            null
        }
        if (installed == null || !sameSignatures(archive, installed)) {
            return InstallResult.Failed(
                "安装包签名与当前版本不一致，无法覆盖安装；如需更换签名，请卸载后重装（会丢失本地会话）"
            )
        }
        if (archive.longVersionCode <= installed.longVersionCode) {
            return InstallResult.Failed("安装包版本不高于当前版本，请重新检查更新")
        }
        if (!packageManager.canRequestPackageInstalls()) {
            return InstallResult.NeedsUnknownSourcesPermission
        }
        val uri = try {
            FileProvider.getUriForFile(
                appContext,
                appContext.packageName + ".fileprovider",
                file,
            )
        } catch (_: Exception) {
            return InstallResult.Failed("安装包路径无效，请重新下载")
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(flags)
        return try {
            appContext.startActivity(intent)
            InstallResult.Started
        } catch (_: ActivityNotFoundException) {
            // 少数 ROM 不响应 ACTION_VIEW，退回到系统保留的 ACTION_INSTALL_PACKAGE
            val fallback = Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .addFlags(flags)
            try {
                appContext.startActivity(fallback)
                InstallResult.Started
            } catch (_: ActivityNotFoundException) {
                InstallResult.Failed("无法启动安装程序，请检查系统安装器")
            } catch (_: Exception) {
                InstallResult.Failed("无法启动安装程序")
            }
        } catch (_: Exception) {
            InstallResult.Failed("无法启动安装程序")
        }
    }

    fun openUnknownSourcesSettings() {
        val packageUri = Uri.parse("package:${appContext.packageName}")
        try {
            appContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            // 部分定制 ROM 没有「安装未知应用」页，退到应用详情页让用户手动授权
            try {
                appContext.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun downloadInternal(
        info: UpdateInfo,
        onProgress: (percent: Int) -> Unit,
    ): DownloadResult {
        if (!isAllowedDownloadUrl(info.apkUrl)) {
            return DownloadResult.Failed("安装包地址无效，已取消下载")
        }
        // 先校验声明大小，避免超大更新把已下载好的旧包清掉之后才失败
        val declaredSize = info.sizeBytes
        if (declaredSize != null && declaredSize > MAX_APK_BYTES) {
            return DownloadResult.Failed("安装包超过 200MB，已取消下载")
        }
        val dir = updateDir()
        if (!dir.isDirectory && !dir.mkdirs()) {
            return DownloadResult.Failed("无法创建更新目录，请清理存储空间")
        }
        val target = File(dir, apkFileName(info))

        // 已有完整安装包时直接复用，避免重复下载大文件
        if (target.isFile && matchesExisting(target, info)) {
            onProgress(100)
            return DownloadResult.Success(target)
        }

        // 缓存目录只保留当前这次下载的安装包，避免旧版本占空间
        cleanupOldApks(dir)
        target.delete()
        // 先写 .part，校验通过后再改名：下载中途被杀进程也不会留下可被误装的半成品
        val part = File(dir, target.name + ".part")
        part.delete()

        var completed = false
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        val call = downloadClient.newCall(request)
        // watcher 必须活到响应体读完；否则取消时 socket 仍会阻塞到超时
        val watcher = startCallCancellationWatcher(call)
        try {
            call.execute().use { response ->
                if (!isTrustedFinalUrl(response.request.url)) {
                    return DownloadResult.Failed("下载被重定向到不受信任的地址，已取消")
                }
                if (!response.isSuccessful) {
                    return DownloadResult.Failed("下载失败（HTTP ${response.code}）")
                }
                val body = response.body
                    ?: return DownloadResult.Failed("下载失败：服务器未返回内容")
                val contentLength = body.contentLength()
                if (contentLength > MAX_APK_BYTES) {
                    return DownloadResult.Failed("安装包超过 200MB，已取消下载")
                }
                val total = if (contentLength > 0) contentLength else declaredSize ?: -1L
                val digest = info.sha256?.let { MessageDigest.getInstance("SHA-256") }
                var written = 0L
                var lastPercent = 0
                onProgress(0)
                body.byteStream().use { input ->
                    val output = try {
                        FileOutputStream(part)
                    } catch (e: IOException) {
                        throw StorageIOException(e)
                    }
                    output.use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            // 每读完一块检查取消，watcher 已负责 cancel() 唤醒阻塞的 read
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            try {
                                out.write(buffer, 0, read)
                            } catch (e: IOException) {
                                throw StorageIOException(e)
                            }
                            digest?.update(buffer, 0, read)
                            written += read
                            if (written > MAX_APK_BYTES) {
                                return DownloadResult.Failed("安装包超过 200MB，已取消下载")
                            }
                            if (total > 0) {
                                val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                if (written <= 0) {
                    return DownloadResult.Failed("下载失败：文件为空")
                }
                if (total > 0 && written != total) {
                    return DownloadResult.Failed("下载不完整，请重试", verificationFailed = true)
                }
                if (declaredSize != null && written != declaredSize) {
                    return DownloadResult.Failed("安装包大小不一致，请重试", verificationFailed = true)
                }
                if (digest != null) {
                    val actual = digest.digest().toHex()
                    if (!actual.equals(info.sha256, ignoreCase = true)) {
                        return DownloadResult.Failed("安装包校验失败", verificationFailed = true)
                    }
                }
                // 原子改名：只有校验通过的完整文件才会以 .apk 出现在缓存目录
                if (!part.renameTo(target)) {
                    return DownloadResult.Failed("保存安装包失败，请检查存储空间")
                }
                if (lastPercent < 100) onProgress(100)
                completed = true
                return DownloadResult.Success(target)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageIOException) {
            return DownloadResult.Failed("存储空间不足或无法写入安装包，请清理存储后重试")
        } catch (e: IOException) {
            // 取消时 watcher 会 cancel 请求，底层通常抛 IOException，
            // 先确认协程状态再报错。
            currentCoroutineContext().ensureActive()
            return DownloadResult.Failed(downloadErrorText(e))
        } finally {
            watcher.cancel()
            if (!completed) {
                // 半成品或校验失败的包不能留在缓存里，避免被「安装已下载的更新」误用
                part.delete()
            }
        }
    }

    private fun isTrustedFinalUrl(url: HttpUrl): Boolean =
        url.scheme == "https" && url.host in ALLOWED_DOWNLOAD_HOSTS

    private fun updateDir(): File = File(appContext.cacheDir, "updates")

    private fun matchesExisting(file: File, info: UpdateInfo): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        val sha = info.sha256
        if (sha != null) {
            // 版本名相同但内容可能被替换，有 sha 时必须校验
            return sha.equals(sha256Of(file), ignoreCase = true)
        }
        val size = info.sizeBytes
        return size != null && size > 0 && file.length() == size
    }

    private fun sha256Of(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    } catch (_: Exception) {
        null
    }

    private fun cleanupOldApks(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile &&
                (file.name.endsWith(".apk", ignoreCase = true) ||
                    file.name.endsWith(".part", ignoreCase = true))
            ) {
                file.delete()
            }
        }
    }

    /** 比较两个 APK 的签名证书；任何一方读不到签名都按不一致处理。 */
    private fun sameSignatures(a: PackageInfo, b: PackageInfo): Boolean {
        fun signaturesOf(info: PackageInfo): Set<String>? =
            info.signingInfo?.apkContentsSigners
                ?.map { it.toByteArray().toHex() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }

        val aSignatures = signaturesOf(a) ?: return false
        val bSignatures = signaturesOf(b) ?: return false
        return aSignatures == bSignatures
    }

    private fun downloadErrorText(e: IOException): String = when (e) {
        is SocketTimeoutException -> "下载超时，请重试"
        is UnknownHostException -> "无法连接下载服务器，请检查网络"
        else -> "下载失败，请检查网络后重试"
    }
}

private fun isAllowedDownloadUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.scheme == "https" && parsed.host in ALLOWED_DOWNLOAD_HOSTS
}

internal data class CachedApkName(val versionName: String, val versionCode: Int?)

/** 从 bandu-<version>[-c<code>].apk 还原版本信息；不是本模块命名的文件返回 null。 */
internal fun parseCachedApkName(name: String): CachedApkName? {
    if (!name.startsWith("bandu-") || !name.endsWith(".apk", ignoreCase = true)) {
        return null
    }
    val body = name.removePrefix("bandu-").removeSuffix(".apk")
    if (body.isEmpty()) return null
    // 构建号是可选后缀，用来处理「只升 versionCode、版本名不变」的发布
    val match = Regex("^(.*)-c(\\d+)$").matchEntire(body)
    val versionName = match?.groupValues?.get(1) ?: body
    if (versionName.isEmpty()) return null
    return CachedApkName(versionName, match?.groupValues?.get(2)?.toIntOrNull())
}

internal fun apkFileName(info: UpdateInfo): String {
    // 版本名来自网络，限制字符集，避免拼出意外的路径
    val safe = info.versionName.trim()
        .map { c -> if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_' }
        .joinToString("")
        .take(48)
        .ifEmpty { "unknown" }
    val code = info.versionCode?.takeIf { it > 0 }?.let { "-c$it" }.orEmpty()
    return "bandu-$safe$code.apk"
}

private fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        chars[i * 2] = hex[value ushr 4]
        chars[i * 2 + 1] = hex[value and 0x0f]
    }
    return String(chars)
}

/** 把本地写盘失败和网络失败区分开，给用户可操作的提示。 */
private class StorageIOException(cause: IOException) : IOException(cause)
