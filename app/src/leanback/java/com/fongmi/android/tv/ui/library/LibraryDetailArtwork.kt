package com.fongmi.android.tv.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.fongmi.android.tv.ui.rex.RexArtworkColors
import com.fongmi.android.tv.ui.custom.LibraryMotion
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.ui.theme.RexTheme
import com.fongmi.android.tv.utils.GlideWarmup
import com.fongmi.android.tv.utils.ImgUtil
import com.fongmi.android.tv.utils.TmdbLogoTransformation
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
data class DetailArtworkColors(val background: Color, val accent: Color)

/** Keep artwork temperature within the shared theme; focus remains the App's neutral highlight. */
fun detailArtworkColors(dominantColor: Int?, isLight: Boolean): DetailArtworkColors {
    val theme = JetStreamPalette.colorScheme()
    if (dominantColor == null) return DetailArtworkColors(theme.background, theme.tertiary)
    val seed = FloatArray(3)
    ColorUtils.colorToM3HCT(dominantColor, seed)
    val background = ColorUtils.M3HCTToColor(seed[0], min(seed[1], 20f), if (isLight) 98f else 7f)
    val accent = ColorUtils.M3HCTToColor(seed[0], min(seed[1], 32f), if (isLight) 42f else 80f)
    return DetailArtworkColors(
        background = Color(ColorUtils.blendARGB(theme.background.toArgb(), background, .65f)),
        accent = Color(ColorUtils.blendARGB(theme.tertiary.toArgb(), accent, .55f))
    )
}

/**
 * Glide owns the displayed bitmap and its lifecycle. A recycled lazy-row slot cancels its old
 * request, and decoding is bounded by the slot's measured size rather than the source artwork.
 */
@Composable
fun DetailArtwork(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = Color.Transparent,
    onLoadResult: ((Boolean) -> Unit)? = null,
    onDominantColor: ((Int) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    alphaRim: Boolean = false
) {
    AndroidView(
        factory = { DetailArtworkImageView(it) },
        modifier = modifier.background(placeholderColor),
        onReset = { it.resetArtwork() },
        onRelease = { it.resetArtwork() },
        update = { view ->
            view.contentDescription = contentDescription
            view.importantForAccessibility = if (contentDescription == null)
                ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO else ImageView.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            if (view.imageTintList != null) view.imageTintList = null
            view.bindArtwork(url, contentScale, alignment, onLoadResult, onDominantColor, alphaRim)
        }
    )
}

/** One bounded backdrop; scrolling only changes the render layer and a cached scrim's opacity. */
@Composable
fun HeroBackdropLayer(
    backdropUrl: String,
    backgroundColor: Color,
    scrollFraction: () -> Float,
    modifier: Modifier = Modifier,
    onDominantColor: ((Int) -> Unit)? = null
) {
    val sideScrim = if (RexTheme.isLight) backgroundColor else Color.Black
    val motionEnabled = LibraryMotion.enabled(LocalView.current)
    Box(modifier.background(backgroundColor)) {
        DetailArtwork(
            url = backdropUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // Read list state here, avoiding recomposition of the page on every scroll frame.
                val progress = if (motionEnabled) scrollFraction().coerceIn(0f, 1f) else 0f
                scaleX = 1f + progress * .025f
                scaleY = scaleX
                translationY = -size.height * progress * .025f
                clip = true
            },
            onDominantColor = onDominantColor
        )
        Box(Modifier.fillMaxSize().drawWithCache {
            // Brushes are rebuilt only for a size/theme change, never for the scroll offset.
            val vertical = Brush.verticalGradient(
                0f to Color.Transparent,
                .25f to backgroundColor.copy(alpha = .08f),
                .55f to backgroundColor.copy(alpha = .64f),
                1f to backgroundColor
            )
            val horizontal = Brush.horizontalGradient(
                0f to sideScrim.copy(alpha = .62f),
                .40f to sideScrim.copy(alpha = .40f),
                .78f to Color.Transparent,
                1f to Color.Transparent
            )
            onDrawBehind {
                drawRect(vertical)
                drawRect(horizontal)
                drawRect(backgroundColor, alpha = scrollFraction().coerceIn(0f, 1f) * .88f)
            }
        })
    }
}

