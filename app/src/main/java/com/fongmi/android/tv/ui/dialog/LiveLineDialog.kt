package com.fongmi.android.tv.ui.dialog

import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.fongmi.android.tv.R
import com.fongmi.android.tv.databinding.AdapterChapterBinding
import com.fongmi.android.tv.databinding.DialogChapterBinding
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration
import java.util.function.IntConsumer

/** Uses the TV side sheet or mobile bottom sheet supplied by the source set. */
class LiveLineDialog : BasePlaybackSelectionDialog() {
    private lateinit var binding: DialogChapterBinding
    private var labels: List<String> = emptyList()
    private var selectedIndex = 0
    private var selection: IntConsumer? = null
    private var closed: Runnable? = null

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): ViewBinding {
        binding = DialogChapterBinding.inflate(inflater, container, false)
        return binding
    }

    override fun initView() {
        binding.title.setText(R.string.dialog_select_live_line)
        binding.recycler.itemAnimator = null
        binding.recycler.setHasFixedSize(true)
        binding.recycler.addItemDecoration(SpaceItemDecoration(1, 12))
        binding.recycler.adapter = object : RecyclerView.Adapter<LineHolder>() {
            override fun getItemCount() = labels.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LineHolder(
                AdapterChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

            override fun onBindViewHolder(holder: LineHolder, position: Int) {
                holder.binding.text.text = labels[position]
                holder.binding.text.isSelected = position == selectedIndex
                holder.itemView.setOnClickListener {
                    val index = holder.bindingAdapterPosition
                    if (index !in labels.indices) return@setOnClickListener
                    val onSelect = selection
                    dismiss()
                    onSelect?.accept(index)
                }
            }
        }
        DialogFocus.requestRecyclerFocus(binding.recycler, selectedIndex, labels.size)
    }

    override fun onStart() {
        super.onStart()
        // Restored fragments cannot retain a callback to the original channel snapshot.
        if (selection == null || labels.isEmpty()) dismissAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        val onClosed = closed
        closed = null
        selection = null
        onClosed?.run()
    }

    private class LineHolder(val binding: AdapterChapterBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TAG = "live-lines"

        @JvmStatic
        fun show(activity: FragmentActivity, labels: List<String>, selectedIndex: Int, selection: IntConsumer, closed: Runnable): Boolean {
            val manager = activity.supportFragmentManager
            if (labels.size <= 1 || manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return false
            LiveLineDialog().apply {
                this.labels = labels.toList()
                this.selectedIndex = selectedIndex.coerceIn(labels.indices)
                this.selection = selection
                this.closed = closed
            }.showNow(manager, TAG)
            return true
        }

        @JvmStatic
        fun dismiss(activity: FragmentActivity) {
            (activity.supportFragmentManager.findFragmentByTag(TAG) as? LiveLineDialog)?.dismissAllowingStateLoss()
        }
    }
}
