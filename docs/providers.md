# Weather providers

Wetter is not an Open-Meteo client. It knows several meteorological services,
what each can supply and where each is worth using, and picks one per location.
The rest of the app never learns which.

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

`ui/` and `domain/` contain no provider-specific code. A provider's response
types are `internal` to its own package and converted by a mapper beside them.

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

`getForecast` returns a `Result`: a provider failing is the router's cue to try
the next candidate, not an exception.

**Capabilities** are a set of `WeatherVariable`, a maximum forecast length, an
hourly horizon, a model resolution and an update interval, recording what a
provider actually delivers rather than what its documentation promises.

**Coverage** is whether the provider answers globally, plus rectangles where it
is a regional speciality, each with a strength between 0 and 1 and, optionally,
the finer resolution of the model that runs there. Rectangles are crude, but
provider coverage is coarse and the question only ever ranks two or three
candidates.

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

A provider that does not serve the location, or cannot supply a *required*
variable, is excluded; everything else is a score. Adding a provider is a data
change, and adjusting policy is an edit to six constants.

- **Boundaries are soft.** A regional preference decays to zero over two degrees
  beyond its rectangle, so the provider does not change the instant a border is
  crossed.
- **Ranking is deterministic.** The same location, providers and health always
  give the same order, ties broken by provider id.
- **The current source is kept in a near-tie.** Within 8 points of the leader,
  the provider behind what is on screen keeps its place, so numbers do not change
  under somebody's eyes for nothing.
- **A resting provider stays eligible**, penalised enough to lose to anything
  healthy, so an outage on both sides of a fallback pair can still recover.

## Keeping the timeline hourly

The best provider for a place is often not the one that reaches furthest. MET
Norway's 2.5 km Nordic model is the right source for this afternoon, but it is
hourly for only about sixty hours, then six-hourly. So the winner supplies the
hours it has and the next candidate the rest:

```text
hours 0–60      MET Norway        2.5 km, the model that knows this coastline
hours 60–168    Open-Meteo        global blend, still hour by hour
                     ↑ the join, where MET Norway stops being hourly
```

`ProviderCapabilities.hourlyHorizonHours` makes this decidable; it is separate
from `maximumForecastDays`, since MET Norway forecasts nine days and is hourly
for two and a half. The rules, enforced in `ForecastStitcher`:

- **Nothing is invented.** Six-hourly steps are never spread into hours.
- **The join lands where the first source stops**, not on a day boundary.
- **A day's summary comes from whoever drew most of that day's hours**, so the
  daily row describes the same model as the bars under it.
- **The extension is optional.** At most one extra request, requested alongside
  the first when the first is known to fall short; if it fails, the forecast that
  succeeded is kept.
- **A small shortfall is ignored.** Only a gap over twelve hours is worth a
  second request.

`ForecastRequirements.hourlyHorizon` is six days: the week view gets hourly
detail for every day bar the tail of the last. The forecast records both
sources: `provider` is the primary, `supplement` the second and when it took
over. Neither is shown on the main screen; both are credited in About.

## Failover

The router tries the preferred provider, then at most one fallback, and never
retries the same provider within a refresh. Whether a second is tried depends on
whether the failure was about *this* provider:

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
second, an exponential backoff starts at 5 minutes and is capped at 2 hours,
applied between refreshes. Failures from having no connection count against no
provider. Health is kept on disk (`ProviderHealthStore`), so an app waking to a
service that has been down for hours does not try it from scratch.

## Adding a provider

1. Check the terms first, against the checklist below. This step disqualifies
   most candidates.
2. Add a package under `data/provider/` with the response types (`internal`), a
   mapper, and the `WeatherProvider` implementation.
3. Declare honest capabilities. Leave out a documented field that is null in
   practice, and set `hourlyHorizonHours` to how far the provider is genuinely
   hour by hour.
4. Declare coverage, with a region's own `resolutionKm` if a finer model runs
   there.
5. Register it in `WeatherData.providers`. Nothing else changes.
6. Add a recorded response under `src/test/resources` and a mapper test covering
   its awkward parts.
