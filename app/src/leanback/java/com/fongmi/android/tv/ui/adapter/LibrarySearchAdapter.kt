package com.fongmi.android.tv.ui.adapter

import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.databinding.AdapterRexPosterBinding
import com.fongmi.android.tv.source.LibraryIdentity
import com.fongmi.android.tv.source.LibrarySearchIndex
import com.fongmi.android.tv.ui.custom.JetStreamAnimator
import com.fongmi.android.tv.ui.custom.JetStreamNativeSectionHeaderView
import com.fongmi.android.tv.ui.custom.LibraryViews
import com.fongmi.android.tv.utils.ImgUtil

/** Search recycles artwork and text; arriving sources never inflate the entire result set. */
class LibrarySearchAdapter(private val width: Int, private val open: OnOpenListener) :
    ListAdapter<LibrarySearchAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    fun interface OnOpenListener { fun open(item: Vod) }

    data class Row(val key: String, val item: Vod?, val resources: List<Vod> = emptyList(),
                   val source: Boolean = false) {
        val header: Boolean get() = item == null
        val display = item?.let { listOf(it.name, it.year, it.pic, it.siteName, it.remarks) }
        val resourceKeys = resources.map(LibraryIdentity::sourceKey)
    }

    var createdCards = 0
        private set

    fun rows(snapshot: LibrarySearchIndex.Snapshot): List<Row> = buildList {
        snapshot.works().forEach { add(Row("work:" + it.item().id, it.item(), it.resources())) }
        if (snapshot.supplements().isNotEmpty()) {
            add(Row("supplements", null))
            snapshot.supplements().forEach { add(Row("source:" + LibraryIdentity.sourceKey(it), it, source = true)) }
        }
    }

    fun submitRows(rows: List<Row>, recycler: RecyclerView, complete: Runnable) {
        val focused = recycler.focusedChild?.let(recycler::getChildViewHolder) as? CardHolder
        // Preserve a selected source when later metadata attaches it to a work.
        val retained = focused?.row?.takeIf { it.source && rows.none { row -> row.key == it.key } }
        val next = if (retained == null) rows else rows.toMutableList().apply {
            var header = indexOfFirst { it.header }
            if (header < 0) { add(Row("supplements", null)); header = lastIndex }
            val oldPosition = currentList.indexOfFirst { it.key == retained.key }
            val oldHeader = currentList.indexOfFirst { it.header }
            add((header + (oldPosition - oldHeader).coerceAtLeast(1)).coerceAtMost(size), retained)
        }
        submitList(next, complete)
    }

    fun fullSpan(position: Int): Boolean = currentList.getOrNull(position)?.header == true

    fun firstCardPosition(): Int = currentList.indexOfFirst { !it.header }

    fun firstSourcePosition(): Int = currentList.indexOfFirst { it.source }

    fun keyAt(position: Int): String? = currentList.getOrNull(position)?.key

    fun positionOf(key: String): Int = currentList.indexOfFirst { it.key == key }

    override fun getItemViewType(position: Int): Int = if (getItem(position).header) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 1) return HeaderHolder(JetStreamNativeSectionHeaderView(parent.context).apply {
            setText(R.string.library_source_supplement)
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
        val binding = AdapterRexPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.poster.layoutParams = binding.poster.layoutParams.apply {
            height = LibraryViews.dp(this@LibrarySearchAdapter.width) * 3 / 2
        }
        binding.root.layoutParams = RecyclerView.LayoutParams(LibraryViews.dp(width), -2).apply {
            bottomMargin = LibraryViews.dp(18)
        }
        createdCards++
        return CardHolder(binding).also { holder ->
            binding.root.setOnClickListener {
                val row = holder.row ?: return@setOnClickListener
                val item = row.item ?: return@setOnClickListener
                if (!row.source) item.setSourceCandidates(ArrayList(row.resources))
                open.open(item)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is CardHolder) return
        val row = getItem(position)
        val previous = holder.row
        holder.row = row
        val item = row.item ?: return
        val metadata = if (row.source) listOf(item.siteName, item.remarks).filter(String::isNotBlank).joinToString(" · ")
            else listOf(item.year, resourceStatus(holder, row)).filter(String::isNotBlank).joinToString(" · ")
        if (holder.binding.name.text.toString() != item.name) holder.binding.name.text = item.name
        if (holder.binding.meta.text.toString() != metadata) holder.binding.meta.text = metadata
        holder.binding.meta.visibility = if (metadata.isEmpty()) View.INVISIBLE else View.VISIBLE
        holder.itemView.contentDescription = listOf(item.name, metadata).filter(String::isNotBlank).joinToString(" · ")
        if (previous?.key != row.key || previous?.display?.get(2) != row.display?.get(2)) {
            ImgUtil.loadArtwork(item.name, item.pic, holder.binding.image, null)
        }
    }

    private fun resourceStatus(holder: CardHolder, row: Row): String = holder.itemView.context.run {
        if (row.resources.isNotEmpty()) getString(R.string.library_search_resource_count, row.resources.size)
        else ""
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is CardHolder) {
            ImgUtil.clear(holder.binding.image)
            JetStreamAnimator.reset(holder.itemView)
            holder.row = null
        }
        super.onViewRecycled(holder)
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)
    private class CardHolder(val binding: AdapterRexPosterBinding) : RecyclerView.ViewHolder(binding.root) {
        var row: Row? = null
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(old: Row, new: Row) = old.key == new.key
            // These immutable List<String> snapshots have value equality; no Vod identity comparison.
            @android.annotation.SuppressLint("DiffUtilEquals")
            override fun areContentsTheSame(old: Row, new: Row) = old.display == new.display &&
                old.resourceKeys == new.resourceKeys && old.source == new.source
        }
    }
}
