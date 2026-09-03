package lv.bolwarra.wetter.domain.observation

/**
 * Somewhere measurements come from.
 *
 * Separate from a weather provider because it answers a different question. A
 * provider says what the weather will be; this says what it was, at a place, at
 * a time that has already happened. Nothing else in the app can settle whether a
 * forecast was any good, so this is what the verification loop is built on.
 */
interface ObservationSource {

    val id: String

    val attribution: String

    /**
     * The latest report from each station near a point.
     *
     * @param radiusKm how far to look. Wide enough to find several stations,
     *   because one station has no way of being checked.
     */
    suspend fun near(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Result<List<WeatherObservation>>

    /**
     * Every report from the stations near a point over the last [hours].
     *
     * Used for verification rather than display: matching a forecast against
     * what happened needs the observation from the hour being checked, not the
     * newest one.
     */
    suspend fun history(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        hours: Int,
    ): Result<List<WeatherObservation>>
}
