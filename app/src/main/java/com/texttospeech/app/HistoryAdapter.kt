package com.texttospeech.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.texttospeech.app.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onClick: (HistoryItem) -> Unit,
    private val onDelete: (HistoryItem) -> Unit
) : ListAdapter<HistoryItem, HistoryAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: HistoryItem) {
            b.tvFileName.text = item.fileName
            b.tvPreview.text  = item.preview
            b.tvMeta.text     = "${formatCount(item.charCount)} ký tự  •  ${formatDate(item.timestamp)}"
            b.root.setOnClickListener    { onClick(item) }
            b.btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val FMT = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        private fun formatDate(ts: Long) = FMT.format(Date(ts))
        private fun formatCount(n: Int) = "%,d".format(n)

        private val DIFF = object : DiffUtil.ItemCallback<HistoryItem>() {
            override fun areItemsTheSame(a: HistoryItem, b: HistoryItem) = a.id == b.id
            override fun areContentsTheSame(a: HistoryItem, b: HistoryItem) = a == b
        }
    }
}
