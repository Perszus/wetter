package lv.bolwarra.wetter.data.provider

import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpHeaders
import io.ktor.serialization.ContentConvertException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import kotlinx.serialization.SerializationException
import lv.bolwarra.wetter.domain.model.WeatherError

/**
 * Turns whatever went wrong into something the router can act on.
 *
 * The distinction that matters is [WeatherError.Offline] versus everything else:
 * an offline failure says nothing about the provider and must not count against
 * its health, or a week without signal would demote every provider Wetter has.
 *
 * The offline test is a heuristic — a DNS failure could equally mean the
 * provider's own domain is broken. It errs towards blaming the network, because
 * wrongly resting a healthy provider is the more expensive mistake.
 */
fun Throwable.toWeatherError(): WeatherError = when (this) {
    is UnknownHostException, is ConnectException, is NoRouteToHostException ->
        WeatherError.Offline

    is HttpRequestTimeoutException, is SocketTimeoutException ->
        WeatherError.Timeout

    is ResponseException ->
        WeatherError.ProviderRejected(response.status.value)

    // Ktor wraps a deserialization failure in its own exception rather than
    // letting kotlinx's through, and answers with the wrong content type by
    // throwing a third. All three mean the same thing to the router: this
    // response cannot be read, try somebody else.
    is ContentConvertException, is NoTransformationFoundException, is SerializationException ->
        WeatherError.MalformedResponse(this)

    is IOException ->
        WeatherError.Unknown(this)

    else -> WeatherError.Unknown(this)
}

/**
 * How long a provider asked to be left alone, if it said.
 *
 * Only the delay-seconds form is read. The HTTP-date form is rare in practice
 * for rate limiting, and parsing it wrongly would mean either hammering a
 * provider that asked for quiet or resting one for a decade.
 */
fun Throwable.retryAfter(): Duration? {
    val response = (this as? ResponseException)?.response ?: return null
    val header = response.headers[HttpHeaders.RetryAfter] ?: return null
    val seconds = header.trim().toLongOrNull() ?: return null
    if (seconds <= 0) return null
    return Duration.ofSeconds(seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS))
}

/** A provider does not get to silence itself for longer than a day. */
private const val MAX_RETRY_AFTER_SECONDS = 24L * 60 * 60
