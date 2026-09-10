# Testing whether Wetter's readings can be trusted

A reliability plan for the numbers this app puts on a screen — not a unit-test
checklist. The unit tests already cover whether functions do what they say; this
asks the harder question, which is whether what they say is *true*, and whether
it stays true at the edges of the world and the calendar.

## What "reliable" means here

`philos.md` claims precision as the whole point of this app, and design rule 8
says the machinery stays hidden. Together those make an unusual promise: the
reader is shown a number with no provenance, no confidence interval and no
source badge, and is expected to act on it. There is no "well, it said 60%".

So the bar is: **every number on screen is either correct, or absent.** A wrong
number is worse than a blank, because a blank is honest.

Five ways that promise can break, in rough order of how quietly they do it:

1. **Silent unit errors.** A gust arriving in km/h and compared against a m/s
   threshold is out by 3.6× and looks entirely plausible. Nothing crashes,
   nothing logs, and a warning simply never fires. This class has already bitten
   this project twice — a hardcoded `C` on the dial, and `mm/h` on the rate.
2. **Frame-of-reference errors.** The right number attached to the wrong hour,
   the wrong day, or the wrong place. Timezones, day boundaries, cache keys.
3. **Aggregation errors.** A rate treated as an accumulation, a 6-hour block
   compared against an hourly threshold, a day's extremes taken over the wrong
   set of hours. Also already bitten this project twice.
4. **Seam errors.** Two sources fused, and the join visible as a step that no
   weather produced — radar into model, model into extension, forecast into
   climatology.
5. **Presentation errors.** The number is right in the domain and wrong on the
   glass: rounded into a lie, formatted in the wrong unit, or disagreeing
   between the dial, the hour strip and the widget.

## Ground truth, and its limits

There is no oracle. What there is:

- **METAR aerodrome reports** (`aviationweather.gov`) — actual instruments and
  actual observers, hourly, at airports. The best available truth for
  temperature, wind and whether something is falling. Already used by the app's
  own verification loop. Limits: airports only, no rainfall amount.
- **ERA5 reanalysis** (Open-Meteo archive) — the past, modelled but assimilated
  against observations. Good for "is this value plausible for this place at this
  time of year". Not truth for a specific hour.
- **Cross-provider agreement** — MET Norway against Open-Meteo. Not truth: two
  models can agree and both be wrong. But *disagreement* is a reliable signal
  that at least one of them is, and a large one at a specific place is worth
  investigating.
- **Internal consistency** — the same quantity computed two ways, or shown on
  two surfaces, must match. This needs no external truth at all and catches the
  seam and presentation classes outright.

Where no ground truth exists, the test is for *self-consistency and
plausibility*, and it is labelled as such rather than dressed up.

## The place matrix

Chosen so that every row breaks a different assumption. Coordinates are what
gets fed to the app.

| # | Place | Lat, lon | What it tests |
|---|---|---|---|
| P1 | Rīga, LV | 56.95, 24.11 | The home case. Everything should be right here. |
| P2 | Kolkata, IN | 22.57, 88.36 | UTC+5:30 — half-hour offset. Monsoon. |
| P3 | Kathmandu, NP | 27.72, 85.32 | UTC+5:45 — quarter-hour offset. Altitude 1400 m. |
| P4 | Chatham Is, NZ | −43.95, −176.55 | UTC+12:45. Across the antimeridian from its own country. |
| P5 | Longyearbyen, SJ | 78.22, 15.63 | Polar night/day: no sunrise for months. |
| P6 | Ushuaia, AR | −54.80, −68.30 | Southern hemisphere, seasons inverted. |
| P7 | Quito, EC | −0.18, −78.47 | Equator: ~12h days year round, no seasons. 2850 m. |
| P8 | Phoenix, US | 33.45, −112.07 | Imperial units. Arizona does not observe DST. Extreme heat. |
| P9 | Nuku'alofa, TO | −21.14, −175.20 | UTC+13. West longitude, east of the date line. |
| P10 | Apia, WS | −13.83, −171.77 | UTC+13, jumped the date line in 2011. |
| P11 | Yakutsk, RU | 62.03, 129.73 | Extreme cold climate. Tests the cold end. |
| P12 | Doha, QA | 25.29, 51.53 | Extreme heat + humidity. Tests the heat end. |
| P13 | Reykjavík, IS | 64.13, −21.90 | Extreme wind climate. No DST, UTC+0 year round. |
| P14 | Lord Howe Is, AU | −31.55, 159.08 | UTC+10:30, and a **30-minute** DST shift — the only one. |
| P15 | Null Island | 0.0, 0.0 | Open ocean. Should degrade honestly, not invent. |
| P16 | Mount Everest | 27.99, 86.93 | 8849 m. Extreme elevation correction. |
| P17 | Tromsø, NO | 69.65, 18.96 | MET Norway's home turf — its best data, and polar. |
| P18 | La Rinconada, PE | −15.18, −69.45 | 5100 m, highest inhabited place. |

