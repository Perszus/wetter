// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.repository

import java.time.Instant
import lv.bolwarra.wetter.data.db.ProviderHealthDao
import lv.bolwarra.wetter.data.db.ProviderHealthEntity
import lv.bolwarra.wetter.domain.provider.ProviderHealth
import lv.bolwarra.wetter.domain.provider.ProviderHealthRegistry

/**
 * Keeps what is known about each provider across a restart, so a service that has
 * been down for hours is not retried from scratch on every wake. Nothing is
 * aggregated and nothing leaves the device.
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
