package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.widget.LinearLayoutCompat
import com.fongmi.android.tv.databinding.ViewVodCardMetadataBinding

/** Reserve the caption height, and create its labels only for cards the viewer focuses. */
class VodCardMetadataView(context: Context, attrs: AttributeSet?) : LinearLayoutCompat(context, attrs) {
    private val labels by lazy(LazyThreadSafetyMode.NONE) {
        ViewVodCardMetadataBinding.inflate(LayoutInflater.from(context), this)
    }
    private var year = ""
    private var remark = ""
    private var site = ""

    fun bind(year: String, remark: String, site: String?) {
        this.year = year
        this.remark = remark
        this.site = site.orEmpty()
        show((parent as? View)?.hasFocus() == true)
    }

    fun show(focused: Boolean) {
        visibility = if (focused) VISIBLE else INVISIBLE
        if (focused) render()
    }

    private fun render() {
        labels.year.text = year
        labels.remark.text = remark
        labels.site.text = site
        labels.year.visibility = if (year.isNotEmpty()) VISIBLE else GONE
        labels.remark.visibility = if (remark.isNotEmpty()) VISIBLE else GONE
        labels.site.visibility = if (site.isNotEmpty()) VISIBLE else GONE
        labels.yearSeparator.visibility = if (year.isNotEmpty() && (remark.isNotEmpty() || site.isNotEmpty())) VISIBLE else GONE
        labels.sourceSeparator.visibility = if (remark.isNotEmpty() && site.isNotEmpty()) VISIBLE else GONE
    }
}
