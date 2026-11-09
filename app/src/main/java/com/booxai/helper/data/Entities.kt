package com.booxai.helper.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [Index(value = ["normalizedTitle"], unique = true)]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val normalizedTitle: String,
    val author: String? = null,
    val createdAt: Long,
    val lastOpenedAt: Long,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startedAt: Long,
    val lastMessageAt: Long,
    val title: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val pageText: String? = null,
    val bookTitle: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val createdAt: Long,
    val interrupted: Boolean = false,
)

data class BookSummary(
    @Embedded val book: BookEntity,
    val sessionCount: Int,
    val lastMessageAt: Long?,
)

data class SessionSummary(
    @Embedded val session: SessionEntity,
    val messageCount: Int,
    val firstQuestion: String?,
)
