package com.booxai.helper.update

import java.io.File

/**
 * 一次更新检查的结果。
 *
 * versionCode 可能为空：GitHub API 回退路径只有 tag 名，没有构建号；
 * 此时由 [VersionComparator] 按版本名判断。
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int?,
    val notes: String,
    val apkUrl: String,
    val sha256: String?,
    val sizeBytes: Long?,
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}

sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult

    data class Failed(
        val message: String,
        // 大小/SHA-256 等完整性校验失败，界面上用更准确的标题
        val verificationFailed: Boolean = false,
    ) : DownloadResult
}

sealed interface InstallResult {
    data object Started : InstallResult

    data object NeedsUnknownSourcesPermission : InstallResult

    data class Failed(val message: String) : InstallResult
}
