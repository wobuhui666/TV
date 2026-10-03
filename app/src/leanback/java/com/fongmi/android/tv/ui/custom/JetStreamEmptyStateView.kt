package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.JetStreamTheme

class JetStreamEmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var message by mutableStateOf(context.getString(R.string.tv_empty_content))

    init {
        val typedArray = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text), defStyleAttr, 0)
        message = typedArray.getText(0)?.toString()?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tv_empty_content)
        typedArray.recycle()
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun setText(text: CharSequence?) {
        message = text?.toString()?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tv_empty_content)
    }

    fun setText(resId: Int) {
        message = if (resId == 0) context.getString(R.string.tv_empty_content) else context.getString(resId)
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.msr_movie),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = context.getString(R.string.tv_empty_back_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
