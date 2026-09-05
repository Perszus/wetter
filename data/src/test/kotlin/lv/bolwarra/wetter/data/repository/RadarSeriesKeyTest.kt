package lv.bolwarra.wetter.data.repository

import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.WeatherLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Whose radar a place gets.
 *
 * The kept series is a list of samples already taken at one point - unlike the
 * projection in memory, there is no way to re-aim it at somebody else's
 * coordinates. So the key that files it has to be fine enough that two
 * distinguishable places never share one, or the map's whole purpose is lost on
 * the first draw after opening.
 */
class RadarSeriesKeyTest {

    private fun at(latitude: Double, longitude: Double) = WeatherLocation(
        name = "pin",
        latitude = latitude,
        longitude = longitude,
        zone = ZoneId.of("Europe/Riga"),
    )

    private fun key(latitude: Double, longitude: Double) =
        NowcastRepository.keyOf(at(latitude, longitude))

    @Test
    fun `two pins a few streets apart do not share a series`() {
        // The case that motivated this: at two decimals these were one key, so
        // the second pin opened showing the first one's radar.
        assertNotEquals(key(56.9156, 24.0730), key(56.9226, 24.0730))
    }

    @Test
    fun `half a kilometre is far enough to be its own place`() {
        assertNotEquals(key(56.9156, 24.0730), key(56.9201, 24.0730))
    }

    @Test
    fun `points inside one radar pixel share, because their samples are the same`() {
        // About thirty metres. A radar pixel averages over roughly a kilometre,
        // so refusing to share here would fetch the same numbers twice.
        assertEquals(key(56.91560, 24.07300), key(56.91562, 24.07301))
    }

    @Test
    fun `the sign survives, so north is not filed with south`() {
        assertNotEquals(key(56.9156, 24.0730), key(-56.9156, 24.0730))
        assertNotEquals(key(56.9156, 24.0730), key(56.9156, -24.0730))
    }
}
