package lv.bolwarra.wetter.data.provider.rainviewer

import kotlin.math.pow

/**
 * Turns a radar tile pixel into a rainfall rate.
 *
 * The tiles are pictures. They carry no numbers, only a colour scale with no
 * published physical meaning, so every part of this had to be established by
 * measurement rather than read out of a document.
 *
 * ### What was measured, and how
 *
 * The scale has three families: a translucent tan ramp, then opaque cool colours
 * (dark blue up to pale cyan), then opaque warm ones (dark red through orange to
 * yellow), with magenta above that. Two independent checks put them in that
 * order.
 *
 * Storms are stratified - the heaviest core sits inside progressively lighter
 * rain - so intensity should fall with distance from a core, and each family
 * should be enclosed by the one below it. Both hold. Over a 2560x1280 sample of
 * central Europe the mean index fell monotonically with distance from the warm
 * cores, and the ring around each family sat where the ordering predicts: the
 * translucent pixels are surrounded by higher values, the cool ones by lower,
 * and the warm ones are innermost. Within the translucent family, alpha rose
 * steadily the closer a pixel sat to a core, from 46 far out to 165 against the
 * cores, which is what makes it the *low end of one scale* rather than a
 * separate overlay.
 *
 * ### What is not established
 *
 * The absolute calibration. Fitting the scale against Open-Meteo's precipitation
 * produced a correlation of only +0.37 and implied half a millimetre an hour at
 * full scale, which is nonsense for a saturated echo - the reference is an 11 km
 * forecast quantised to tenths, compared against kilometre observations, and
 * noise in both variables flattens the fit rather than sharpening it. So the
 * span below is the conventional one for a radar composite, not a fitted result.
 * [MIN_DBZ] and [MAX_DBZ] are the two numbers to revisit once the verification
 * store has enough observed rain to fit against something real.
 *
 * Rates from here are therefore good for *shape* - where the rain is, which way
 * it is going, whether it is growing - and only approximate in magnitude. The
 * fusion layer weights them accordingly.
 */
internal object RainViewerPalette {

    /**
     * The span the colour scale is read across.
     *
     * The top is 55 rather than the 75 a radar scale actually reaches, and that
     * is a deliberate hail cap rather than a shortened scale. Marshall-Palmer
     * relates reflectivity to *rain*, and above roughly 55 dBZ the echo is hail
     * or a melting layer, which reflects far more strongly than the equivalent
     * rain. Read literally, 73 dBZ comes out as 1300 mm/h - a number with no
     * physical meaning, arrived at by applying a rain relation to something that
     * is not rain. Capping is what operational radar processing does for the
     * same reason, and it puts the top of this scale near 100 mm/h, which is
     * about as hard as rain ever actually falls.
     */
    const val MIN_DBZ = 5.0
    const val MAX_DBZ = 55.0

    /** Marshall-Palmer, the standard relation between reflectivity and rate. */
    private const val Z_A = 200.0
    private const val Z_B = 1.6

    /** Where each family ends, as a fraction of the whole scale. */
    private const val TRANSLUCENT_TOP = 0.37
    private const val COOL_TOP = 0.67
    private const val WARM_TOP = 0.97

    /** Measured bounds of the translucent ramp's alpha. */
    private const val FAINTEST_ALPHA = 20.0
    private const val STRONGEST_ALPHA = 190.0

    /** Measured luminance bounds within each opaque family. */
    private const val COOL_MIN_LUMA = 53.0
    private const val COOL_MAX_LUMA = 198.0
    private const val WARM_MIN_LUMA = 28.0
    private const val WARM_MAX_LUMA = 216.0

    /** Red and blue both at least this high means the magenta topping the scale. */
    private const val MAGENTA_CHANNEL = 200

    /**
     * Millimetres per hour for one pixel, from packed ARGB.
     *
     * A fully transparent pixel is 0.0 rather than unobserved. That is a real
     * concession: this source draws "no echo" and "outside radar coverage"
     * identically, so the two genuinely cannot be told apart here. Reporting the
     * whole map as unobserved would make it useless, so within the composite's
     * footprint transparent is read as dry - and the honest consequence is that
     * a coverage hole in this source looks like fine weather. Sources that do
     * publish a coverage mask should mark it with
     * [lv.bolwarra.wetter.domain.radar.RadarField.NO_ECHO], which the rest of the
     * engine already handles.
     */
    fun rateOf(argb: Int): Float {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0) return 0f

        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF

        val fraction = fractionOf(alpha, red, green, blue)
        val dbz = MIN_DBZ + fraction * (MAX_DBZ - MIN_DBZ)
        return millimetresPerHour(dbz).toFloat()
    }

    /** Where a colour sits on the scale, 0 at the faintest trace and 1 at hail. */
    fun fractionOf(alpha: Int, red: Int, green: Int, blue: Int): Double {
        if (alpha < 255) {
            val within = ((alpha - FAINTEST_ALPHA) / (STRONGEST_ALPHA - FAINTEST_ALPHA))
                .coerceIn(0.0, 1.0)
            return within * TRANSLUCENT_TOP
        }

        val luma = 0.299 * red + 0.587 * green + 0.114 * blue
        // Magenta first. It sits at the very top of the scale and has *both* red
        // and blue at full, so a "blue is at least as strong as red" test for the
        // cool family claims it - filing the most extreme colour on the scale
        // among the lightest.
        if (red >= MAGENTA_CHANNEL && blue >= MAGENTA_CHANNEL) {
            val within = (green / 255.0).coerceIn(0.0, 1.0)
            return WARM_TOP + within * (1.0 - WARM_TOP)
        }
        // Cool means blue is at least as strong as red: the blues and cyans.
        if (blue >= red) {
            val within = ((luma - COOL_MIN_LUMA) / (COOL_MAX_LUMA - COOL_MIN_LUMA))
                .coerceIn(0.0, 1.0)
            return TRANSLUCENT_TOP + within * (COOL_TOP - TRANSLUCENT_TOP)
        }
        val within = ((luma - WARM_MIN_LUMA) / (WARM_MAX_LUMA - WARM_MIN_LUMA))
            .coerceIn(0.0, 1.0)
        return COOL_TOP + within * (WARM_TOP - COOL_TOP)
    }

    /** Marshall-Palmer inverted: Z = 200 R^1.6, so R = (Z/200)^(1/1.6). */
    fun millimetresPerHour(dbz: Double): Double {
        val z = 10.0.pow(dbz / 10.0)
        return (z / Z_A).pow(1.0 / Z_B)
    }
}
