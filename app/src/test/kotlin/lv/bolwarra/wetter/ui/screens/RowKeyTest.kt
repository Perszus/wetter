package lv.bolwarra.wetter.ui.screens

import java.time.ZoneId
import java.util.Locale
import lv.bolwarra.wetter.domain.model.WeatherLocation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The identity a row in the locations list is keyed by.
 *
 * This exists because the list crashed. It was keyed on coordinates, which are
 * not an identity: the geocoder answers "Singapore" with both Singapore and
 * Singapore Island at exactly 1.36667, 103.8, and a `LazyColumn` given the same
 * key twice throws rather than degrading.
 */
class RowKeyTest {

    private val locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(locale)
    }

    private fun place(
        name: String,
        latitude: Double = 1.36667,
        longitude: Double = 103.8,
        region: String? = null,
        country: String? = "Singapore",
    ) = WeatherLocation(
        name = name,
        latitude = latitude,
        longitude = longitude,
        zone = ZoneId.of("Asia/Singapore"),
        region = region,
        country = country,
    )

    @Test
    fun `two places at one point are two rows`() {
        // The exact pair that crashed it, measured from the live geocoder.
        assertNotEquals(place("Singapore").rowKey(), place("Singapore Island").rowKey())
    }

    @Test
    fun `the same place twice is one row`() {
        assertEquals(place("Singapore").rowKey(), place("Singapore").rowKey())
    }

    @Test
    fun `places sharing a name are told apart by where they are`() {
        val kansas =
            place("Springfield", 37.21, -93.29, region = "Missouri", country = "United States")
        val illinois =
            place("Springfield", 39.80, -89.64, region = "Illinois", country = "United States")
        assertNotEquals(kansas.rowKey(), illinois.rowKey())
    }

    @Test
    fun `a missing region does not collide with an empty one`() {
        // Both absent and blank render as nothing, so they are the same row.
        assertEquals(place("Riga", region = null).rowKey(), place("Riga", region = "").rowKey())
    }

    @Test
    fun `the key does not change shape with the phone's language`() {
        // A decimal comma would still be deterministic, but a key whose format
        // follows the locale is a bug waiting for somebody else's device.
        Locale.setDefault(Locale.US)
        val english = place("Riga").rowKey()
        Locale.setDefault(Locale.forLanguageTag("lv-LV"))
        assertEquals(english, place("Riga").rowKey())
    }

    @Test
    fun `deduplicating by the key leaves both real places`() {
        val results = listOf(place("Singapore"), place("Singapore Island"), place("Singapore"))
        assertEquals(2, results.distinctBy { it.rowKey() }.size)
    }
}
