# Weather providers

Wetter is not an Open-Meteo client. It knows about several meteorological
services, what each can supply and where each is worth using, and picks one per
location. The rest of the app never learns which.

```text
                         Wetter UI
                             │
                      WeatherRepository
                             │
                    WeatherProviderRouter
                             │
              ┌──────────────┴──────────────┐
              ↓                             ↓
        OpenMeteoProvider            MetNorwayProvider
              │                             │
              └──────────────┬──────────────┘
                             ↓
                      ForecastStitcher
                             ↓
                       WeatherForecast
```

`ui/` and `domain/` contain no provider-specific code at all. A provider's
response types are `internal` to its own package and are converted by a mapper
that sits beside them; nothing else can name them.

## The abstraction

```kotlin
interface WeatherProvider {
    val id: String
    val displayName: String
    val attribution: String
    val capabilities: ProviderCapabilities
    val coverage: ProviderCoverage

    suspend fun getForecast(location: WeatherLocation): Result<WeatherForecast>
}
```

`getForecast` returns a `Result` rather than throwing. A provider failing is an
ordinary event in a multi-provider system — it is the router's cue to try the
next candidate, not an exception to propagate upwards.

**Capabilities** are a set of `WeatherVariable`, a maximum forecast length, an
hourly horizon, a model resolution and an update interval. They record what a provider actually
delivers, not what its documentation promises.

**Coverage** is whether the provider answers globally, plus a list of rectangles
where it is a regional speciality. Each region carries a strength between 0 and
1 and, optionally, the finer resolution of the model that runs there.

Rectangles are crude — a box around the Nordics also contains a slice of the
Atlantic. They are used anyway, because provider coverage genuinely is coarse
and the alternative is shipping a polygon dataset to answer a question that only
ever ranks two or three candidates.

## Selection

`ScoringProviderSelector` scores each provider out of roughly ninety:

| Term | Weight | What it measures |
|---|---:|---|
| Geographic base | 20 | Serves this location at all |
| Regional preference | 30 | Strength of the best regional claim here |
| Capability fit | 25 | Share of the optional variables Wetter can use |
| Resolution | 10 | Grid spacing of the model that runs *here* |
| Update frequency | 5 | How often a new run is published |
| Health penalty | −5 each, −100 resting | Recent failures |

Two things are exclusions rather than penalties: a provider that does not serve
the location, and one that cannot supply a *required* variable. Everything else
is a score.

There is no chain of `if (country == …)`. Adding a provider is a data change,
and adjusting policy is an edit to six constants.

**Boundaries are soft.** A regional preference decays linearly to zero over two
degrees beyond its rectangle, so a provider does not change the instant a border
is crossed. Weather does not respect the edge of a rectangle and neither should
a forecast somebody is watching while they walk down a street.

**Ranking is deterministic.** The same location, providers and health always
produce the same order, with ties broken by provider id. This is what makes the
behaviour testable and debuggable.

**The current source is preferred in a near-tie.** If the provider that supplied
what is on screen is within 8 points of the leader, it keeps its place. Swapping
sources for a point of score would change the numbers under someone's eyes for
no reason they could perceive.

**A resting provider stays eligible.** It is penalised heavily enough to lose to
anything healthy, but not excluded — otherwise an outage on both sides of a
fallback pair would leave the app unable to recover on its own.

## Keeping the timeline hourly

The best provider for a place is often not the one that reaches furthest. MET
Norway runs a 2.5 km model over the Nordics and is the right source for whether
it rains this afternoon — but it is hourly for only about sixty hours, after
which its series drops to six-hourly steps. Choosing it naively would mean a
better forecast for two days and *no hourly timeline at all* for the other five.

So a forecast is assembled adaptively. The winner supplies the hours it has, and
the next-best candidate supplies the rest:

```text
hours 0–60      MET Norway        2.5 km, the model that knows this coastline
hours 60–168    Open-Meteo        global blend, still hour by hour
                     ↑ the join, exactly where MET Norway stops being hourly
```

`ProviderCapabilities.hourlyHorizonHours` is what makes this decidable, and it is
deliberately separate from `maximumForecastDays` — MET Norway forecasts nine days
and is hourly for two and a half of them.

The rules, all enforced in `ForecastStitcher`:

- **Nothing is invented.** Six-hourly steps are never spread into hours. A bar
  six times too wide is worse than no bar, because it is wrong about *when*.
- **The join lands where the first source stops.** It is not moved to a day
  boundary, because that would throw away up to a day of the better model to
  make the seam tidier.
- **A day's summary comes from whoever drew that day's hours**, so the daily row
  can never describe one model while the bars above it draw another.
- **The extension is optional.** It costs at most one extra request, and if that
  request fails the user still gets the forecast that already succeeded.
  Extending a forecast must never be able to cost somebody one.
- **A small shortfall is ignored.** A forecast starting at local midnight is
  always a few hours short of any round horizon by mid-morning; only a gap over
  twelve hours is worth a second request.

`ForecastRequirements.hourlyHorizon` is six days rather than seven: the daily
forecast runs a week, and six guarantees hour-by-hour detail for every day of it
bar the tail of the last.

The result records both sources — `provider` stays the primary, and `supplement`
names the second and the instant it took over. Neither is shown on the main
screen. Both are credited in About, as their licences require.

## Failover