private class DetailArtworkImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private data class ArtworkRequest(
        val url: String,
        val width: Int,
        val height: Int,
        val scaleType: ImageView.ScaleType,
        val sampleColor: Boolean,
        val alphaRim: Boolean
    )

    private var artworkUrl = ""
    private var requestedScale = ImageView.ScaleType.CENTER_CROP
    private var requestedAlphaRim = false
    private var request: ArtworkRequest? = null
    private var loadResult: ((Boolean) -> Unit)? = null
    private var dominantColor: ((Int) -> Unit)? = null

    init {
        isFocusable = false
        isClickable = false
    }

    fun bindArtwork(
        url: String,
        scale: ContentScale,
        alignment: Alignment,
        onLoadResult: ((Boolean) -> Unit)?,
        onDominantColor: ((Int) -> Unit)?,
        alphaRim: Boolean
    ) {
        artworkUrl = url
        loadResult = onLoadResult
        dominantColor = onDominantColor
        requestedAlphaRim = alphaRim
        requestedScale = when (scale) {
            ContentScale.Fit -> when (alignment) {
                Alignment.CenterStart -> ImageView.ScaleType.FIT_START
                Alignment.CenterEnd -> ImageView.ScaleType.FIT_END
                else -> ImageView.ScaleType.FIT_CENTER
            }
            ContentScale.Inside -> ImageView.ScaleType.CENTER_INSIDE
            ContentScale.FillBounds -> ImageView.ScaleType.FIT_XY
            ContentScale.None -> ImageView.ScaleType.CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }
        scaleType = requestedScale
        loadArtwork()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) loadArtwork()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadArtwork()
    }

    override fun onDetachedFromWindow() {
        clearRequest()
        super.onDetachedFromWindow()
    }

    fun resetArtwork() {
        clearRequest()
        artworkUrl = ""
        loadResult = null
        dominantColor = null
    }

    private fun clearRequest() {
        request = null
        ImgUtil.clear(this)
        setImageDrawable(null)
    }

    private fun loadArtwork() {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        if (artworkUrl.isBlank()) {
            clearRequest()
            loadResult?.invoke(false)
            return
        }
        // 4K TVs still decode one 1080p backdrop; lazy-card thumbnails are much smaller.
        val ratio = min(1f, min(1920f / width, 1080f / height))
        val next = ArtworkRequest(artworkUrl,
            (width * ratio).roundToInt().coerceAtLeast(1),
            (height * ratio).roundToInt().coerceAtLeast(1),
            requestedScale, dominantColor != null, requestedAlphaRim)
        if (request == next) return
        GlideWarmup.cancel(this)
        if (GlideWarmup.defer(this, { loadArtwork() }, { loadResult?.invoke(false) })) return
        request = next
        try {
            val builder = Glide.with(this).asBitmap().load(ImgUtil.getUrl(next.url))
                .override(next.width, next.height)
                .downsample(DownsampleStrategy.AT_MOST)
                .dontTransform()
                .dontAnimate()
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean
                    ): Boolean {
                        if (request == next) loadResult?.invoke(false)
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap, model: Any, target: Target<Bitmap>?,
                        dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean {
                        if (request != next) return true
                        loadResult?.invoke(true)
                        if (next.sampleColor) {
                            // The existing cache copies only 64×36 pixels and samples off the UI thread.
                            RexArtworkColors.from(next.url, resource) { color ->
                                if (request == next) dominantColor?.invoke(color)
                            }
                        }
                        return false
                    }
                })
            if (next.alphaRim) {
                // Preserve logo colors; cached alpha-shaped contrast also handles opaque artwork.
                builder.disallowHardwareConfig().transform(TmdbLogoTransformation(resources.displayMetrics.density))
            } else if (next.sampleColor) builder.disallowHardwareConfig()
            builder.into(this)
        } catch (_: IllegalArgumentException) {
            loadResult?.invoke(false)
        } catch (_: IllegalStateException) {
            loadResult?.invoke(false)
        }
    }
}