7. Add the attribution here, in the README and in the privacy policy;
   `ReleaseReadinessTest` checks the last two against the code.

### Terms-of-service checklist

- [ ] Is the service usable by a free, open-source client at all?
- [ ] Does it need an API key? One embedded in a public repository is a non-starter.
- [ ] What attribution is required, and in what wording?
- [ ] What is the licence of the data, and is redistribution allowed?
- [ ] What are the rate limits, and is an identifying User-Agent required?
- [ ] Is use restricted to non-commercial purposes?
- [ ] Does it ask for caching behaviour or coordinate truncation?

## Current providers

### Open-Meteo: the global baseline

- <https://open-meteo.com>, data licensed
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); attribution
  *"Weather data by Open-Meteo.com, licensed CC BY 4.0"*.
- No API key; free for non-commercial use.
- Every variable Wetter draws, up to 16 days, globally. The fallback everywhere.
- `baseUrl` is a constructor parameter, so a self-hosted instance can be used
  without other changes.

### MET Norway: the regional specialist

- <https://api.met.no>, data licensed
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); attribution
  *"Weather data from MET Norway (met.no), licensed CC BY 4.0"*; terms
  <https://api.met.no/doc/TermsOfService>.
- **Requires an identifying User-Agent** with a means of contact, set once on the
  shared HTTP client.
- **Coordinates are truncated to four decimals**, as the terms ask, which also
  caps how precisely a request describes where somebody is.
- A 2.5 km model over the Nordic area and a global model elsewhere. Preferred
  where the fine grid runs; an ordinary global source everywhere else.
- Hourly for about 60 hours, then six-hourly to day nine, in one timeseries of
  instants that the mapper splits without counting a six-hourly step twice.
- No snow depth, sunrise or sunset: the first is left absent, the others are
  computed from the sun's position.

## Radar and nowcasting

Radar is not routed as a forecast provider; it answers a different question. A
numerical model predicts from a simulated atmosphere initialised hours ago, which
is why models are weakest about the next twenty minutes. Radar observes the
precipitation that exists now, at about a kilometre, and says nothing about
tonight.

Wetter does its own nowcasting: the observed field is carried forward (motion
estimated by block matching, backward trajectories, growth and decay with a
saturating limit) and blended with the models by lead time. RainViewer stopped
publishing forecast frames at the start of 2026, so past the present moment there
is nothing to fetch.

The first half hour is stepped at five minutes, the rest at ten, so a projected
value is always within a few minutes of the moment being read.

### The hand-over

| Lead | Radar's share | Why |
|---|---|---|
| 0 – 1 h | 80% | The sky is being watched rather than predicted. The models keep a fifth for snow, drizzle under the beam and coverage holes radar cannot see. |
| next 20 min | eased to nothing | A smoothstep, so neither the value nor its rate of change steps. |
| after that | 0% | Carrying a frozen field further is no longer worth a vote. |

An hour, because advection assumes the field is only carried along: nearly true
over ten minutes, strained by an hour, and by two hours cells have formed and
died. The window shortens with the quality of the motion match, from the full
hour for a field with edges and cells down to 15 minutes for a featureless sheet.
At zero lead the sample is the observation itself, which no motion error can
corrupt, so a poor match costs reach, never the present.

Confidence is carried separately and is not raised to match: a value taken from
radar two hours out is still less certain than one ten minutes out.

### The model term

Past the radar window no single model decides. The model term is the **median of
the ensemble members and the chosen provider together**: one deterministic run
can be wrong in ways its own output never reveals. On one wet evening in Riga the
chosen provider reported 0.0 mm for every hour after the next while ECMWF, UKMO
and DMI had 1.3, 1.7 and 1.9 mm over the same hours. A median, so one outlying
model can neither carry an hour nor empty it.

The members are ECMWF (native 9 km, hourly), ICON, GFS, UKMO, Météo-France and
GEM everywhere, plus KNMI's and DMI's own models over Europe. Outside their
domains KNMI's and DMI's products return ECMWF's series byte for byte, so
identical members are counted once. ECMWF's 0.25° product, JMA and CMA are left
out as three-hourly series spread across hours; BOM's and KMA's globals return
empty.

