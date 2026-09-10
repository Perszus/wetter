package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * One day's sky, as a mark rather than a picture.
 *
 * docs/design-principles.md rules out weather illustrations and oversized icons,
 * and that rules out the obvious answer — a set of drawn scenes with rays and
 * highlights, which is what most weather apps put here. What is wanted instead
 * is the same thing the rest of this app draws: an instrument mark, small,
 * quiet, at the weight of the type beside it.
 *
 * ### Drawn, not shipped
 *
 * Fourteen conditions is fourteen vector drawables to keep in step with the
 * palette, or one file that draws them from the theme's own colours. Drawn also
 * means they cannot fall out of step across themes: there is no light-mode asset
 * to forget to make a dark-mode twin of.
 *
 * ### Filled cloud, stroked weather
 *
 * The cloud is a union of filled shapes — a slab with three discs sitting on it
 * — because filled shapes merge without a seam. A stroked outline of the same
 * cloud has to be one continuous path through three circle intersections, and
 * every attempt at it either shows the joins or needs the intersection points
 * solved for at four sizes. What falls out of the cloud is stroked, so the
 * glyph has the same mix of weights as the rest of the app.
 *
 * ### One saturated hue, still
 *
 * The sky is drawn in ink and only what is *falling* takes the precipitation
 * colour. That is the palette rule, and here it does a second job: down a column
 * of seven days, the blue marks are the wet ones and nothing else competes for
 * the eye. A yellow sun would have made a clear week the loudest thing on the
 * page.
 */
@Composable
fun ConditionGlyph(
    condition: WeatherCondition,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = GLYPH,
    tint: Color = WetterTheme.colors.textSecondary,
    falling: Color = WetterTheme.colors.precipitation,
) {
    val described = contentDescription
        ?.let { Modifier.semantics { this.contentDescription = it } }
        ?: Modifier
    Canvas(modifier.size(size).then(described)) {
        drawCondition(condition, tint, falling)
    }
}

/**
 * The set, mapped.
 *
 * Grouped rather than one-to-one: sixteen conditions do not need sixteen
 * drawings, and two marks a reader cannot tell apart are worse than one mark
 * used twice. What must stay distinguishable is what changes a decision —
 * whether anything falls, whether it is frozen, whether it is violent — and the
 * freezing pair keep a mark of their own because ice underfoot is the one thing
 * on this list that a coat does not solve.
 */
