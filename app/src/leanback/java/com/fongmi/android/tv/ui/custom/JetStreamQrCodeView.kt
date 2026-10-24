package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.theme.JetStreamShapes
import com.fongmi.android.tv.ui.rex.RexTheme
import com.fongmi.android.tv.ui.theme.JetStreamSpacing
import com.fongmi.android.tv.ui.theme.JetStreamTheme

class JetStreamQrCodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var qrBitmap by mutableStateOf<Bitmap?>(null)

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun setImageBitmap(bitmap: Bitmap?) {
        qrBitmap = bitmap
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            val surfaceColor = MaterialTheme.colorScheme.surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(JetStreamShapes.Large)
                    .background(surfaceColor)
                    .padding(JetStreamSpacing.Small),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(JetStreamShapes.Medium)
                        .padding(JetStreamSpacing.Small),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = qrBitmap
                    if (bitmap == null) {
                        Text(
                            text = "QR",
                            color = if (RexTheme.isLight()) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}
