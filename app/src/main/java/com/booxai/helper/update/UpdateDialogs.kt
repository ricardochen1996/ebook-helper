package com.booxai.helper.update

import android.os.SystemClock
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.booxai.helper.App
import com.booxai.helper.BuildConfig
import com.booxai.helper.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NOTES_LIMIT = 600
private const val PROGRESS_STEP = 5
private const val PROGRESS_INTERVAL_MS = 500L

/**
 * 更新相关的对话框。
 *
 * 墨水屏刷新慢，文案尽量短、进度条只在必要时重绘：
 * 进度每跨过 5% 或距上次刷新超过 300ms 才更新一次，避免残影。
 */
object UpdateDialogs {

    /**
     * Activity 正在结束或已经销毁时，弹窗、Toast 和界面回调都不应再触碰它：
     * 否则可能把窗口泄漏到下一个页面上，或刷新已经不可见的 ViewBinding。
     */
    private fun canUseUi(activity: AppCompatActivity): Boolean =
        !activity.isFinishing && !activity.isDestroyed

    /**
     * 弹窗还要求 Activity 至少处于 STARTED：页面在后台时弹窗虽然能创建，
     * 但用户看不见，等回到前台时错误早已过时，不如等下次操作再提示。
     */
    private fun canShowDialog(activity: AppCompatActivity): Boolean =
        canUseUi(activity) &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun toast(activity: AppCompatActivity, resId: Int) {
        // Toast 可能比 Activity 活得更久，用 Application Context 避免它持有页面引用
        Toast.makeText(activity.applicationContext, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(activity: AppCompatActivity, message: CharSequence) {
        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    fun showAvailable(
        activity: AppCompatActivity,
        info: UpdateInfo,
        onFinished: () -> Unit = {},
    ) {
        if (!canUseUi(activity)) {
            // Activity 已销毁时不能再回调刷新界面，避免操作已销毁的 ViewBinding
            if (!activity.isDestroyed) onFinished()
            return
        }
        val notes = plainNotes(info.notes)
        val message = notes.ifBlank {
            activity.getString(
                R.string.update_dialog_message,
                BuildConfig.VERSION_NAME,
                info.versionName,
            )
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, info.versionName))
            .setMessage(message)
            .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                downloadAndInstall(activity, info, onFinished)
            }
            .setNegativeButton(R.string.update_dialog_later) { _, _ -> onFinished() }
            // 按返回键等同「稍后」，确保调用方能统一刷新界面
            .setOnCancelListener { onFinished() }
            .create()
        // 墨水屏上弹窗动画只会拖慢响应并留下残影，必须在 show() 前关闭
        dialog.window?.setWindowAnimations(0)
        dialog.show()
    }

    private fun downloadAndInstall(
        activity: AppCompatActivity,
        info: UpdateInfo,
        onFinished: () -> Unit,
    ) {
        if (!canUseUi(activity)) {
            if (!activity.isDestroyed) onFinished()
            return
        }
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_update_progress, null)
        val tvProgress = view.findViewById<TextView>(R.id.tvUpdateProgress)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressUpdate)
        progressBar.max = 100
        progressBar.progress = 0
        tvProgress.text = activity.getString(R.string.update_downloading, 0)

