package com.booxai.helper.data

import android.content.Context
import java.io.File

/**
 * 会话截图持久化：截图放在 App 私有 files 目录，随消息记录一起用于回看。
 * 删除会话/书籍或清空历史时同步删除对应文件。
 */
object ScreenshotStore {

    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, "chat_images").apply { mkdirs() }
    }

    fun persist(sourcePath: String): String? {
        val source = File(sourcePath)
        if (!source.isFile) return null
        val targetDir = dir ?: return null
        return try {
            val target = File.createTempFile("page_", ".jpg", targetDir)
            source.copyTo(target, overwrite = true)
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun exists(path: String?): Boolean = path != null && File(path).isFile

    fun delete(paths: Collection<String>) {
        paths.forEach { path ->
            try {
                File(path).delete()
            } catch (_: Exception) {
            }
        }
    }

    fun clear() {
        dir?.listFiles()?.forEach { file ->
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }
}
