package lv.bolwarra.wetter.data.repository

import java.time.Instant
import java.util.Locale
import lv.bolwarra.wetter.data.db.AnnouncedHazardDao
import lv.bolwarra.wetter.data.db.AnnouncedHazardEntity
import lv.bolwarra.wetter.domain.hazard.HazardAnnouncement
import lv.bolwarra.wetter.domain.hazard.HazardAnnouncements
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * What has already been said about a place, so it is not said twice.
 *
 * The refresh worker wakes every quarter of an hour and finds the same frost in
 * the forecast every time. Without a record of what has gone out, a cold night
 * would arrive as forty identical notifications, which is an app somebody turns
 * off before morning - and then never hears the one warning that mattered.
 *
 * Deliberately dumb. It holds opaque keys and a timestamp and knows nothing
 * about what a hazard is; the domain decides what a key means and when two
 * things are the same warning.
 */
class AnnouncedHazardStore internal constructor(private val dao: AnnouncedHazardDao) {

    /** Everything already announced for this place. */
    suspend fun said(location: WeatherLocation): Set<String> = dao.keysFor(keyOf(location)).toSet()

    /**
     * Written before the notification is posted, not after.
     *
     * A phone that dies between the two loses one warning. A phone that posts
     * and then fails to write repeats that warning every quarter of an hour
     * until the weather changes, which is the failure that makes somebody turn
     * the feature off.
     */
    suspend fun remember(
        location: WeatherLocation,
        announcements: List<HazardAnnouncement>,
        at: Instant,
    ) {
        if (announcements.isEmpty()) return
        val cacheKey = keyOf(location)
        dao.write(
            announcements.map {
                AnnouncedHazardEntity(
                    cacheKey = cacheKey,
                    hazardKey = it.key,
                    announcedAtEpochSecond = at.epochSecond,
                )
            },
        )
    }

    /** Drops keys for weather that is long over. */
    suspend fun prune(now: Instant) {
        dao.deleteOlderThan(HazardAnnouncements.forgettableBefore(now).epochSecond)
    }

    /** The same two decimals the forecast cache and the verification store use. */
    private fun keyOf(location: WeatherLocation): String =
        String.format(Locale.ROOT, "%.2f,%.2f", location.latitude, location.longitude)
}