        // 下载可能卡在慢网络上，给用户留一个取消入口；取消后 UpdateManager 会删除半成品
        var downloadJob: Job? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, info.versionName))
            .setView(view)
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                downloadJob?.let {
                    it.cancel()
                    toast(activity, R.string.update_download_cancelled)
                }
            }
            // 禁用返回键和点外部关闭，避免误触取消正在进行的下载；取消只走明确的按钮
            .setCancelable(false)
            .create()
        // 动画必须在 show() 前关闭，show() 之后再设置对已创建的窗口不生效
        dialog.window?.setWindowAnimations(0)
        dialog.show()

        downloadJob = activity.lifecycleScope.launch {
            try {
                var lastPercent = 0
                var lastShownAt = 0L
                val result = App.updates.download(info) { percent ->
                    val now = SystemClock.elapsedRealtime()
                    // 进度回调来自 IO 线程，节流判断也放在这里，减少主线程投递次数。
                    // 同时满足「跨过 5%」和「距上次 ≥500ms」才重绘，避免高频刷新留残影。
                    if (percent >= 100 ||
                        (percent - lastPercent >= PROGRESS_STEP &&
                            now - lastShownAt >= PROGRESS_INTERVAL_MS)
                    ) {
                        lastPercent = percent
                        lastShownAt = now
                        activity.runOnUiThread {
                            // 取消后弹窗已关闭，不再刷新上面的控件
                            if (dialog.isShowing && canUseUi(activity)) {
                                progressBar.progress = percent
                                tvProgress.text =
                                    activity.getString(R.string.update_downloading, percent)
                            }
                        }
                    }
                }
                // 先收起进度窗，再按结果弹安装/错误提示，避免两个对话框叠在一起
                dialog.dismiss()
                if (!canUseUi(activity)) return@launch
                when (result) {
                    is DownloadResult.Success -> {
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            // 解析 APK 清单是磁盘 IO，放到后台线程，避免墨水屏界面卡顿
                            val installResult = withContext(Dispatchers.IO) {
                                App.updates.install(result.file)
                            }
                            handleInstallResult(activity, installResult)
                        } else {
                            // 应用在后台时系统会拦截启动安装界面；
                            // 安装包已缓存，回到设置页可以手动安装
                            toast(activity, R.string.update_downloaded_background)
                        }
                    }
                    is DownloadResult.Failed -> {
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            showDownloadError(activity, result)
                        } else {
                            // 后台失败时弹窗会被丢弃，用 Toast 留下可操作的信息
                            toast(
                                activity,
                                activity.getString(R.string.update_failed_background, result.message),
                            )
                        }
                    }
                }
            } finally {
                // 成功、失败或协程被取消都要关闭对话框，否则 Activity 结束时窗口会泄漏
                if (dialog.isShowing) dialog.dismiss()
                // Activity 已销毁时不能再回调刷新界面
                if (!activity.isDestroyed) onFinished()
            }
        }
    }

    /**
     * 安装结果统一在这里反馈，设置页「安装已下载的更新」也复用它。
     */
    fun handleInstallResult(activity: AppCompatActivity, result: InstallResult) {
        if (!canUseUi(activity)) return
        when (result) {
            InstallResult.Started -> toast(activity, R.string.update_install_started)

            InstallResult.NeedsUnknownSourcesPermission -> {
                if (canShowDialog(activity)) {
                    showUnknownSourcesDialog(activity)
                } else {
                    toast(activity, R.string.update_downloaded_background)
                }
            }

            is InstallResult.Failed -> {
                if (canShowDialog(activity)) {
                    showError(activity, result.message)
                } else {
                    // 页面在后台时弹窗会被丢弃，至少用 Toast 留下失败原因
                    toast(
                        activity,
                        activity.getString(R.string.update_failed_background, result.message),
                    )
                }
            }
        }
    }

    fun showError(activity: AppCompatActivity, message: String) {
        if (!canShowDialog(activity)) return
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_install_failed)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
        // 动画必须在 show() 前关闭，否则窗口已经按动画参数创建好了
        dialog.window?.setWindowAnimations(0)
        dialog.show()
    }

    fun showLatest(activity: AppCompatActivity) {
        if (!canUseUi(activity)) return
        toast(activity, R.string.settings_update_latest)
    }

    private fun showUnknownSourcesDialog(activity: AppCompatActivity) {
        if (!canShowDialog(activity)) return
        val message = activity.getString(R.string.update_need_permission_message) +
            "\n\n" + activity.getString(R.string.update_downloaded_hint)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_need_permission_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_open_permission) { _, _ ->
                App.updates.openUnknownSourcesSettings()
            }
            .setNegativeButton(R.string.update_dialog_later, null)
            .create()
        dialog.window?.setWindowAnimations(0)
        dialog.show()
    }

    private fun showDownloadError(
        activity: AppCompatActivity,
        result: DownloadResult.Failed,
    ) {
        if (!canShowDialog(activity)) return
        // 完整性校验失败和网络失败用不同标题，避免让用户误以为是网络问题
        val title = if (result.verificationFailed) {
            R.string.update_verify_failed
        } else {
            R.string.update_download_failed
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(result.message)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
        dialog.window?.setWindowAnimations(0)
        dialog.show()
    }

    /**
     * GitHub Release 正文是 Markdown，墨水屏对话框里显示原始标记很乱，
     * 这里做最小清理后截断，避免弹窗过长。
     */
    private fun plainNotes(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace("\r\n", "\n")
        text = text.replace(Regex("```[\\s\\S]*?```"), " ")
        text = text.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        text = text.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)")) { it.groupValues[1] }
        text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        text = text.replace(Regex("(?m)^\\s*>\\s?"), "")
        text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "· ")
        text = text.replace(Regex("`([^`]*)`")) { it.groupValues[1] }
        text = text.replace(Regex("\\*\\*|__|~~"), "")
        text = text.replace(Regex("<[^>]+>"), "")
        text = text.replace(Regex("\\n{3,}"), "\n\n").trim()
        if (text.length > NOTES_LIMIT) {
            // 按 UTF-16 长度截断可能切掉 emoji 的半个代理项，留一个完整字符再补省略号
            var end = NOTES_LIMIT
            if (text[end - 1].isHighSurrogate()) end -= 1
            text = text.take(end) + "…"
        }
        return text
    }
}
