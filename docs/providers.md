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

## Radar, and who does the nowcasting

Radar is not another forecast provider and is deliberately not routed as one. It
answers a different question.

A numerical model predicts how the atmosphere will evolve. It has to be *told*
what is currently overhead, and by the time a run reaches a phone that state is
already an hour or two old — which is why models are weakest about the next
twenty minutes and why "is it about to rain on me" is the question they answer
worst. Radar is the opposite: it observes precipitation that exists, right now,
at roughly a kilometre. It cannot tell you about tonight at all.

So Wetter does its own nowcasting rather than asking anyone for one. The
observed field is projected forward — motion estimated by block matching,
carried on backward trajectories, with growth and decay on a saturating leash —
and the result is blended with the models by lead time. This became the only
option available as well as the right one: RainViewer withdrew their own
forecast frames at the start of 2026, so past the present moment there is
nothing to fetch.

### Where the handover happens

**Radar is the immediate layer.** Not because it stops being a prediction — it
does not. Carrying a field forward is a forecast, and it rests on an assumption
that gets worse every minute: that the rain keeps doing what it was measured
doing. The difference is what the prediction is *built on*.

A model predicts from a simulated atmosphere initialised hours ago. The nowcast
predicts from what is in the sky right now — where the rain actually is, how
dense it actually is, which way it was actually observed to move. Both are
guesses about the future; one of them starts from a look out of the window.

That is the whole claim, and it is a narrow one. It is also why the claim
expires: the shorter the extrapolation, the more of the answer is observation
and the less is assumption. An hour in, most of it is still the observation.

**So inside an hour the radar decides.** It is not one opinion of two there; it is
the only source that has actually looked. It sees where the cloud is, how dense
it is and which way it is moving, and it looks again every ten minutes. A
model's next hours were computed hours ago from an analysis older still, and
nothing about them improves as the hour approaches — whatever was sent is what
you get. We do not need a report from half an hour ago to tell us there is rain
overhead when we can see it.

| Lead | Radar | Why |
|---|---|---|
| 0 – 1 h | 95% | The window where the sky is being watched rather than predicted. |
| 1 h+ | confidence × 95% | The field has been carried further than the motion behind it justifies. |
| 3 h+ | in practice <25% | Extrapolation is spent. The model is all there is. |

An hour rather than two, and the reason is the method. Underneath is advection:
the field is assumed frozen and only carried along. Over ten minutes that is
very nearly true; by an hour it is strained; by two the cell that was coming may
have rained itself out while something new built overhead that no amount of
looking at old frames could have shown. Physics can model formation and decay,
which is the one thing advection cannot — so the hand-over belongs where the
assumption stops paying.

Past the window the share is **not** read off a table at runtime. It is derived
from the nowcast's own confidence, the product of two independent things: how far
radar can usefully see at all, and how well *this* sweep matched the last one. A
fixed table is right on average and blind to the second — and the second is what
separates a good estimate from the one over Dublin that came out fifty degrees
wrong on a thin, structureless field.

**Confidence is not raised to match.** Taking the value from the radar says where
the number came from; it does not claim the number is certain. A reading two
hours out is less certain than one ten minutes out however it was arrived at, and
the confidence carried alongside each point still says so. That is where the
sharpening lives now: as a shower approaches, the answer does not become *more
radar*, it becomes more certain.

The cost of this is deliberate and worth writing down. A projection built on
almost no echo — the Dublin case — leads inside the window where it used to hand
back to the model, reporting a low confidence while it does. The judgement is
that a thin look at the real sky beats a fresh look at an old model. Shortening
the window from two hours to one narrows that exposure: the case where the
motion estimate is worth least is also the case where it is trusted alone for
the shortest time.

Two properties of that split are worth keeping:

- **The present moment does not depend on the motion estimate.** A flat rain
  field gives a poor match, but the sweep still shows rain that is falling. Only
  the projection needs the motion — which is why a featureless field is still
  worth believing about now, and why its confidence falls away with lead even
  though its share no longer does.
