# Reliability run — 10 September 2026

The run of `test.md`. Eighteen places, live provider data, a decade of ERA5, an
independent almanac, and the verification record off a real phone.

**Nine findings. Seven fixed, two recorded.** Three of them were wrong numbers on
screen with no symptom to notice them by.

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
- **Offline and provider-failure behaviour** (G1, G2) — not exercised in this run.
- **Cross-surface consistency with the widget** (D5, E1) — the app's own surfaces
  agree; the widget bitmap was not diffed against the chart.
