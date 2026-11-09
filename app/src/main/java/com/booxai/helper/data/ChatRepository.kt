package com.booxai.helper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

class ChatRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
) {
    private val bookDao = db.bookDao()
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()

    fun observeBooks(): Flow<List<BookSummary>> = bookDao.observeSummaries()

    fun observeSessions(bookId: Long): Flow<List<SessionSummary>> = sessionDao.observeSummaries(bookId)

    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>> = messageDao.observeBySession(sessionId)

    suspend fun book(id: Long): BookEntity? = withContext(Dispatchers.IO) { bookDao.byId(id) }

    suspend fun session(id: Long): SessionEntity? = withContext(Dispatchers.IO) { sessionDao.byId(id) }

    suspend fun messages(sessionId: Long): List<MessageEntity> = withContext(Dispatchers.IO) {
        messageDao.bySession(sessionId)
    }

    suspend fun lastPageText(sessionId: Long): String? = withContext(Dispatchers.IO) {
        messageDao.lastUserMessageWithPage(sessionId)?.pageText
    }

    /**
     * 找书；书名未知时归入“未命名书籍”。同名书自动合并。
     */
    suspend fun resolveBook(title: String?): BookEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val display = title?.trim().orEmpty().ifEmpty { UNKNOWN_TITLE }
        val normalized = normalizeTitle(display)
        val existing = bookDao.byNormalizedTitle(normalized)
        if (existing != null) {
            bookDao.touch(existing.id, now)
            settings.lastBookTitle = existing.title
            return@withContext existing
        }
        val id = try {
            bookDao.insert(
                BookEntity(
                    title = display,
                    normalizedTitle = normalized,
                    createdAt = now,
                    lastOpenedAt = now,
                )
            )
        } catch (_: Exception) {
            // 并发插入时回退到已有记录
            bookDao.byNormalizedTitle(normalized)?.id ?: throw IllegalStateException("无法创建书籍")
        }
        settings.lastBookTitle = display
        bookDao.byId(id) ?: throw IllegalStateException("无法创建书籍")
    }

    /**
     * 修改书名；若目标书名已存在则合并会话。
     */
    suspend fun renameBook(oldBookId: Long, newTitle: String): Long = withContext(Dispatchers.IO) {
        val old = bookDao.byId(oldBookId) ?: return@withContext oldBookId
        val display = newTitle.trim()
        if (display.isEmpty()) return@withContext oldBookId
        val normalized = normalizeTitle(display)
        if (normalized == old.normalizedTitle) {
            if (old.title != display) bookDao.update(old.copy(title = display))
            settings.lastBookTitle = display
            return@withContext old.id
        }
        val now = System.currentTimeMillis()
        val existing = bookDao.byNormalizedTitle(normalized)
        if (existing != null && existing.id != old.id) {
            sessionDao.reassignBook(old.id, existing.id)
            bookDao.delete(old)
            bookDao.touch(existing.id, now)
            settings.lastBookTitle = existing.title
            existing.id
        } else {
            bookDao.update(old.copy(title = display, normalizedTitle = normalized))
            settings.lastBookTitle = display
            old.id
        }
    }

    suspend fun getOrCreateSession(bookId: Long): SessionEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val latest = sessionDao.latestByBook(bookId)
        if (latest != null && now - latest.lastMessageAt < SESSION_GAP_MS) {
            return@withContext latest
        }
        createSessionInternal(bookId, now)
    }

    suspend fun createSession(bookId: Long): SessionEntity = withContext(Dispatchers.IO) {
        createSessionInternal(bookId, System.currentTimeMillis())
    }

    private suspend fun createSessionInternal(bookId: Long, now: Long): SessionEntity {
        val id = sessionDao.insert(
            SessionEntity(
                bookId = bookId,
                startedAt = now,
                lastMessageAt = now,
            )
        )
        bookDao.touch(bookId, now)
        return sessionDao.byId(id) ?: throw IllegalStateException("无法创建会话")
    }

    suspend fun addMessage(
        sessionId: Long,
        role: String,
        content: String,
        pageText: String?,
        bookTitle: String?,
        provider: String? = null,
        model: String? = null,
        interrupted: Boolean = false,
    ): MessageEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                role = role,
                content = content,
                pageText = pageText,
                bookTitle = bookTitle,
                provider = provider,
                model = model,
                createdAt = now,
                interrupted = interrupted,
            )
        )
        sessionDao.touch(
            sessionId,
            now,
            if (role == ROLE_USER) content.take(60) else null
        )
        sessionDao.byId(sessionId)?.let { session ->
            bookDao.touch(session.bookId, now)
        }
        messageDao.lastMessage(sessionId) ?: MessageEntity(
            id = id,
            sessionId = sessionId,
            role = role,
            content = content,
            pageText = pageText,
            bookTitle = bookTitle,
            provider = provider,
            model = model,
            createdAt = now,
            interrupted = interrupted,
        )
    }

    suspend fun updateMessage(id: Long, content: String, interrupted: Boolean) = withContext(Dispatchers.IO) {
        messageDao.updateContent(id, content, interrupted)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        messageDao.clearAll()
        db.clearAllTables()
    }

    private fun normalizeTitle(title: String): String {
        return title
            .replace("《", "")
            .replace("》", "")
            .replace(Regex("\\s+"), "")
            .lowercase(Locale.ROOT)
            .ifEmpty { normalizeTitle(UNKNOWN_TITLE) }
    }

    companion object {
        const val UNKNOWN_TITLE = "未命名书籍"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        private const val SESSION_GAP_MS = 30 * 60 * 1000L
    }
}
