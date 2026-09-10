# Reliability run — 10 September 2026

The run of `reliability-testing.md`. Eighteen places, live provider data, a decade of ERA5, an
independent almanac, and the verification record off a real phone.

**Eighteen findings. Seventeen fixed, one recorded.** Five were wrong numbers on
screen with no symptom to notice them by; two were the app nagging or lying about
a control; one was a warning
system that would have been switched off within a week in half the climates on
earth.

---

## Findings

### 1. Sunrise and sunset were wrong, by up to nineteen minutes a day — FIXED

**Silent.** `SolarTime` used the low-precision Fourier series that circulates as
"the NOAA equations". Measured against Open-Meteo's independent implementation at
all eighteen places, it failed at six of them:

| place | latitude | sunrise | sunset |
|---|---|---|---|
| Rīga | 57.0 | ok | +4 min |
| Yakutsk | 62.0 | ok | +5 min |
| Reykjavík | 64.1 | ok | +5 min |
| Tromsø | 69.7 | ok | +7 min |
| Longyearbyen | 78.2 | −6 min | +13 min |

Always too long a day, at both ends, growing with latitude — the fingerprint of a
declination error rather than a clock or timezone one. Localised by
reimplementing both formulas and differencing them against the full algorithm:
the series is accurate at the solstices and **wrong by 0.43° at the equinoxes**,
which put the March 2026 equinox nearly a day out. Near a grazing sun that
becomes minutes.

Fixed in two parts: the full NOAA solar position (Julian century, equation of
centre, nutation, true obliquity), and then a second pass that recomputes the
position *at* the time the first pass produced, because declination moves 0.4° a
day and Longyearbyen's sunrise is eight hours from noon.

**Result: every place now within 76 seconds, most under 40.** Longyearbyen went
253 s → 69 s. Pinned by `SolarTimeReliabilityTest`.

### 2. The rain rate read zero in every sub-hour timezone — FIXED

**Silent, and the worst of the run.** Found on the emulator, not in a test:
Kathmandu at 21:54 local showed `0.0 mm/h` on the rain tile while the dial beside
it said `Drizzle` and the hour covering the reader held 0.4 mm.

`window()` opened by truncating the clock to a whole **UTC** hour. Kathmandu is
UTC+05:45, so its rows land at :15 past the UTC hour; truncating landed *before*
the row covering now, the filter dropped it, the window opened on the next hour,
and `at(now)` fell off the front into an elvis default of `0.0`.

Affects every zone offset by half or three quarters of an hour — India, Nepal,
Iran, Afghanistan, Myanmar, Newfoundland, the Chatham Islands, Lord Howe, central
Australia. India alone is 1.4 billion people.

`WeekPage` had the same truncation, dropping the current hour from an expanded
day's strip.

Fixed with `hourCovering`, which takes the start from the series rather than the
clock — identical behaviour on whole-hour zones. Verified on device: `0.0 mm/h` →
`0.4 mm/h`. Pinned by `SubHourZoneTest`, which walks all ten sub-hour zones.

### 3. Most of Europe had a cold danger level it could never reach — FIXED

**Visible.** Printing the effective hazard bar at all eighteen places for January
and July showed the cold *danger* clamped to −25 °C nearly everywhere, because
`leastDanger` was the absolute warning. Reykjavík warned at −17.6 and could not
be in danger until −25, which it has essentially never been; Ushuaia warned at
−12 with the same unreachable danger. Berlin at −20 °C — the case that started
this — read as merely a warning.

Fixed by clamping cold's danger to freezing instead, so a place reaches danger at
its own hardest day in a decade. Deliberately **not** symmetric with heat: a body
is a body and the heat index bands are physiological, while cold harms through
clothing, housing and roads, all built to local norms. Berlin at −20 is now
DANGER; Rīga at −20 is WARNING. Pinned by two tests in `HazardsTest`.

### 4. RainViewer's index was fetched twice per refresh — FIXED

**Speed.** `latestSweep()` fetched it, then `recentFrames()` fetched the same
document again milliseconds later. Measured at 122 ms a call. Now held for one
minute, against a service that publishes every ten.

### 5. Radar frames were fetched one after another — FIXED

**Speed, and the largest.** Each frame's nine tiles were fetched in parallel and
then the frames themselves were awaited in turn, because `map` is sequential.
Three sweeps cost three round trips; the catch-up case asks for thirteen and cost
thirteen. They have no dependency on each other.

Now all frames run together, bounded by a nine-permit pool so the burst on a
volunteer service is capped by requests-in-flight rather than by sweeps-requested.
**Saves roughly 260 ms on an ordinary refresh and about 1.6 s when catching up.**

