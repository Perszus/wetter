package lv.bolwarra.wetter.domain.location

import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * Finding a place by name.
 *
 * Behind an interface like every other source, because geocoding is somebody
 * else's service with its own terms and its own coverage, and the screen that
 * uses it should not know whose.
 */
interface PlaceSearch {

    val attribution: String

    /**
     * Places matching what has been typed, best match first.
     *
     * An empty list is a normal answer - most of what anybody types on the way
     * to a place name matches nothing - and is not an error.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): Result<List<WeatherLocation>>

    companion object {
        const val DEFAULT_LIMIT = 8

        /**
         * Below this a query matches half the world and the answer is noise.
         * Two letters is enough for somewhere genuinely short.
         */
        const val MINIMUM_QUERY = 2
    }
}
