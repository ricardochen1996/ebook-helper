package com.booxai.helper.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.booxai.helper.data.BookEntity
import com.booxai.helper.data.BookSummary
import com.booxai.helper.databinding.ItemBookBinding
import com.booxai.helper.util.TimeFormat

class BookAdapter(
    private val onClick: (BookEntity) -> Unit,
    private val onDelete: (BookEntity) -> Unit,
) : ListAdapter<BookSummary, BookAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.binding.tvBookTitle.text = item.book.title
        val meta = buildString {
            append(item.sessionCount)
            append(" 个会话")
            item.lastMessageAt?.let {
                append(" · 最后 ")
                append(TimeFormat.dateTime(it))
            }
        }
        holder.binding.tvBookMeta.text = meta
        holder.binding.root.setOnClickListener { onClick(item.book) }
        holder.binding.btnDeleteBook.setOnClickListener { onDelete(item.book) }
    }

    class Holder(val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BookSummary>() {
            override fun areItemsTheSame(oldItem: BookSummary, newItem: BookSummary): Boolean =
                oldItem.book.id == newItem.book.id

            override fun areContentsTheSame(oldItem: BookSummary, newItem: BookSummary): Boolean =
                oldItem == newItem
        }
    }
}