private fun DrawScope.drawCondition(condition: WeatherCondition, ink: Color, wet: Color) {
    val s = size.minDimension
    // Everything below is written against a square, then centred in whatever box
    // it was given. Two glyphs in a column must sit on the same baseline however
    // wide their row is.
    translate(left = (size.width - s) / 2f, top = (size.height - s) / 2f) {
        when (condition) {
            WeatherCondition.CLEAR ->
                sun(Offset(s * 0.5f, s * 0.5f), s * 0.18f, ink)

            WeatherCondition.MAINLY_CLEAR -> {
                sun(Offset(s * 0.36f, s * 0.34f), s * 0.15f, ink)
                cloud(s * 0.58f, s * 0.72f, s * 0.52f, ink)
            }

            WeatherCondition.PARTLY_CLOUDY -> {
                sun(Offset(s * 0.32f, s * 0.30f), s * 0.14f, ink)
                cloud(s * 0.56f, s * 0.76f, s * 0.68f, ink)
            }

            WeatherCondition.OVERCAST ->
                cloud(s * 0.5f, s * 0.68f, s * 0.78f, ink)

            // Not a cloud with rain missing: fog is the one condition you are
            // standing inside, so it is drawn as bands across the whole glyph
            // with the sky only suggested above them.
            WeatherCondition.FOG -> {
                cloud(s * 0.5f, s * 0.48f, s * 0.66f, ink)
                bands(s, ink)
            }

            // Droplets, not short streaks. See [droplets]: this and RAIN were
            // the same drawing at two lengths, which is not a difference at the
            // size a week row gives a glyph.
            WeatherCondition.DRIZZLE -> {
                cloud(s * 0.5f, s * 0.56f, s * 0.72f, ink)
                droplets(s, at = spread(3), colour = wet)
            }

            WeatherCondition.RAIN -> {
                cloud(s * 0.5f, s * 0.56f, s * 0.72f, ink)
                drops(s, count = 3, length = s * 0.20f, colour = wet)
            }

            WeatherCondition.FREEZING_DRIZZLE, WeatherCondition.FREEZING_RAIN -> {
                cloud(s * 0.5f, s * 0.50f, s * 0.72f, ink)
                drops(s, count = 3, length = s * 0.16f, colour = wet)
                // The ground the rain freezes onto. Without it this is rain, and
                // the difference between the two is the whole warning.
                ground(s, ink)
            }

            WeatherCondition.SLEET -> {
                cloud(s * 0.5f, s * 0.56f, s * 0.72f, ink)
                drops(s, count = 1, length = s * 0.18f, colour = wet, at = floatArrayOf(0.5f))
                flakes(s, at = floatArrayOf(0.26f, 0.74f), colour = wet)
            }

            WeatherCondition.SNOW, WeatherCondition.SNOW_SHOWERS -> {
                cloud(s * 0.5f, s * 0.56f, s * 0.72f, ink)
                flakes(s, at = floatArrayOf(0.24f, 0.5f, 0.76f), colour = wet)
            }

            // Grains are the small dry pellets, so: the shape of snow at a
            // fraction of the size, which is what they are.
            WeatherCondition.SNOW_GRAINS -> {
                cloud(s * 0.5f, s * 0.56f, s * 0.72f, ink)
                grains(s, at = floatArrayOf(0.26f, 0.5f, 0.74f), colour = wet)
            }

            WeatherCondition.RAIN_SHOWERS -> {
                sun(Offset(s * 0.28f, s * 0.24f), s * 0.12f, ink)
                cloud(s * 0.56f, s * 0.60f, s * 0.62f, ink)
                drops(
                    s,
                    count = 2,
                    length = s * 0.16f,
                    colour = wet,
                    at = floatArrayOf(0.44f, 0.68f),
                )
            }

            WeatherCondition.THUNDERSTORM -> {
                cloud(s * 0.5f, s * 0.54f, s * 0.72f, ink)
                bolt(s, wet)
            }

            WeatherCondition.THUNDERSTORM_WITH_HAIL -> {
                cloud(s * 0.5f, s * 0.54f, s * 0.72f, ink)
                bolt(s, wet)
                grains(s, at = floatArrayOf(0.22f, 0.78f), colour = wet)
            }

            // The same dash the week uses for a dry day. A guessed picture would
            // be the app pretending to know something it was not told.
            WeatherCondition.UNKNOWN -> drawLine(
                color = ink,
                start = Offset(s * 0.32f, s * 0.5f),
                end = Offset(s * 0.68f, s * 0.5f),
                strokeWidth = s * STROKE,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A slab with three discs on it.
 *
 * [baseY] is the flat underside, which is what the falling marks hang from and
 * what makes two clouds of different widths look like they are at the same
 * height.
 */
private fun DrawScope.cloud(centreX: Float, baseY: Float, width: Float, colour: Color) {
    val body = width * 0.30f
    val left = centreX - width / 2f
    val slabTop = baseY - body

    drawRoundRect(
        color = colour,
        topLeft = Offset(left, slabTop),
        size = Size(width, body),
        cornerRadius = CornerRadius(body / 2f),
    )
    // Off-centre on purpose: a cloud with its big bump exactly in the middle
    // reads as a symbol of a cloud, and every real one is lopsided.
    drawCircle(colour, width * 0.24f, Offset(left + width * 0.26f, slabTop + body * 0.35f))
    drawCircle(colour, width * 0.30f, Offset(left + width * 0.52f, slabTop + body * 0.10f))
    drawCircle(colour, width * 0.20f, Offset(left + width * 0.80f, slabTop + body * 0.40f))
}

/** A disc with eight rays, or just the disc where a cloud is over it. */
private fun DrawScope.sun(centre: Offset, radius: Float, colour: Color) {
    drawCircle(colour, radius, centre)

    val inner = radius * 1.5f
    val outer = radius * 2.1f
    repeat(RAYS) { index ->
        val angle = (2.0 * PI / RAYS * index).toFloat()
        val dx = cos(angle)
        val dy = sin(angle)
        drawLine(
            color = colour,
            start = Offset(centre.x + dx * inner, centre.y + dy * inner),
            end = Offset(centre.x + dx * outer, centre.y + dy * outer),
            strokeWidth = radius * 0.24f,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Rain, as strokes leaning the way rain leans.
 *
 * [at] gives the positions across the glyph when the default spread is wrong for
 * a particular sky — a shower's cloud sits off to one side, and its rain has to
 * fall out of that cloud rather than out of the middle of the box.
 */
private fun DrawScope.drops(
    s: Float,
    count: Int,
    length: Float,
    colour: Color,
    at: FloatArray = spread(count),
) {
    val top = s * 0.72f
    at.forEach { x ->
        drawLine(
            color = colour,
            start = Offset(s * x + length * 0.14f, top),
            end = Offset(s * x - length * 0.14f, top + length),
            strokeWidth = s * STROKE,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Drizzle, as suspended droplets rather than falling streaks.
 *
 * This and rain used to be one drawing at two lengths - the same cloud, the same
 * three marks in the same places, one set half as long as the other. Side by
 * side the difference is visible; alone in a week row at a couple of dozen
 * device-independent pixels it is not, and a reader seeing a cloud with marks
 * under it reads rain.
 *
 * That was reported from use, and it mattered more than a drawing usually would:
 * the app had just been taught to reserve the *word* rain for rain worth naming,
 * so the bar would say rain starts on Wednesday while Sunday's drizzle still
 * looked like rain. The vocabulary was right and the picture was not.
 *
 * Round marks rather than long ones, because the difference is then one of kind
 * and survives being small. It is also what drizzle is: droplets fine enough to
 * hang in the air rather than fall through it. The same distinction the snow
 * pair already makes, where grains are dots and snow is crystals - this only
 * brings the rain pair up to it.
 *
 * Slightly larger than a snow grain, and set a little higher, so a drizzle glyph
 * is not mistaken for a snow-grain one either.
 */
private fun DrawScope.droplets(s: Float, at: FloatArray, colour: Color) {
    at.forEach { x ->
        drawCircle(colour, s * 0.055f, Offset(s * x, s * 0.80f))
    }
}

/** Snow, as three-stroke crystals. */
private fun DrawScope.flakes(s: Float, at: FloatArray, colour: Color) {
    val radius = s * 0.075f
    val y = s * 0.82f
    at.forEach { x ->
        val centre = Offset(s * x, y)
        repeat(3) { arm ->
            val angle = (PI / 3.0 * arm).toFloat()
            val dx = cos(angle) * radius
            val dy = sin(angle) * radius
            drawLine(
                color = colour,
                start = Offset(centre.x - dx, centre.y - dy),
                end = Offset(centre.x + dx, centre.y + dy),
                strokeWidth = s * STROKE * 0.85f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Hail and snow grains: hard, round, and small. */
private fun DrawScope.grains(s: Float, at: FloatArray, colour: Color) {
    at.forEach { x ->
        drawCircle(colour, s * 0.045f, Offset(s * x, s * 0.85f))
    }
}

private fun DrawScope.bolt(s: Float, colour: Color) {
    val path = Path().apply {
        moveTo(s * 0.54f, s * 0.60f)
        lineTo(s * 0.40f, s * 0.86f)
        lineTo(s * 0.50f, s * 0.86f)
        lineTo(s * 0.44f, s * 1.00f)
        lineTo(s * 0.64f, s * 0.78f)
        lineTo(s * 0.53f, s * 0.78f)
        lineTo(s * 0.62f, s * 0.60f)
        close()
    }
    drawPath(path, colour)
}

/** Fog, as bands the sky is seen through. */
private fun DrawScope.bands(s: Float, colour: Color) {
    listOf(0.68f to 0.80f, 0.82f to 0.94f, 0.96f to 0.62f).forEach { (y, width) ->
        val half = s * width / 2f
        drawLine(
            color = colour,
            start = Offset(s * 0.5f - half, s * y),
            end = Offset(s * 0.5f + half, s * y),
            strokeWidth = s * STROKE,
            cap = StrokeCap.Round,
        )
    }
}

/** The line freezing rain lands on. */
private fun DrawScope.ground(s: Float, colour: Color) {
    drawLine(
        color = colour,
        start = Offset(s * 0.18f, s * 0.94f),
        end = Offset(s * 0.82f, s * 0.94f),
        strokeWidth = s * STROKE,
        cap = StrokeCap.Round,
    )
}

/** Evenly across the middle of the glyph, which is where most clouds sit. */
private fun spread(count: Int): FloatArray = when (count) {
    1 -> floatArrayOf(0.5f)
    2 -> floatArrayOf(0.36f, 0.64f)
    else -> floatArrayOf(0.26f, 0.5f, 0.74f)
}

/**
 * Stroke weight as a fraction of the glyph, so it is the same optical weight at
 * every size it is drawn at. A fixed dp would make a large one look spindly.
 */
private const val STROKE = 0.075f

private const val RAYS = 8

/** Sized to the cap height of the row it sits in, not to a picture. */
val GLYPH = 22.dp