### 6. The chart waited up to a minute for data the app already had — FIXED

**Speed, and the one that was actually noticed.** The fused timeline was rebuilt
on a 60-second timer, so a sweep collected by the background worker sat unused
for an average of thirty seconds while the screen was open — exactly the symptom
reported.

The timer was doing two unrelated jobs: re-anchoring the leading edge as it drifts
into the past (a real reason for a clock) and noticing new data (not a reason for
a clock — the database knows the instant it lands). Now `radar_series` is
observed through Room and the timeline rebuilds on a new sweep *or* the slow tick.

### 7. The ensemble request relied on the service's default units — FIXED

**Latent.** Every other Open-Meteo request states `temperature_unit`,
`precipitation_unit` and `wind_speed_unit`; the ensemble request stated none.
Correct today because the defaults happen to match. A default is a promise nobody
made, and a silent switch would not fail — it would make every ensemble spread
three times too wide.

### 8. An in-memory cache key was locale-dependent — FIXED

**Latent.** `AirQualityRepository.keyOf` used `"%.2f,%.2f".format(...)`, which
takes the device's locale, so on a Latvian phone it produced `56,95,24,11`.
Consistent within a run, so nothing was broken; it would break silently the day
that key was written to disk. Every other key in the package already used
`Locale.ROOT`.

### 9. Climatology is keyed to about eleven kilometres — RECORDED, NOT FIXED

**Latent, needs a judgement.** The forecast cache keys at four decimals (11 m) and
the nowcast at three (110 m), but climatology keys at **one** (11 km). That was
harmless when normals only tinted the month page. They now set the hazard
thresholds, and in mountainous terrain 11 km can be a thousand metres of
elevation — a valley's climate imported to a village above it. Written into
`notes.md` rather than changed, because tightening the key costs an archive fetch
per place and the right precision is a measurement nobody has taken.

### 10. The verification loop threw away the location's height — FIXED

**Silent.** `LocalEstimate` takes an elevation and uses it twice: it brings each
station's reading to that height at the standard lapse rate, and it prefers
stations at a similar height. The verification path passed `null` while
`location.elevationMetres` sat unused on the line above.

So the "observation" for a town in the hills was the temperature of the
aerodrome on the plain below it, and the whole gap went to `BiasCorrection`,
which cannot tell an elevation difference from a model that runs warm. It would
learn the gap and subtract it from every temperature on screen — making the app
wrong at exactly the places terrain makes it hard.

The extremes were safe by accident: a gap over about 770 m exceeds
`MAX_TEMPERATURE_OFFSET` and the correction is refused outright. The dangerous
band is underneath it — 400 m is 2.6 °C, large enough to matter and small enough
to be believed. Pinned by `ElevationReliabilityTest`.

### 11. A missing-data sentinel became a DANGER warning — FIXED

**Silent.** Found by the degradation tests: `Double.POSITIVE_INFINITY` passed
straight through a threshold into a hazard peak.

JSON has no infinity literal, but that is not the real exposure — `-9999` and
`999` mean "missing" across a great deal of meteorology and are perfectly
ordinary numbers as far as a parser is concerned. Either one reaching a
comparison raises a **danger**, the strongest thing this app can say, and pushes
a notification about it.

Readings outside what the planet has ever done are now treated as absent rather
than as extreme, with the bounds set at the records plus a wide margin — the job
is to reject `-9999`, not to second-guess a forecast. Tested in both directions:
sentinels raise nothing, and Vostok's −89.2 °C and an 80 m/s hurricane gust still
do.

### 12. The notification permission was asked for over an empty screen — FIXED

**Visible.** The rule was "asked once, over a screen that is already showing the
weather", and that was a comment rather than a condition — the request fired
from the activity as soon as the composition ran. On a warm cache the forecast
won by a frame or two, so it looked right. A first run in aeroplane mode put the
system dialog over an empty black screen: the exact prompt-about-nothing the
comment claimed to avoid. Now gated on a forecast actually being present, and
verified in both directions on device.

### 13. It would have asked again on every launch — FIXED

**Visible, and the worst of the interaction findings.** The "asked" flag was a
`rememberSaveable`, which does not survive the app being closed. Somebody who
declined would be asked again on the next cold start, and the next, until they
gave in — the platform's two-refusal limit is not a defence, because two is
already one more than nobody asked for.

The platform's own record is now the gate:
`shouldShowRequestPermissionRationale` is true exactly once a permission has been
refused and not yet refused for good. Nothing is stored, and it cannot drift out
of step with the system. **Verified end to end: fresh install asks once, "Don't
allow" tapped, then three cold launches with no dialog at all.**

