package lv.bolwarra.wetter.domain.radar

import java.time.Instant
import kotlin.math.hypot

/**
 * Synthetic sweeps with known contents.
 *
 * Real radar cannot be used to test a motion estimator, because nobody knows the
 * true answer for a real sweep - which is the whole difficulty of the subject.
 * A field built by translating a known pattern by a known amount has an answer
 * that can be asserted.
 *
 * The pattern is several cones of different size and intensity rather than one.
 * A single symmetrical blob has a broad, flat cost minimum - it matches itself
 * almost as well slightly misaligned as aligned - so it would pass a test that a
 * uniform sheet of drizzle would also pass, which proves nothing.
 */
internal object RadarTestFields {

    /** A block of tiles over Riga, which is where the numbers were measured. */
    fun geometry(tiles: Int = 1): RadarGeometry = RadarGeometry.ofTileBlock(
        zoom = 7,
        tileX = 72,
        tileY = 39,
        tilesAcross = tiles,
        tilesDown = tiles,
    )

    private data class Cone(val x: Float, val y: Float, val radius: Float, val peak: Float)

    private val PATTERN = listOf(
        Cone(70f, 80f, 34f, 6.0f),
        Cone(150f, 60f, 22f, 12.0f),
        Cone(110f, 170f, 40f, 3.0f),
        Cone(190f, 150f, 18f, 9.0f),
    )

    /**
     * The pattern, shifted by ([shiftX], [shiftY]) pixels.
     *
     * @param scale multiplies every intensity, for testing growth and decay.
     * @param holes when set, a band down the left is marked unobserved, so
     *   coverage handling can be exercised.
     */
    fun pattern(
        at: Instant,
        geometry: RadarGeometry = geometry(),
        shiftX: Float = 0f,
        shiftY: Float = 0f,
        scale: Float = 1f,
        holes: Boolean = false,
    ): RadarField {
        val values = FloatArray(geometry.width * geometry.height)
        for (y in 0 until geometry.height) {
            for (x in 0 until geometry.width) {
                if (holes && x < HOLE_WIDTH) {
                    values[y * geometry.width + x] = RadarField.NO_ECHO
                    continue
                }
                var total = 0f
                for (cone in PATTERN) {
                    val distance = hypot(x - (cone.x + shiftX), y - (cone.y + shiftY))
                    if (distance < cone.radius) {
                        total += cone.peak * (1f - distance / cone.radius)
                    }
                }
                values[y * geometry.width + x] = total * scale
            }
        }
        return RadarField(at, geometry, values)
    }

    /** A field with nothing falling anywhere - observed, and dry. */
    fun dry(at: Instant, geometry: RadarGeometry = geometry()): RadarField =
        RadarField(at, geometry, FloatArray(geometry.width * geometry.height))

    const val HOLE_WIDTH = 40
}
