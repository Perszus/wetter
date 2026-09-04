package lv.bolwarra.wetter.data.repository

import java.time.Instant
import lv.bolwarra.wetter.data.db.ProviderHealthDao
import lv.bolwarra.wetter.data.db.ProviderHealthEntity
import lv.bolwarra.wetter.domain.provider.ProviderHealth
import lv.bolwarra.wetter.domain.provider.ProviderHealthRegistry

/**
 * Keeps what is known about each provider across a restart.
 *
 * The knowledge is slow to earn and costs a few rows to keep. Held only in
 * memory, an app waking to a service that has been down for hours would try it,
 * fail, wait, and learn that fact again from scratch every single time - which
 * is the request the backoff exists to avoid making. `docs/decisions.md` had
 * this down as an open question; this settles it.
 *
 * Nothing is aggregated and nothing leaves the device. It is a note to self
 * about who to ask first.
 */
internal class ProviderHealthStore(private val dao: ProviderHealthDao) {

    suspend fun restoreInto(registry: ProviderHealthRegistry) {
        val remembered = runCatching { dao.all() }.getOrNull().orEmpty()
        registry.restore(remembered.associate { it.providerId to it.toDomain() })
    }

    suspend fun save(registry: ProviderHealthRegistry) {
        val rows = registry.snapshot().values.map { it.toEntity() }
        if (rows.isNotEmpty()) runCatching { dao.write(rows) }
    }

    private fun ProviderHealthEntity.toDomain() = ProviderHealth(
        providerId = providerId,
        lastSuccess = lastSuccessEpochSecond?.let(Instant::ofEpochSecond),
        lastFailure = lastFailureEpochSecond?.let(Instant::ofEpochSecond),
        consecutiveFailures = consecutiveFailures,
        cooldownUntil = cooldownUntilEpochSecond?.let(Instant::ofEpochSecond),
    )

    private fun ProviderHealth.toEntity() = ProviderHealthEntity(
        providerId = providerId,
        lastSuccessEpochSecond = lastSuccess?.epochSecond,
        lastFailureEpochSecond = lastFailure?.epochSecond,
        consecutiveFailures = consecutiveFailures,
        cooldownUntilEpochSecond = cooldownUntil?.epochSecond,
    )
}