The router tries the preferred provider, then at most one fallback. It never
retries the same provider inside a single refresh: one that just timed out will
most likely time out again a second later, and the alternative is already ranked
and waiting.

Whether a second provider is tried depends on whether the failure was about
*this* provider:

| Failure | Fail over? | Why |
|---|---|---|
| Timeout | yes | The provider's problem |
| HTTP 5xx | yes | The provider's problem |
| HTTP 429 | yes | Rate-limited here, not everywhere |
| Malformed response | yes | This service cannot be read right now |
| Offline | **no** | No second provider fixes a device with no connection |
| HTTP 4xx (other) | **no** | The request was wrong; it will be wrong elsewhere too |

## Health and backoff

`ProviderHealth` records consecutive failures and, when a provider sends
`Retry-After`, an explicit cooldown. One failure is treated as noise; from the
second, an exponential backoff starts at 5 minutes and is capped at 2 hours.
Backoff happens *between* refreshes rather than inside one, which is what keeps
the app from hammering a service that is having a bad afternoon.

Failures caused by having no connection are not counted against any provider. A
week without signal would otherwise demote every provider the app has.

Health is kept in memory. An app that has been closed long enough to be evicted
should start from a clean slate rather than resume a stale grudge.

## Adding a provider

1. Check the terms first — see the checklist below. This is the step that
   disqualifies most candidates, and doing it last wastes the implementation.
2. Add a package under `data/provider/`, containing the response types
   (`internal`), a mapper, and the `WeatherProvider` implementation.
3. Declare honest capabilities. If a documented field is null in practice, leave
   it out: the selector's job is to predict what will arrive. Take particular
   care with `hourlyHorizonHours` — it is how far the provider is *genuinely*
   hour by hour, which for several services is much less than how far it
   forecasts at all.
4. Declare coverage. Give a region its own `resolutionKm` if a finer model runs
   there.
5. Register it in `WetterContainer.providers`. Nothing else changes.
6. Add a recorded response under `src/test/resources` and a mapper test that
   covers its awkward parts, not its easy ones.
7. Add the attribution to the list in this file and in the README.

### Terms-of-service checklist

Technical accessibility is not permission. Before adding a provider, confirm and
record:

- [ ] Is the service usable by a free, open-source client at all?
- [ ] Does it require an API key? A key that must be embedded in a public
      repository is a non-starter.
- [ ] What attribution is required, and in what exact wording?
- [ ] What is the licence of the data itself, and is redistribution allowed?
- [ ] What are the rate limits, and does it require an identifying User-Agent?
- [ ] Is non-commercial-only use a condition? Wetter is free, but a restriction
      that forbids commercial use makes the data non-free and may be
      incompatible with the app's licence.
- [ ] Does it ask for caching behaviour or coordinate truncation?

## Current providers

### Open-Meteo — the global baseline

- <https://open-meteo.com>
- Data licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/);
  attribution: *"Weather data by Open-Meteo.com, licensed CC BY 4.0"*
- No API key. Free for non-commercial use.
- Supplies every variable Wetter can draw, up to 16 days.
- Global, with no regional preference. It is the fallback everywhere, which is
  precisely what makes it valuable.
- `baseUrl` is a constructor parameter, so a self-hosted instance can be used
  instead without any other change.

### MET Norway — the regional specialist

- <https://api.met.no>
- Data licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/);
  attribution: *"Weather data from MET Norway (met.no), licensed CC BY 4.0"*
- Terms: <https://api.met.no/doc/TermsOfService>
- **Requires an identifying User-Agent** with a means of contact. This is
  installed once on the shared HTTP client; sending a generic one is grounds for
  being blocked.
- **Coordinates are truncated to four decimals**, as the terms ask, so requests
  land on shared cache entries rather than one per user. This is a privacy
  benefit as well as a courtesy: it caps how precisely a request describes where
  somebody is.
- Runs a 2.5 km model over the Nordic area and a global model elsewhere, which
  is why its Nordic region carries its own resolution.
- Hourly for about 60 hours, then six-hourly to day nine. Wetter uses the hourly
  part and extends the rest from Open-Meteo — see
  [Keeping the timeline hourly](#keeping-the-timeline-hourly).
- Publishes no snow depth, no sunrise and no sunset. The first is left absent;
  the other two are computed from the sun's position, which is why the provider
  claims the sunrise capability.
- Its response is one timeseries of instants rather than separate hourly and
  daily blocks, and it drops from hourly to six-hourly after about two and a
  half days. The mapper handles both, and takes care that a six-hourly step is
  not drawn as an hour or counted twice.

MET Norway is **not** presented as globally better than Open-Meteo. It is
preferred where its fine grid runs, and treated as an ordinary global source
everywhere else.

## Debugging a decision

In debug builds the router logs its ranking and the reason each provider scored
what it did:

```text
WeatherProviderRouter  ranking for 56.9, 24.1
WeatherProviderRouter    met-norway: 87.3 — Covers this location; Regional source
                         for the Nordic region; Supplies most variables Wetter
                         uses; Resolution about 2.5 km here
WeatherProviderRouter    open-meteo: 78.6 — Covers this location; Supplies every
                         variable Wetter uses; Resolution about 11.0 km here
```

Coordinates are rounded to one decimal before they reach the log. A debug log is
still a file on a device, and there is no reason for it to record where somebody
lives to five decimal places.