### Why not quarter-hourly model data

Open-Meteo serves a `minutely_15` series everywhere, and it is genuine only in a
few places. Over Berlin, inside ICON-D2's domain, the quarters vary and add up to
the hour; over Rīga they are the hour divided evenly; over New York and Tokyo
they disagree with their own hourly totals. The sub-hourly near term is radar's
job wherever radar exists.

### RainViewer: the radar source

- <https://www.rainviewer.com/api.html>. No key, no registration.
- **Attribution is required**: *"Radar by RainViewer"*, shown in About and on the
  rain map beside the OpenStreetMap credit.
- **The free tier is described as personal and educational use**; commercial or
  high-volume use is arranged case by case, and RainViewer disclaims rights in
  the underlying national radar data. This is the least settled licence position
  of any source here, recorded as a decision rather than overlooked.
- National networks composited worldwide, refreshed every ten minutes, with two
  hours of history. Tiles are cached for two days at a path unique to each sweep.
- The chart reads a three-by-three block of tiles at zoom 7, about 510 km across
  at mid-latitudes. The rain map reads a four-by-four block a zoom out, about
  1,360 km, and shows the middle three, so its projection always has observed sky
  upwind of the screen.

**The tiles are pictures, not numbers.** The rain field is read back out of the
colour scale (`RainViewerPalette`). Its ordering is established from the
stratification of storms; its absolute calibration is not, so the span is the
conventional one for a radar composite, capped at 55 dBZ where the echo stops
being rain. Radar rates are reliable in shape (where, which way, growing or not)
and approximate in magnitude, and the fusion weights them accordingly.

**"No echo" and "no coverage" are the same transparent pixel.** Most of the world
has thin coverage or none, and reading an empty pixel as dry would contradict the
models exactly where no radar can correct them. So a dry pixel counts as an
observation only when some step has echo within one motion block (about 40 km);
otherwise the models answer alone. Every step is checked, so a shower approaching
from beyond the neighbourhood still counts.

## Air quality

Open-Meteo's air quality service is a separate host backed by Copernicus CAMS
(40 km globally, 11 km over Europe), behind its own `AirQualitySource` so a
weather provider failing cannot take it down. Coverage is global.

There is no AQI. National indices turn the same micrograms into different
verdicts, so Wetter reports concentrations and bands PM2.5 against the WHO's 2021
global guidelines: the guideline value and its four interim targets. The band is
judged on a 24-hour mean, as the guideline is defined (`past_hours=24`); the
current hour is shown as the number. Values are cached in memory for 30 minutes
and never persisted.

## Other services

- **Open-Meteo archive** (ERA5 reanalysis): a decade of daily history per place,
  fetched about once a month, for the Month page's normals and the local hazard
  thresholds.
- **Open-Meteo geocoding**: place search by name, returning an IANA time zone and
  an elevation with each place.
- **NOAA/NWS Aviation Weather Center** (<https://aviationweather.gov>): METAR
  reports near a place, used only to score past forecasts and learn a local
  temperature bias. Nothing from them is shown.
- **Photon** by komoot (<https://photon.komoot.io>): the name of a point placed
  on the map or typed as coordinates, asked once the map settles. Data
  © OpenStreetMap contributors.
- **OpenStreetMap tiles** (<https://tile.openstreetmap.org>): the basemap for the
  pin picker and the rain map. Only tiles in view are requested, under the app's
  User-Agent, with the credit on the map, as the tile usage policy asks.

## Debugging a decision

In debug builds the router logs its ranking and the reason for each score:

```text
WeatherProviderRouter  ranking for 56.9, 24.1
WeatherProviderRouter    met-norway: 87.3: Covers this location; Regional source
                         for the Nordic region; Supplies most variables Wetter
                         uses; Resolution about 2.5 km here
WeatherProviderRouter    open-meteo: 78.6: Covers this location; Supplies every
                         variable Wetter uses; Resolution about 11.0 km here
```

Coordinates are rounded to one decimal before they reach the log.
