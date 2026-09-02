package lv.bolwarra.wetter.domain.model

/**
 * Why a forecast could not be produced.
 *
 * A closed set, so the UI can say something specific and true without ever
 * putting an exception message on screen (docs/design-principles.md). The cause is kept for
 * the log; it is not for the user.
 */
sealed interface WeatherError {
    /** The device has no usable connection. */
    data object Offline : WeatherError

    /** Reached the provider, but it did not answer in time. */
    data object Timeout : WeatherError

    /** The provider answered with a failure. [status] is the HTTP status. */
    data class ProviderRejected(val status: Int) : WeatherError

    /** The provider answered with something this version cannot read. */
    data class MalformedResponse(val cause: Throwable? = null) : WeatherError

    /** No location is selected, or the device would not give one. */
    data object LocationUnavailable : WeatherError

    /**
     * Every provider was either outside its coverage here or unable to supply
     * what Wetter needs. Distinct from a provider failing: nothing was even
     * tried, and retrying will not help until the location changes.
     */
    data object NoProviderAvailable : WeatherError

    data class Unknown(val cause: Throwable? = null) : WeatherError
}