### 14. And then there was no way back — FIXED

Asking only once creates the opposite problem: somebody who declined in March
and wants warnings in November had a switch in Settings that read "On" while the
platform dropped every notification. A control that lies.

Turning the row on now does whatever is still needed — requests the permission if
that is still possible, and goes to the system's own notification page for this
app if it is not. Verified on device: with the permission refused, tapping "On"
opened the system dialog, and granting it there left the app able to notify.

### 15. A dangerous climate meant a notification every other day — FIXED

**The most valuable test of the run, and it could not have been found any other
way.** F4 replays a year of ERA5 through the hazard logic at every place. Unit
tests say the thresholds are right; only this says what they *do*.

| place | days marked | of which danger |
|---|---|---|
| Everest | 96.7% | 206 cold, 138 wind |
| Doha | 44.1% | 129 heat |
| Yakutsk | 31.0% | 104 cold |
| Phoenix | 21.1% | 64 heat |
| Rīga | 4.9% | none |
| Quito | 0.0% | none |

The temperate places are exactly right, so the thresholds themselves are sound.
What was wrong is the rule that an absolute danger always wins — introduced
deliberately, and defensible for the *dial*, because forty-one degrees of heat
index is dangerous to any body. It bypassed the local suppression entirely, for
precisely the places that needed it.

Fixed by separating the two questions that had been collapsed into one: **the
dial says what is dangerous, and the notification says what is unusual here.** A
warning must now also clear the place's own 95th percentile before it reaches a
phone. Every one of those days still carries its mark on the dial.

Re-measured after the fix, counting notifications rather than marks:

| place | before | after |
|---|---|---|
| Everest | 353 | **13** |
| Yakutsk | 113 | **14** |
| Phoenix | 77 | **28** |
| Doha | 161 | **72** |
| Rīga | 18 | 18 |
| Tromsø | 29 | 27 |

All eighteen places have now been replayed. The full picture, as a share of days
that would reach a notification: Quito 0%, Null Island 1.4%, Yakutsk 3.8%,
Everest 3.6%, Rīga and La Rinconada 4.9%, Apia 5.2%, Kathmandu 5.5%, Chatham
6.8%, Tromsø 7.4%, Phoenix 7.7%, Longyearbyen 7.9%, Lord Howe 8.2%, Ushuaia 9.0%,
Kolkata 10.7%, Reykjavík 11.5%, Nuku'alofa 16.4%, Doha 19.7%. The median is about
seven per cent — roughly one warned day a fortnight.

Doha and Nuku'alofa remain the highest and are recorded rather than tuned away:
both are genuinely hard climates, the figure counts hazard kinds rather than
days, and the right rate is the tunable already noted in `notes.md`.

### 16. A summit and its valley shared one climate — FIXED

**Silent, and confirmed rather than suspected.** Finding 9 recorded the
climatology key at one decimal (~11 km) as a worry and left it, because the
right precision was "a measurement nobody has taken". This is that measurement.

Chamonix in the valley and the Aiguille du Midi above it are 4.5 km apart and
round to the same key, `45.9,6.9`. The forecast model has no difficulty telling
them apart — different grid cells, 1034 m against 3597 m, and **12.2 °C against
−1.9 °C at the same moment**. The climatology handed both whichever set of tails
was fetched first.

That was tolerable while normals only tinted the month page. It is not tolerable
now they set the hazard thresholds: a summit would be judged against a valley's
cold bar, or a valley against a summit's, and the gap between those two is the
better part of fifteen degrees.

Now keyed to two decimals — about 1.1 km, finer than any global model's grid, and
the same identity a place already has in the verification store and the
air-quality cache. The cost is bounded: a rebuild is a month apart and a phone
holds a handful of places, not a continuum.

### 17. "It is raining" was a coin flip — FIXED

**Reported from use, then measured.** Rīga had weeks of cloud without much rain
and the bar under the chart said it was raining almost continuously.

Checked against aerodrome reports — 1295 hours at ten airports, model hours
paired with the METAR for the same hour:

| what the model said | actually raining at the station |
|---|---|
| trace, 0.1–0.5 mm/h | **53%** — a coin flip |
| light or above, ≥0.5 mm/h | 79% |

The app drew both as rain because the wet/dry line was the *measurable*
threshold. The scale already had the right word for the lower band and its own
comment already said what it was: `TRACE` — "damp ground, no more".

So there are two bars now, at deliberately different heights. **The curve draws
anything measurable**, because something is falling and the chart is a picture of
the data. **The words and the marks wait for rain.** Wrong claims fell by half —
43 to 20 — and the app went from being right 70% of the time when it says rain to
79%.