## The tests

### A. Units — the silent class

**A1. Every unit conversion, against known values.**
0 °C = 32 °F, 100 °C = 212 °F, −40 °C = −40 °F. 1 m/s = 3.6 km/h = 2.23694 mph =
1.94384 kn. 25.4 mm = 1 in. Verified as *readings*.

**A2. Differences converted as differences, not as readings.**
A 1 °C difference is 1.8 °F, not 33.8 °F. The verification bias and any delta
must use `TemperatureUnit.difference`. Regression guard: this was got right once
and the formatter that used it has since been deleted, so nothing currently
exercises it.

**A3. Every outbound API request states its units explicitly.**
Grep every provider for the unit parameters it sends. A request that relies on a
service's default is a bug waiting for the service to change its mind. The
archive gust variable was added without `wind_speed_unit` in a first draft and
would have been 3.6× wrong with nothing to show for it.

**A4. Every threshold constant is in the unit its comparand arrives in.**
Walk each threshold in `Hazards`, `PrecipitationIntensity`, `ClimateNormals` and
confirm the value it is compared against is in the same unit. Rates against
rates, accumulations against accumulations.

**A5. No unit string is hardcoded in the UI.**
Grep for `"C"`, `"mm"`, `"m/s"`, `"km/h"`, `°` in composables outside the
formatter layer.

### B. Frames of reference — time and place

**B1. Half- and quarter-hour offsets.** P2, P3, P4, P14. Every hour label on the
dial, the hour strip and the week must land on the location's own clock. A
forecast hour at 06:00 UTC in Kathmandu is 11:45 local, and an app that rounds
to the hour will show 11:00 or 12:00 — both wrong.

**B2. The day boundary.** For every place, the set of hours the app assigns to
"today" must be exactly the hours whose *local* date is today. Checked against
`ForecastStitcher.hoursPerDay` and the week rows.

**B3. Days that are not 24 hours.** A DST transition day has 23 or 25 hours, and
Lord Howe has a 30-minute one. Daily aggregation must not assume 24.

**B4. Places that do not observe DST while their neighbours do.** P8 Phoenix,
P13 Reykjavík. A zone offset must come from the zone, never from a country.

**B5. Date-line places.** P4, P9, P10. "Tomorrow" on the far side of the
antimeridian, and a west longitude with a positive UTC offset.

**B6. Cache-key precision.** Three different roundings are in use — 2 decimals
for the forecast and verification, 3 for the nowcast, 1 for climatology. At the
equator 0.01° is 1.1 km and 0.1° is 11 km. Two saved places closer than the key
resolution share a row. Test: save two real places 800 m apart and check they do
not collide; check what 11 km does to a coastal or mountain climatology.

**B7. The location's zone versus the provider's.** A pin is saved with
`ZoneId.systemDefault()` and corrected from the forecast. Test that the
correction lands, and that nothing renders with the phone's zone in between.

### C. Aggregation

**C1. Daily extremes.** For each place, the daily high/low the app shows must
equal the max/min of the hours it assigns to that local day. Not the provider's
own daily field — the two can differ, and if they do, which is drawn?

**C2. Rate versus accumulation, everywhere.** MET Norway's `next_6_hours` is an
accumulation over six hours; `next_1_hours` is one hour. Any comparison against
a per-hour threshold must divide. Re-verified at a place and time where the
6-hour blocks are actually in use — that is beyond hour ~48 of a MET forecast.

**C3. Dominant condition.** The day icon in the week view must be derived from
the same hours the row's numbers are, and only from hourly steps where the day
has them.

**C4. Precipitation totals.** Sum of hourly rates over a day, against the
provider's daily sum. A mismatch beyond rounding means one of them is being read
as the other.

**C5. Wet-hour counting.** The hour strip marks wet hours. That mark and the
chart's curve and the day's rain figure must all agree about which hours are wet.

### D. Seams

**D1. Radar-to-model handover.** Where the nowcast ends and the model takes over,
the curve must not step. Measure the discontinuity at the join across several
places and hours; anything larger than the model's own hour-to-hour variation is
a seam artefact.

