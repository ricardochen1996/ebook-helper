package com.booxai.helper.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.booxai.helper.data.SessionSummary
import com.booxai.helper.databinding.ItemSessionBinding
import com.booxai.helper.util.TimeFormat

class SessionAdapter(
    private val onClick: (SessionSummary) -> Unit,
    private val onDelete: (SessionSummary) -> Unit,
) : ListAdapter<SessionSummary, SessionAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.binding.tvSessionTime.text = TimeFormat.dateTime(item.session.startedAt)
        holder.binding.tvSessionSnippet.text = item.firstQuestion?.takeIf { it.isNotBlank() }
            ?: item.session.title
            ?: "（无提问）"
        holder.binding.tvSessionCount.text = "${item.messageCount} 条消息"
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnDeleteSession.setOnClickListener { onDelete(item) }
    }

    class Holder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SessionSummary>() {
            override fun areItemsTheSame(oldItem: SessionSummary, newItem: SessionSummary): Boolean =
                oldItem.session.id == newItem.session.id

            override fun areContentsTheSame(oldItem: SessionSummary, newItem: SessionSummary): Boolean =
                oldItem == newItem
        }
    }
}
