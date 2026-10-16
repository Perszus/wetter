package lv.bolwarra.wetter.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A line of text the glass keeps a soft margin around, positioned in the glass's own coordinates. */
internal class GlassText(val layout: TextLayoutResult, val topLeft: Offset)

/**
 * What the weather leaves on the dial's glass: one picture per state (chosen by
 * GlassMark), a greyscale matte drawn as light, its brightness the opacity of
 * white on either plate.
 *
 * It fills the face and fades only at the rim. Around every glyph of [clear] the
 * glass thins in a soft margin shaped like the glyph itself, so the reading
 * stands clear of whatever the picture is without a hole being cut for it.
 *
 * A new picture fades in rather than cutting.
 */
@Composable
internal fun GlassMarks(
    name: String?,
    ink: Color,
    modifier: Modifier = Modifier,
    clear: List<GlassText> = emptyList(),
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = cached(name), name) {
        value = name?.let { withContext(Dispatchers.IO) { load(context, it) } }
    }
    val shown by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(FADE_MS),
        label = "glass marks",
    )
    val picture = image ?: return

    Canvas(
        modifier.graphicsLayer {
            // Offscreen, so the falloff and the margins below act on the marks alone.
            compositingStrategy = CompositingStrategy.Offscreen
            alpha = shown
        },
    ) {
        val side = size.minDimension.toInt()
        drawImage(
            image = picture,
            dstOffset = IntOffset(
                ((size.width - side) / 2).toInt(),
                ((size.height - side) / 2).toInt(),
            ),
            dstSize = IntSize(side, side),
            alpha = GlassLook.PEAK_OPACITY,
            colorFilter = inkFrom(ink),
        )
        drawRect(
            brush = Brush.radialGradient(
                colorStops = FALLOFF,
                center = Offset(size.width / 2, size.height / 2),
                radius = size.minDimension / 2,
            ),
            blendMode = BlendMode.DstIn,
        )
        val thinned = Color.Black.copy(alpha = GlassLook.HALO_STRENGTH)
        clear.forEach { text ->
            // Each glyph thickened by its margin, then blurred: solid against the
            // glyph, fading out beyond it. Blurring the bare strokes would spread a
            // light figure's thin lines too thin to hold anything back.
            val core = GlassLook.haloCore(text.layout.layoutInput.style.fontSize.toPx(), density)
            drawText(
                textLayoutResult = text.layout,
                color = thinned,
                topLeft = text.topLeft,
                shadow = Shadow(thinned, Offset.Zero, core * GlassLook.HALO_SOFTNESS),
                drawStyle = Stroke(width = core * 2f, join = StrokeJoin.Round),
                blendMode = BlendMode.DstOut,
            )
        }
    }
}

/** The matte's brightness as the ink's opacity; its colour discarded. */
private fun inkFrom(ink: Color): ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, ink.red * 255f,
            0f, 0f, 0f, 0f, ink.green * 255f,
            0f, 0f, 0f, 0f, ink.blue * 255f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
        ),
    ),
)

/** The falloff as Compose stops. */
private val FALLOFF: Array<Pair<Float, Color>> =
    GlassLook.falloff().map { (at, alpha) -> at to Color.Black.copy(alpha = alpha) }.toTypedArray()

private const val FADE_MS = 700

/**
 * How the glass is drawn, wherever it is drawn: the dial in the app and the
 * widgets' pictures read these, so the two cannot drift apart.
 */
internal object GlassLook {

    const val PEAK_OPACITY = 0.3f

    /** Full across the face to this fraction of the radius... */
    const val FALLOFF_START = 0.86f

    /** ...and gone by the fired edge. */
    const val FALLOFF_END = 0.98f

    /** How much glass the margin round a glyph takes away, right against it. */
    const val HALO_STRENGTH = 0.9f

    /** How far the fade beyond the solid margin reaches, as a multiple of it. */
    const val HALO_SOFTNESS = 2f

    /**
     * The solid margin round a glyph, in pixels: a share of the type's size, so a
     * large figure keeps a proportionate margin, and never under a floor.
     */
    fun haloCore(textSizePx: Float, density: Float): Float =
        maxOf(textSizePx * HALO_SHARE, HALO_FLOOR_DP * density)

    private const val HALO_SHARE = 0.09f
    private const val HALO_FLOOR_DP = 2f

    /** A smoothstep from [FALLOFF_START] to [FALLOFF_END], as (fraction of the radius, opacity) stops. */
    fun falloff(): List<Pair<Float, Float>> {
        val steps = 8
        val ramp = (0..steps).map { i ->
            val t = i / steps.toFloat()
            val eased = t * t * (3f - 2f * t)
            (FALLOFF_START + (FALLOFF_END - FALLOFF_START) * t) to 1f - eased
        }
        return listOf(0f to 1f) + ramp + listOf(1f to 0f)
    }
}

/**
 * Decoded pictures, held so moving between pages or rotating the phone does not
 * decode the same matte again. The weather changes slowly; a handful is plenty.
 */
private val decoded = LruCache<String, ImageBitmap>(4)

private fun cached(name: String?): ImageBitmap? = name?.let { decoded.get(it) }

private fun load(context: Context, name: String): ImageBitmap? = decoded.get(name) ?: runCatching {
    context.assets.open("glass/$name.webp").use { BitmapFactory.decodeStream(it) }
        ?.asImageBitmap()
        ?.also { decoded.put(name, it) }
}.getOrNull()