**D2. Provider-to-extension stitch.** `ForecastStitcher` gives a day to the
extension only by majority. Verify at a place where MET Norway is preferred and
runs out before Open-Meteo does.

**D3. Forecast-to-climatology, on the month page.** The square where the
forecast ends and the normal begins must not step, and must not be labelled as a
forecast.

**D4. Fusion tail.** `dropLastWhile { it.sources == 0 }` — verify no zero-padded
tail survives at any place, including one with no radar at all.

**D5. Shared intensity axis.** `RainCurveBands` is shared between the app chart
and the widget bitmap. The same forecast must produce the same band boundaries
on both.

### E. Presentation

**E1. One number, one value, everywhere.** For a given place and hour, the
temperature on the dial, in the hour strip, in the week row and in the widget
must be the same number. Any difference is either a rounding inconsistency or
two code paths.

**E2. Rounding at the boundaries.** −0.4 °C must not render as "−0°". 0.04 mm
must not render as "0.0 mm" if it is also called wet. 99.6% must not become
"100%" if the app treats 100% as certainty.

**E3. Missing data renders as missing.** Null temperature, null gust, no
sunrise (P5 in winter, P17), no UV at night. Never `0`, never `--` where a real
`0` is meaningful.

**E4. Extreme values do not overflow the layout.** −59 °C at P11, 50 °C at P12,
a four-digit pressure, a 100 mm/h rate.

**E5. The dial, the chart and the widget in both plates.** Pure Black and Pure
White, and every reading legible in each.

### F. Correctness of the derived layers

**F1. Elevation correction.** P16 and P18 are at 8849 m and 5100 m. The observed
estimate applies a lapse correction between station and place; at these
elevations that is a 50 °C correction and almost certainly nonsense. Test what
the app does and whether it should refuse.

**F2. Climatology sanity.** For each place, the tails must be ordered
(`coldExtreme ≤ coldTail ≤ medianLow ≤ medianHigh ≤ warmTail ≤ warmExtreme`),
the sample count ≥ 30, and 29 February must have a normal.

**F3. Hazard thresholds sane at every place.** Print the effective warning and
danger bar for heat, cold and wind at all 18 places for mid-January and
mid-July. Anything absurd — a heat warning below the floor, a cold warning above
freezing, a wind bar below Beaufort 8 — is a bug in the clamping.

**F4. Hazard rate.** Replay a year of ERA5 through the hazard scan at several
places and count how many days would have raised a warning. A system that fires
on 30% of days is furniture; one that fires on 0 days in a decade is dead code.
Both are failures and neither shows up in a unit test.

**F5. Moon and tide.** `MoonPhase` against a published ephemeris for several
dates; the tide state is a claim about the sun-moon geometry only.

**F6. Sunrise/sunset.** Against a published almanac for a mid-latitude, an
equatorial and an arctic place.

### G. Failure and degradation

**G1. Offline.** Aeroplane mode with a warm cache: the app must show the cached
forecast and say it is not refreshing, never a spinner forever and never a blank.

**G2. Provider rejection.** A 500 from the preferred provider must fail over,
and the router's ranking must not oscillate.

**G3. Partial data.** A provider response with null gusts, null UV, no daily
block. Every reading that depends on them must go absent, not zero.

**G4. Empty and absurd input.** No hourly rows; hours in the past only; a
forecast whose location is null-island; NaN.

**G5. First run.** Fresh install with no cache, no location, no permission, no
network. Then network. Nothing may render a wrong number in between.

### H. The verification loop itself

**H1. Records are written for the right hours** and observations attach to the
right ones.

**H2. Bias is learned from distinct hours** — regression guard on the fix that
took 201 records down to 24 hours.

**H3. Dry hours reach the record** — regression guard on the METAR fix.

**H4. The correction improves the forecast.** Replay: apply the learned bias to
past predictions and check the corrected error is smaller than the raw one. If
it is not, the correction is making the app worse and should be off.

## How the run is scored

Every test lands in one of:

- **PASS** — checked, correct.
- **FAIL** — checked, wrong, with the evidence.
- **PLAUSIBLE** — checked against no external truth; consistent and sensible but
  not proven.
- **BLOCKED** — could not be run, with the reason. Not a pass.

Findings are logged below with a severity: **silent** (wrong number, no
symptom), **visible** (wrong or missing, a reader would notice), **cosmetic**.

---

## Findings

Filled in by the run. See `test-results.md` for the full log.
