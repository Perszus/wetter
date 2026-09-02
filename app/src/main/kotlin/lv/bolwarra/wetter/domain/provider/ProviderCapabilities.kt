package lv.bolwarra.wetter.domain.provider

/**
 * What a provider can actually deliver, as opposed to what it advertises.
 *
 * Filled in from the provider's own documentation and from what its responses
 * are observed to contain — a field that is documented but null in practice
 * should be absent here, because the selector's job is to predict what will
 * arrive, not what was promised.
 */
data class ProviderCapabilities(
    val variables: Set<WeatherVariable>,
    val maximumForecastDays: Int,
    /**
     * Approximate horizontal grid spacing of the underlying model, in kilometres.
     * Lower is better for precipitation, where a shower is often smaller than the
     * cell that is meant to contain it. Null when the provider does not say.
     */
    val resolutionKm: Double?,
    /** How often the provider publishes a new run. Null when it does not say. */
    val updateIntervalHours: Double?,
) {
    fun supports(variable: WeatherVariable): Boolean = variable in variables

    fun satisfies(requirements: ForecastRequirements): Boolean =
        variables.containsAll(requirements.required) &&
            maximumForecastDays >= requirements.minimumForecastDays

    /** 0.0 when none of the desired extras are present, 1.0 when all are. */
    fun desiredCoverage(requirements: ForecastRequirements): Double {
        if (requirements.desired.isEmpty()) return 1.0
        val met = requirements.desired.count { it in variables }
        return met.toDouble() / requirements.desired.size
    }
}
