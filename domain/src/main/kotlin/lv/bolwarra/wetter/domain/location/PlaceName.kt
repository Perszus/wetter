package lv.bolwarra.wetter.domain.location

/**
 * What somewhere is called, once something has looked it up.
 *
 * Three fields because that is what a person reads: the thing itself, the town
 * it is in, and the country - the same shape [lv.bolwarra.wetter.domain.model.WeatherLocation]
 * already uses, so a looked-up name drops straight into a saved place.
 *
 * All of it optional, and the whole thing absent for most of the earth. Two
 * thirds of the surface is water and a good deal of the land has nothing named
 * on it; a pin in the middle of the Atlantic resolves to nothing at all, which
 * is a correct answer and not a failure.
 */
data class PlaceName(
    /** The most specific thing there is: a street and number, or a district. */
    val label: String,
    /** The settlement it sits in, when that is not already the label. */
    val region: String? = null,
    val country: String? = null,
)
