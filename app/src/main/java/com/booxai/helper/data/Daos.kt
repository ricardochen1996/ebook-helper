package com.booxai.helper.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query(
        "SELECT b.*, " +
            "(SELECT COUNT(*) FROM sessions s WHERE s.bookId = b.id) AS sessionCount, " +
            "(SELECT MAX(s.lastMessageAt) FROM sessions s WHERE s.bookId = b.id) AS lastMessageAt " +
            "FROM books b ORDER BY b.lastOpenedAt DESC"
    )
    fun observeSummaries(): Flow<List<BookSummary>>

    @Query("SELECT * FROM books WHERE normalizedTitle = :normalized LIMIT 1")
    suspend fun byNormalizedTitle(normalized: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): BookEntity?

    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC LIMIT 1")
    suspend fun latest(): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("DELETE FROM books")
    suspend fun clearAll()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE bookId = :bookId ORDER BY lastMessageAt DESC")
    fun observeByBook(bookId: Long): Flow<List<SessionEntity>>

    @Query(
        "SELECT s.*, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.sessionId = s.id) AS messageCount, " +
            "(SELECT m.content FROM messages m WHERE m.sessionId = s.id AND m.role = 'user' ORDER BY m.id LIMIT 1) AS firstQuestion " +
            "FROM sessions s WHERE s.bookId = :bookId ORDER BY s.lastMessageAt DESC"
    )
    fun observeSummaries(bookId: Long): Flow<List<SessionSummary>>

    @Query("SELECT * FROM sessions WHERE bookId = :bookId ORDER BY lastMessageAt DESC LIMIT 1")
    suspend fun latestByBook(bookId: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE bookId = :bookId ORDER BY lastMessageAt DESC LIMIT 1")
    suspend fun latest(bookId: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE sessions SET lastMessageAt = :time, title = COALESCE(title, :title) WHERE id = :id")
    suspend fun touch(id: Long, time: Long, title: String?)

    @Query("UPDATE sessions SET bookId = :newBookId WHERE bookId = :oldBookId")
    suspend fun reassignBook(oldBookId: Long, newBookId: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeBySession(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun bySession(sessionId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun lastMessage(sessionId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'user' AND pageText IS NOT NULL ORDER BY id DESC LIMIT 1")
    suspend fun lastUserMessageWithPage(sessionId: Long): MessageEntity?

    @Query("SELECT screenshotPath FROM messages WHERE sessionId = :sessionId AND screenshotPath IS NOT NULL")
    suspend fun screenshotPathsForSession(sessionId: Long): List<String>

    @Query(
        "SELECT m.screenshotPath FROM messages m " +
            "INNER JOIN sessions s ON m.sessionId = s.id " +
            "WHERE s.bookId = :bookId AND m.screenshotPath IS NOT NULL"
    )
    suspend fun screenshotPathsForBook(bookId: Long): List<String>

    @Query("SELECT screenshotPath FROM messages WHERE screenshotPath IS NOT NULL")
    suspend fun allScreenshotPaths(): List<String>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content, interrupted = :interrupted WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, interrupted: Boolean)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