The higher bar was then carried to every surface that *claims* rain, which took
two passes: the bar under the chart had a second route to "raining now" through
the current rate, so the first fix only covered half of it. The claims are now
the spell sentence, that bar, the marks in the hour strip, the week's day icon
and its wet-hour count. Verified on live Rīga data with the rate sitting exactly
on the old threshold: 0.1 mm/h, dial reading "Partly cloudy", bar reading "Rain
starts Sunday at 14:00" where it would previously have said it was raining.

Two things deliberately keep the lower bar, and the difference is the point:

- **The curve**, which is a picture of the data. A drizzle is not concerning and
  the line lifting off the axis is signal enough to expect something.
- **The word on the dial**, which comes from radar rather than a model and whose
  answer in that band is "drizzle" — the accurate name for a tenth of a
  millimetre out of an overcast. The 53% figure was measured on model hours and
  does not transfer to an observation. An existing test caught this being
  over-reached and was right to.

Things that were tried and rejected, because the measurement said so:

- **Raising the measurable threshold** instead. Worse: at 0.5 mm/h the app
  catches 44% of real rain rather than 60%, and the curve loses hours that
  genuinely had something in them.
- **A humidity or dew-point gate**, on the sound physical reasoning that light
  precipitation into dry air evaporates before it lands. It buys almost nothing:
  skill +0.557 to +0.559. The diagnostic shows why — light hours that reached the
  ground averaged 2.72 °C of dew point depression and those that did not averaged
  3.39 °C. The distributions overlap almost entirely. Virga is real and it is not
  what is going on here.
- **The model's own probability** on top of the new bar: 79% to 81%, on three
  claims out of ninety-four. The two are correlated, so the rate bar has already
  done the work, and machinery that does not earn its place does not go in.

### 18. The month page had two definitions of wet — FIXED

Found while tracing who consumes the wet/dry decision. `MonthPage` asked
`PrecipitationIntensity.ofRate` — whose constants are millimetres per **hour** —
about `day.precipitationTotal`, which is millimetres over a whole **day**. So a
forecast day with a tenth of a millimetre spread across twenty-four hours washed
the square.

That is the rate-for-accumulation confusion this project has now made three
times, and here it had a second cost: the climatology squares on the same grid
use the conventional rain-day line at a millimetre, so the forecast half and the
normals half of one page were answering different questions, and the forecast
half marked far more wet days than the decade beside it. Both now use
`ClimateNormals.WET_DAY_MM`.

---

## What passed

- **Timezones and day boundaries** (B1–B5) — all 18 places. Sub-hour, quarter-hour,
  date-line, no-DST. The provider's daily block agrees with its own hourly rows to
  0.00 °C everywhere.
- **The stitcher's majority rule** (D2) — MET Norway's far days are covered only by
  six-hourly steps, and deriving a daily extreme from four samples instead of 24
  narrows the range by 0.92 °C on average and understates the peak by up to 3.3 °C
  at Kathmandu and Kolkata. Measured by subsampling one model against itself, so
  it is sampling error and not model disagreement. It does **not** reach the
  screen: MET's hourly ends around day 3, so the extension supplies those days and
  its hourly-derived rows win the majority vote. Only reachable if the extension
  request fails, which is a documented degradation.
- **Moon phase** (F5) — six eclipses, which are independently known phases. All
  within the ±14 h the mean synodic model admits to.
- **Unit conversions** (A1, A2) — against the defining constants. Differences
  convert as differences.
- **Rounding boundaries** (E2) — −0.4 °C does not render as "−0°"; an absent
  reading is absent and a real zero is zero; inches keep two decimals.
- **Hazard bar sanity** (F2, F3) — all 18 places, January and July. Every tail
  correctly ordered, every clamp holding, no absurd bars.
- **Compression** — gzip is already active end to end, 4.7× on the 16-day forecast
  (38.7 KB → 8.2 KB). Nothing to win there.

- **Every threshold is in the unit its comparand arrives in** (A4). Every
  `TRACE_MM_PER_HOUR` comparison is against a rate; `WET_DAY_MM` is an
  accumulation and carries its own name and unit so the two cannot be confused.
- **No hardcoded unit string anywhere outside the formatter layer** (A5).
- **Extreme values and the other plate** (E4, E5) — Phoenix at 96 °F on Pure
  White, in imperial: two decimals on inches so an ordinary shower does not round
  to zero, dark status-bar icons on the light ground, the amber hazard mark as
  the only saturated colour, no overflow.
