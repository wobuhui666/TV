package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.viewbinding.ViewBinding
import com.fongmi.android.tv.databinding.ActivityPushBinding
import com.fongmi.android.tv.server.Server
import com.fongmi.android.tv.ui.base.BaseActivity
import com.fongmi.android.tv.ui.custom.JetStreamPushView
import com.fongmi.android.tv.utils.QRCode
import com.fongmi.android.tv.utils.Sniffer
import com.fongmi.android.tv.utils.Util

class PushActivity : BaseActivity(), JetStreamPushView.Listener {

    private lateinit var binding: ActivityPushBinding

    companion object {
        @JvmStatic
        fun start(activity: Activity) {
            start(activity, 2)
        }

        @JvmStatic
        fun start(activity: Activity, tab: Int) {
            val intent = Intent(activity, PushActivity::class.java)
            intent.putExtra("tab", tab)
            activity.startActivity(intent)
        }
    }

    private val tab: Int
        get() = intent.getIntExtra("tab", 2)

    private val displayAddress: String
        get() = Server.get().address

    private val openAddress: String
        get() = Server.get().getAddress(tab)

    override fun getBinding(): ViewBinding {
        binding = ActivityPushBinding.inflate(layoutInflater)
        return binding
    }

    override fun initView(savedInstanceState: Bundle?) {
        binding.content.setAddress(displayAddress)
        binding.content.setQrBitmap(QRCode.getJetStreamBitmap(openAddress, 250, 4))
        binding.content.post { if (binding.content.isShown && binding.content.isEnabled) binding.content.requestFocus() }
    }

    override fun initEvent() {
        binding.content.setListener(this)
    }

    override fun onCopyAddress(): Boolean {
        return try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            manager.setPrimaryClip(ClipData.newPlainText("", displayAddress))
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onPushClipboard(): Boolean {
        val text = Util.getClipText()?.toString().orEmpty()
        if (text.isEmpty()) return false
        VideoActivity.start(this, Sniffer.getUrl(text))
        return true
    }

    override fun onOpenAddress() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(openAddress)))
        }
    }
}