- **The model is never switched off.** Radar sees precipitation, not the sky. It
  misses what falls below the beam, misses snow it cannot detect, and cannot see
  past its own coverage. A standing model share means those gaps degrade the
  answer rather than emptying it.

### Current radar source

#### RainViewer — global composite

- <https://www.rainviewer.com/api.html>
- No key, no registration.
- **Attribution is mandatory** under their free terms: *"Weather data by
  RainViewer"* with a link back. Shown in About.
- **Their free tier is described as personal and educational use.** Commercial or
  high-volume integration is arranged case by case, and they explicitly disclaim
  rights in the underlying national radar data. This is the least settled licence
  position of any source here and is a deliberate, recorded decision rather than
  an oversight — see the [terms-of-service
  checklist](#terms-of-service-checklist).
- Composites national networks worldwide, refreshed every ten minutes, thirteen
  frames of history. Coverage over the Baltic is real and continuous; MET
  Norway's nowcast reports *no coverage* for Rīga because that is its own Nordic
  composite, not this one.
- Responses are cached hard and a block of nine tiles is about 45 KB per cycle.

### What the tiles cannot tell us

The API serves **pictures, not numbers**. There is no numeric endpoint, so the
rain field is read back out of the colour scale, and two things follow.

The *ordering* of the scale is established: translucent tan at the faint end,
then opaque cool colours, then warm, with magenta above. Storms are stratified,
so intensity should fall with distance from a core and each family should be
enclosed by the one below it — both hold across a large sample of central
Europe, which is what pins the order down.

The *absolute calibration* is not established. Fitting the scale against
Open-Meteo's precipitation gave a correlation of only 0.37 and implied half a
millimetre an hour at full scale, which is nonsense for a saturated echo: an
11 km forecast quantised to tenths is too coarse a ruler for kilometre
observations. The span used is therefore the conventional one for a radar
composite, capped at 55 dBZ where the echo stops being rain — read literally,
the top of the scale came out at 1300 mm/h, a rain relation applied to hail.

**So radar rates here are reliable in shape and approximate in magnitude.**
Where the rain is, which way it is going and whether it is growing are all
trustworthy; the exact millimetres are not. The fusion weights them accordingly,
and the two constants to revisit are `MIN_DBZ` and `MAX_DBZ`, once the
verification store holds enough observed rain to fit against something measured.

## Air quality

Not weather, and not fetched with it. Open-Meteo's air quality service is a
separate host backed by different models — Copernicus CAMS, 40 km globally and
11 km over Europe — so it lives behind its own `AirQualitySource` for the same
reason radar does: it answers a different question on its own cadence, and a
weather provider failing must not take it down with it.

Coverage was checked rather than assumed. Riga, Nairobi, Delhi, São Paulo,
Sydney and Longyearbyen all return values, so this is not a European feature
that degrades elsewhere.

### Why there is no AQI

An air quality index is a national instrument. The European AQI, the US AQI,
India's NAQI and China's take the same micrograms and return different verdicts,
because each encodes what its own regulator decided was acceptable. Open-Meteo
will compute the European and US indices anywhere on Earth, which is precisely
the trap: a Delhi reading judged by European thresholds is not a measurement, it
is an opinion imported from 6,000 km away.

So Wetter reports the concentration and bands it against the one threshold set
that is not national — the WHO's 2021 global air quality guidelines. The bands
are the guideline value and its four interim targets, which exist because most
of the world is a long way above the guideline and needed a ladder rather than a
pass/fail line. The same air reads the same everywhere.

### The 24-hour mean

The WHO's PM2.5 threshold is defined on a 24-hour mean, so a 24-hour mean is
what gets banded. `past_hours=24` costs one parameter on a request already being
made, and it is the difference between "the air here is bad" and "somebody had a
bonfire an hour ago". The current hour is still shown as the number; the mean
only decides the word. Where fewer than 18 of the 24 hours came back, no mean is
claimed and the current hour stands in.

Nothing is persisted. The source publishes hourly values, and unlike a forecast
there is nothing here worth showing stale — "the air was clean when you last
opened this" is not an answer to "is the air clean". A 30-minute in-memory cache
is the whole of it.

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
