package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.JetStreamBorders
import com.fongmi.android.tv.ui.theme.JetStreamShapes
import com.fongmi.android.tv.ui.theme.JetStreamTheme

class JetStreamPageHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var title by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var eyebrow by mutableStateOf("")

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.JetStreamPageHeaderView, defStyleAttr, 0)
        eyebrow = typedArray.getString(R.styleable.JetStreamPageHeaderView_jetStreamEyebrow).orEmpty()
        title = typedArray.getString(R.styleable.JetStreamPageHeaderView_jetStreamTitle).orEmpty()
        subtitle = typedArray.getString(R.styleable.JetStreamPageHeaderView_jetStreamSubtitle).orEmpty()
        typedArray.recycle()
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun setText(text: CharSequence?) {
        title = text?.toString().orEmpty()
    }

    fun setTitle(text: CharSequence?) {
        title = text?.toString().orEmpty()
    }

    fun setSubtitle(text: CharSequence?) {
        subtitle = text?.toString().orEmpty()
    }

    fun setEyebrow(text: CharSequence?) {
        eyebrow = text?.toString().orEmpty()
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                ) {
                    if (eyebrow.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .clip(JetStreamShapes.Button)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = eyebrow.uppercase(),
                                modifier = Modifier.padding(start = 7.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.0.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        text = title.ifBlank { context.getString(R.string.home_setting) },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

}
