package lv.bolwarra.wetter.widget

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import lv.bolwarra.wetter.ui.theme.WetterColors

/** The card every widget is drawn on, and the arithmetic shared by all of them. */
internal object WidgetPlate {

    /**
     * An opaque raised card with a faint sheen across its top third.
     *
     * Opaque because the palette's contrast is solved against a known ground; a
     * translucent widget would take its legibility from the wallpaper.
     */
    fun drawPlate(canvas: Canvas, plate: RectF, radius: Float, colors: WetterColors) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.surfaceRaised.toArgb()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(plate, radius, radius, paint)

        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                plate.top,
                0f,
                plate.top + plate.height() * SHEEN_DEPTH,
                withAlpha(colors.surfaceHighlight.toArgb(), SHEEN_ALPHA),
                withAlpha(colors.surfaceHighlight.toArgb(), 0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(plate, radius, radius, sheen)
    }

    /**
     * A thin rim of light around the plate, brightest on the side the wind
     * blows from and brighter the stronger it blows.
     *
     * Widgets cannot animate, so the wind is shown by where the light stands
     * rather than by movement. With no wind reading the rim stays even.
     */
    fun drawRim(
        canvas: Canvas,
        plate: RectF,
        radius: Float,
        scale: Float,
        colors: WetterColors,
        windSpeed: Double?,
        windFrom: Int?,
    ) {
        val width = RIM_DP * scale
        val band = RectF(plate).apply { inset(width / 2f, width / 2f) }
        if (band.width() <= 0f || band.height() <= 0f) return

        val rim = colors.surfaceHighlight.toArgb() and 0x00FFFFFF
        val base = withAlpha(rim, RIM_BASE_ALPHA)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            color = base
        }

        if (windSpeed != null && windFrom != null) {
            // Square root, so ordinary breezes are visible on a scale that tops
            // out at gale force.
            val strength = sqrt((windSpeed / GALE_MS).coerceIn(0.0, 1.0)).toFloat()
            val lit = withAlpha(rim, RIM_BASE_ALPHA + (RIM_PEAK_ALPHA - RIM_BASE_ALPHA) * strength)

            // Sweep angles start at three o'clock, bearings at twelve.
            paint.shader = SweepGradient(
                band.centerX(),
                band.centerY(),
                intArrayOf(lit, base, base, lit),
                floatArrayOf(0f, TAIL_START, TAIL_END, 1f),
            ).apply {
                setLocalMatrix(
                    Matrix().apply {
                        postRotate(windFrom - QUARTER_TURN, band.centerX(), band.centerY())
                    },
                )
            }
        }
        canvas.drawRoundRect(band, radius - width / 2f, radius - width / 2f, paint)
    }

    /**
     * Pixels per dp for a picture of this size: the screen's own density,
     * reduced only as far as needed to keep the bitmap within [maxBytes].
     */
    fun renderScale(widthDp: Float, heightDp: Float, density: Float, maxBytes: Int): Float {
        val area = max(1f, widthDp * heightDp)
        return min(density, sqrt(maxBytes / BYTES_PER_PIXEL / area))
    }

    fun mix(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * amount).toInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

    /**
     * What a picture may weigh, tried in order until the launcher takes one.
     *
     * First half of the widget service's own allowance, which AOSP sets at six
     * bytes per screen pixel: a bitmap travels to the launcher in shared memory,
     * not inside the Binder transaction, so that allowance is the real limit and
     * the picture can be drawn at the screen's full density. Then sizes that fit
     * inside a transaction outright, for any host that refuses the first.
     */
    fun budgets(metrics: DisplayMetrics): List<Int> = listOf(
        metrics.widthPixels * metrics.heightPixels * SERVICE_BYTES_PER_PIXEL / 2,
        TRANSACTION_BYTES,
        TRANSACTION_BYTES / 4,
    )

    private const val SERVICE_BYTES_PER_PIXEL = 6
    private const val TRANSACTION_BYTES = 768 * 1024

    private const val BYTES_PER_PIXEL = 4f

    private const val RIM_DP = 1.5f
    private const val RIM_BASE_ALPHA = 0.22f
    private const val RIM_PEAK_ALPHA = 0.85f

    private const val SHEEN_ALPHA = 0.055f
    private const val SHEEN_DEPTH = 0.38f

    private const val TAIL_START = 0.3f
    private const val TAIL_END = 0.7f
    private const val QUARTER_TURN = 90f

    /** Gale force, as in the hazard rules: the rim is at its brightest here. */
    private const val GALE_MS = 17.2
}
