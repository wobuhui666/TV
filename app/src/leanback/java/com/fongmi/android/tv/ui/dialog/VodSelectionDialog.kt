package com.fongmi.android.tv.ui.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.fongmi.android.tv.databinding.AdapterChapterBinding
import com.fongmi.android.tv.databinding.DialogChapterBinding
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration
import java.util.function.IntConsumer

/** Virtualized choices for the current video's episodes, lines and picture qualities. */
class VodSelectionDialog : BasePlaybackSelectionDialog() {
    private lateinit var binding: DialogChapterBinding
    private var title = ""
    private var labels: List<String> = emptyList()
    private var selected = 0
    private var selection: IntConsumer? = null

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): ViewBinding {
        binding = DialogChapterBinding.inflate(inflater, container, false)
        return binding
    }

    override fun initView() {
        binding.title.setText(title)
        binding.recycler.itemAnimator = null
        binding.recycler.setHasFixedSize(true)
        binding.recycler.addItemDecoration(SpaceItemDecoration(1, 12))
        binding.recycler.adapter = object : RecyclerView.Adapter<ChoiceHolder>() {
            override fun getItemCount() = labels.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChoiceHolder(
                AdapterChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

            override fun onBindViewHolder(holder: ChoiceHolder, position: Int) {
                holder.binding.text.text = labels[position]
                holder.binding.text.isSelected = position == selected
                holder.itemView.setOnClickListener {
                    val index = holder.bindingAdapterPosition
                    if (index !in labels.indices) return@setOnClickListener
                    dismiss()
                    selection?.accept(index)
                }
            }
        }
        DialogFocus.requestRecyclerFocus(binding.recycler, selected, labels.size)
    }

    override fun onStart() {
        super.onStart()
        // A restored process no longer owns the source snapshot used by the callback.
        if (selection == null || labels.isEmpty()) dismissAllowingStateLoss()
    }

    private class ChoiceHolder(val binding: AdapterChapterBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        @JvmStatic
        fun show(activity: FragmentActivity, title: String, labels: List<String>, selected: Int, selection: IntConsumer) {
            val manager = activity.supportFragmentManager
            if (labels.isEmpty() || manager.isStateSaved || manager.findFragmentByTag("vod-selection") != null) return
            VodSelectionDialog().apply {
                this.title = title
                this.labels = labels.toList()
                this.selected = selected.coerceIn(labels.indices)
                this.selection = selection
            }.show(manager, "vod-selection")
        }
    }
}
