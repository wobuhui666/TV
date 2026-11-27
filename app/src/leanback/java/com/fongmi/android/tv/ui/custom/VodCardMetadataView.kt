package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
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

    fun bind(year: String, remark: String, site: String) {
        this.year = year
        this.remark = remark
        this.site = site
        if (visibility == VISIBLE) render()
    }

    fun show(focused: Boolean) {
        visibility = if (focused) VISIBLE else INVISIBLE
        if (focused) render()
    }

    private fun render() {
        with(labels) {
            year.text = this@VodCardMetadataView.year
            remark.text = this@VodCardMetadataView.remark
            site.text = this@VodCardMetadataView.site
            year.visibility = if (year.text.isNotEmpty()) VISIBLE else GONE
            remark.visibility = if (remark.text.isNotEmpty()) VISIBLE else GONE
            site.visibility = if (site.text.isNotEmpty()) VISIBLE else GONE
            yearSeparator.visibility = if (year.visibility == VISIBLE && (remark.visibility == VISIBLE || site.visibility == VISIBLE)) VISIBLE else GONE
            sourceSeparator.visibility = if (remark.visibility == VISIBLE && site.visibility == VISIBLE) VISIBLE else GONE
        }
    }
}
