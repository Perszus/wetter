// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import lv.bolwarra.wetter.ui.components.GlassLook

/**
 * The dial's glass picture on a widget: the matte's brightness as white light
 * at the dial's opacity ([GlassLook]), thinned in a soft margin round every line
 * of text and every mark drawn over it.
 */
internal object WidgetGlass {

    /**
     * Draws the picture into [area], fading towards a disc's rim when [round],
     * otherwise towards the edges of [area]. The glass thins round each glyph of
     * [text], in its own shape, and in a round margin under each of [marks].
     */
    fun draw(
        canvas: Canvas,
        context: Context,
        name: String?,
        area: RectF,
        scale: Float,
        round: Boolean,
        text: List<WidgetInk.Line> = emptyList(),
        marks: List<RectF> = emptyList(),
        opacity: Float = GlassLook.PEAK_OPACITY,
        cornerRadius: Float = 0f,
    ) {
        if (name == null || area.width() <= 0f || area.height() <= 0f) return
        val matte = matte(context, name) ?: return

        val clip = canvas.save()
        if (!round) {
            canvas.clipPath(
                Path().apply {
                    addRoundRect(area, cornerRadius, cornerRadius, Path.Direction.CW)
                },
            )
        }
        val layer = canvas.saveLayer(area, null)

        // Cover rather than fit, so a non-square plate is filled.
        val side = max(area.width(), area.height())
        val target = RectF(
            area.centerX() - side / 2f,
            area.centerY() - side / 2f,
            area.centerX() + side / 2f,
            area.centerY() + side / 2f,
        )
        canvas.drawBitmap(
            matte,
            null,
            target,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (opacity * 255).roundToInt()
                colorFilter = LIGHT
            },
        )

        val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        if (round) {
            val stops = GlassLook.falloff()
            fade.shader = RadialGradient(
                area.centerX(),
                area.centerY(),
                min(area.width(), area.height()) / 2f,
                stops.map { (_, alpha) -> (alpha * 255).roundToInt() shl 24 }.toIntArray(),
                stops.map { (at, _) -> at }.toFloatArray(),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(area, fade)
        } else {
            val feather = EDGE_FEATHER_DP * scale
            fade.maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
            val inner = RectF(area).apply { inset(feather * 1.5f, feather * 1.5f) }
            val corner = max(feather, cornerRadius - feather * 1.5f)
            canvas.drawRoundRect(inner, corner, corner, fade)
        }

        val strength = (GlassLook.HALO_STRENGTH * 255).roundToInt()
        text.forEach { line ->
            // Each glyph thickened by its margin, then blurred: solid against the
            // glyph, fading out beyond it. Blurring the bare strokes would spread a
            // light figure's thin lines too thin to hold anything back.
            val core = GlassLook.haloCore(line.paint.textSize, scale)
            canvas.drawText(
                line.text,
                line.rect.left,
                line.baseline,
                Paint(line.paint).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    color = android.graphics.Color.BLACK
                    alpha = strength
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = core * 2f
                    strokeJoin = Paint.Join.ROUND
                    maskFilter =
                        BlurMaskFilter(core * GlassLook.HALO_SOFTNESS, BlurMaskFilter.Blur.NORMAL)
                },
            )
        }
        val halo = GlassLook.haloCore(0f, scale) * GlassLook.HALO_SOFTNESS
        marks.forEach { mark ->
            val reach = mark.width() / 2f + halo
            canvas.drawCircle(
                mark.centerX(),
                mark.centerY(),
                reach,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    shader = RadialGradient(
                        mark.centerX(),
                        mark.centerY(),
                        reach,
                        intArrayOf(strength shl 24, strength shl 24, 0),
                        floatArrayOf(0f, (mark.width() / 2f) / reach, 1f),
                        Shader.TileMode.CLAMP,
                    )
                },
            )
        }

        canvas.restoreToCount(layer)
        canvas.restoreToCount(clip)
    }

    /** Decoded mattes, kept for the life of the process. */
    private fun matte(context: Context, name: String): Bitmap? = decoded.get(name) ?: runCatching {
        context.assets.open("glass/$name.webp").use { BitmapFactory.decodeStream(it) }
            ?.also { decoded.put(name, it) }
    }.getOrNull()

    private val decoded = LruCache<String, Bitmap>(4)

    /** Luminance to the alpha of white. */
    private val LIGHT = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 0f, 255f,
                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            ),
        ),
    )

    private const val EDGE_FEATHER_DP = 14f
}
