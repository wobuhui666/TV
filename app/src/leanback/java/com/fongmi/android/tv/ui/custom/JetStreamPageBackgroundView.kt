package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.fongmi.android.tv.ui.components.JetStreamRadialScrim
import com.fongmi.android.tv.ui.components.jetStreamVerticalScrimBrush
import com.fongmi.android.tv.ui.theme.JetStreamAmbient
import com.fongmi.android.tv.ui.theme.JetStreamTheme

class JetStreamPageBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            val colorScheme = MaterialTheme.colorScheme
            val backdrop = JetStreamAmbient.backdrop
            val glowColor by animateColorAsState(
                targetValue = (JetStreamAmbient.color?.let { Color(it) } ?: colorScheme.primary).copy(alpha = 0.22f),
                animationSpec = tween(durationMillis = 220),
                label = "ambientGlow"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        jetStreamVerticalScrimBrush(
                            startColor = colorScheme.background,
                            middleColor = colorScheme.surface,
                            endColor = colorScheme.background.copy(alpha = 0.92f)
                        )
                    )
            ) {
                Crossfade(
                    targetState = backdrop,
                    animationSpec = tween(durationMillis = 220),
                    label = "ambientBackdrop"
                ) { bitmap ->
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.26f,
                            filterQuality = FilterQuality.Low
                        )
                    }
                }
                JetStreamRadialScrim(
                    modifier = Modifier.fillMaxSize(),
                    centerColor = glowColor,
                    edgeColor = Color.Transparent,
                    centerFraction = Offset(0.12f, 0.05f),
                    radiusFraction = 0.62f
                )
                JetStreamRadialScrim(
                    modifier = Modifier.fillMaxSize(),
                    centerColor = colorScheme.tertiary.copy(alpha = 0.16f),
                    edgeColor = Color.Transparent,
                    centerFraction = Offset(0.86f, 0.72f),
                    radiusFraction = 0.66f
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            jetStreamVerticalScrimBrush(
                                startColor = colorScheme.background.copy(alpha = 0.04f),
                                middleColor = Color.Transparent,
                                endColor = colorScheme.background.copy(alpha = 0.30f)
                            )
                        )
                )
            }
        }
    }
}