- **Offline with a warm cache** (G1) — the cached forecast is shown in full. No
  spinner that never ends, no blank, no error state clobbering a reading that is
  still the best answer available.
- **Missing and absurd input** (G3, G4) — an empty series, a forecast entirely in
  the past, a single hour, null temperature, null gust, null precipitation, NaN,
  infinity, ±1000 °C. Nothing throws, and nothing becomes a zero.
- **The shared intensity axis** (D5) — `RainCurveBands` holds its 0..1 bounds at
  every edge including negative and `Float.MAX_VALUE`, its band edges keep their
  order, and the short-track lift leaves both ends of the light band as fixed
  points so every band is the same height on both surfaces.
- **The widget's drawn geometry, on a device** (D5, E1) — the one part of the app
  that cannot be checked on the JVM, since `android.graphics`' desktop stubs draw
  nothing and a unit test of it would pass against a blank bitmap. Four
  instrumented tests read the rendered pixels: the three levels come out equal
  thirds on the glass, the curve climbs strictly across every band, and a drizzle
  steps clear of the floor while dry stays on it. Found by rendering two strips
  and diffing them rather than by matching colours — antialiasing means a drawn
  pixel is a blend of the mark and the plate, and the first version of this test
  failed on every assertion for exactly that reason.

- **Hourly sums against daily totals** (C4) — exact to 0.00 mm at all eighteen
  places. Nothing is being read as an accumulation where it is a rate, or the
  reverse.
- **The radar-to-model hand-over is not a cliff** (D1) — with radar at 8 mm/h and
  the model dry, the join spreads the whole disagreement across the window rather
  than showing it in one step, and it runs one way without bouncing. Two sources
  that agree produce no step at all.
- **Every surface agrees which hours are wet** (C5, E1) — the chart band, the
  intensity scale and the trace threshold give the same verdict on both sides of
  the boundary, so the strip cannot be empty under a day headed "rain".
- **A climate normal can never be drawn as a forecast** (D3) — `DayNormal` and
  `DailyWeather` are separate types and nothing converts between them, so the
  month page's fallback is structural rather than a convention.
- **Provider failover** (G2) — already covered: 500s, timeouts, rate limits,
  malformed responses, offline, provider rest and recovery, and a failed
  extension still yielding the forecast that worked.
- **The fused tail** (D4) — covered: nothing is claimed beyond either source, and
  the series stops where the model does.
- **The daily condition and the six-hourly rate** (C3) — covered, including the
  regression guard for the bug that once marked a day as rain from a six-hour
  total compared against an hourly threshold.
- **Verification records match the hour they describe** (H1) — covered.
- **The provider's zone wins over a stale saved one** (B7) — seeded Tokyo's
  coordinates with a deliberately wrong `Europe/Riga` zone; the forecast came
  back carrying `Asia/Tokyo`, and every rendering path — the plate, the week, the
  widget, the hazard scan — reads the forecast's zone rather than the saved row's.
  The saved row can hold a stale zone if the correction at pin time is missed, and
  nothing displays it.

## Plausible, not proven

- **The learned bias correction helps** (H4). Replayed walk-forward with no
  leakage over the phone's own records: MAE 1.313 → 1.250 °C, a 5% improvement.
  But that is 24 distinct hours at one place over one week. Worth re-running after
  a season.

## Blocked

- **Thunder relative to place** — cannot be measured from the archive. Its daily
  `weather_code` is one representative code per day and storm hours always lose to
  the prevailing rain: across eight cities including Kolkata and Rīga in July it
  reports a thunder share of *zero everywhere*. Needs hourly codes, about four
  times the payload of the whole archive fetch. In `notes.md`.
- **Provider failover under a 5xx** (G2) — the ranking and health store are
  covered by their own tests, but a live failure was not induced in this run.
- **The widget bitmap against the chart** (E1) — they share `RainCurveBands` and
  that is now tested, but the two rendered surfaces were not diffed pixel for
  pixel.
- **Thunder relative to its place.** The archive cannot measure it: its daily
  `weather_code` is one representative code per day and storm hours always lose
  to the prevailing rain — across eight cities, including Kolkata and Rīga in
  July, it reports a thunder share of zero everywhere. Counting real storm hours
  needs the hourly codes, roughly four times the payload of the whole archive
  fetch, for one boolean. Deliberately not shipped as a check that silently never
  fires.
- **The widget bitmap against the chart, pixel for pixel** (E1). They share
  `RainCurveBands` and that geometry is now tested from both ends, but rendering
  the two surfaces and diffing them needs an instrumented test this run did not
  set up.
