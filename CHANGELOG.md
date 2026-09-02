# Changelog

Notable changes to Wetter. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Three pages behind a domain switcher — Today, Week and Month — each answering
  one question with its own tiles, instead of one page trying to answer all
  three. Today is the default. The location and current reading sit above the
  switcher, so changing page moves nothing that was already true.
- **The current reading is a dial.** A thermostat face: a porcelain disc with
  the temperature at its centre, a glass edge around it, hour ticks, and a short
  mark inside the rim showing where in the day you are.
- A light travels the glass edge at the speed of the wind — a drift in still
  air, a rush in a gale. Dead calm holds it still, which is itself the reading
  and stops the animation rather than spinning it at nobody. It is one sweep
  gradient rotated whole, so the tail is a continuous falloff rather than
  banding, and the seam where the sweep wraps sits at full transparency.
- Two marks stand beside the dial, low and out to the sides: an umbrella when
  rain is expected later today, and three stacked sine lines that fill like a
  level to give the wind as low, moderate or strong. The wind is therefore said
  twice on purpose — the travelling light gives a feel for it that needs
  watching, the lines give a reading that can be taken at a glance.
- All three wind lines are always drawn, the inactive ones faint. An indicator
  that hides its unfilled steps cannot be read as a level: two lines showing
  would leave you unable to tell two-of-three from two-of-two.
- Wind *direction* is deliberately not on the ring: the angle there already
  means time of day, and a compass bearing on the same degrees would make every
  reading ambiguous. Direction belongs in the Air tile, in words.
- **The precipitation curve.** A flowing area trace with a gradient fill,
  scrubbable to read any point of it. The curve is a monotone cubic spline, so
  it can never overshoot: ordinary smoothing dips below zero approaching a
  shower and bulges above the peak inside it, drawing rain nobody forecast.
- The curve states its own scale, and the scale adapts. A fixed 8 mm ceiling
  draws a drizzly day as a flat line; scaling to the day's peak makes drizzle
  and downpour look identical. Instead the ceiling steps between the
  meteorological band tops and the chart says which one it is using, so the
  scale can change but never silently.
- The curve is walked every ten minutes and scrubs at that resolution, reading
  out a *rate* — `≈0.4 mm/h` — rather than an accumulation. That distinction is
  what makes the fine reading honest: millimetres accumulated across an hour
  cannot be read at 01:20, but a rate at that moment can be interpolated. The
  tilde marks points that fall between the forecast's own samples.
- The window is the next six hours rather than the next day, on a real time
  axis: hour labels with half-hour ticks between them.
- No day/night shading, and no "updated N minutes ago". Keeping the forecast
  current is the app's job, not something to report to the reader — with the
  known cost that a device offline for a day now shows day-old numbers silently.
- The domain switcher is a pill sized to its words and centred, rather than a
  full-width bar, and the pages slide between one another in the direction they
  sit rather than cutting.
- **The chart's vertical axis is intensity, not millimetres, and it is fixed.**
  Nobody reads a number off a rain chart; they read a shape — high is pouring,
  low is drizzle. That reading is only true if the scale never moves, so the
  adaptive ceiling is gone, and with it the number that labelled it. The axis is
  anchored to the conventional intensity bands with each band given a slice of
  the height wide enough to see, which is linear in how wet you get rather than
  in millimetres.
- Scrubbing names the intensity — "3:40 · Light" — alongside the rate, because
  "light" is the half anybody can act on.
- Next rain is a pill under the curve rather than a card of its own, and it
  names the day when the day is not today: "Rain starts at 23:00", "… starts
  tomorrow at 08:00", "… starts Wednesday at 14:00", and a date beyond that,
  because seven days out a weekday name is today's name again.
- **The precipitation timeline (superseded by the curve above).** One bar per hour across the local day, height
  for intensity on an absolute 8 mm/h scale that is never rescaled to the view,
  colour carrying confidence, a night wash behind the small hours, and past
  hours dimmed so the boundary between dim and bright is the current moment.
  Built from ordinary Compose layout — no charting library, no `Canvas`.
- Precipitation spells in the domain layer: when rain starts, how hard it gets,
  when it stops, and whether it was still falling when the forecast ran out. A
  single dry hour splits a shower rather than being bridged.
- Week page: seven days drawn against one shared temperature scale.
- The timeline shows a rolling 24 hours from the current hour rather than the
  calendar day. Providers disagree about where an hourly series starts —
  Open-Meteo returns the day from local midnight, MET Norway from the current
  hour — so slicing by date gave a full chart from one and a five-hour stump
  from the other, depending on the time of day.
- Month page is an honest stub. No service forecasts a month, so it needs the
  archive endpoint and month-to-date actuals against the long-run normal.

- Adaptive hourly range. A provider that stops being hourly short of the horizon
  is extended from the next-best candidate, so there is always an hour-by-hour
  timeline across the forecast. The regional model keeps the near term, where
  precipitation timing matters most; coarse steps are never stretched into
  hours to fill the gap. `ProviderCapabilities` gained `hourlyHorizonHours`,
  `WeatherForecast` gained `supplement`, and the joining rules live in
  `ForecastStitcher`.

- Split into three Gradle modules, `:app` -> `:data` -> `:domain`, so the
  layering is enforced by the compiler rather than by review. `:domain` is a
  plain Kotlin library with no dependency beyond the standard library; the
  concrete providers, HTTP client, router and cache are `internal` to `:data`
  and reachable only through `WeatherData`.
- ktlint, configured from `.editorconfig` in the Android style, and checked in
  CI alongside tests and lint.
- CONTRIBUTING.md.
- Store distribution set up for both F-Droid and Google Play from one commit:
  shared `fastlane/metadata/android/` store text, an F-Droid build recipe, a
  privacy policy, and `docs/RELEASING.md` covering both. Release signing is
  optional so F-Droid can build unsigned, while `bundleRelease` fails fast
  without an upload key.
- An additional permission under GPL section 7 allowing distribution through
  application stores, so a Play listing does not require chasing every future
  contributor for consent. See `LICENSE-EXCEPTION.txt`.
- F-Droid metadata validated with F-Droid's own tooling: `fdroid lint` reports
  no findings and `fdroid rewritemeta` leaves it unchanged, so it can go into an
  fdroiddata merge request verbatim. Fixed an invalid `Categories` value (`Time`
  is not a category; the app is `Weather`) and a `Changelog` URL that pointed at
  `/main` rather than `/HEAD`.
- Byte-for-byte reproducible release builds. Two independent builds of a commit
  now produce an identical APK, verified from a fresh shallow clone with no
  keystore. AGP's `vcsInfo` embedding was the only thing standing in the way.

### Fixed

- Choosing MET Norway in the Nordics used to mean losing five of seven days of
  hourly forecast, because its six-hourly tail was discarded and nothing
  replaced it.
- Snow was drawn as rain for any provider that reports a precipitation amount
  without splitting it into rain and snow — which is MET Norway's whole output,
  and therefore most of what users in the Nordics would have seen all winter.
  The condition now decides when there is no breakdown.
- A temperature the provider did not supply was displayed as `0°`. Current and
  hourly temperatures are nullable and render as an em dash.
- CI ran `testDebugUnitTest`, which does not exist in a plain Kotlin module and
  would have silently stopped running `:domain`'s tests after the split. The
  workflow now uses variant-agnostic task names.
- `InMemoryForecastCache` wrote with a read-then-write that could drop a
  concurrent entry when two locations refreshed at once.
- A failed refresh left its error on screen after switching to another location.

## [0.1.0] — 2026-09-02

The first working skeleton: real forecasts from two weather services, chosen per
location, rendered offline-first. The precipitation timeline that the app exists
for is not built yet.

### Added

- Kotlin and Jetpack Compose project targeting API 36, minimum API 26, with no
  Google Play Services and no proprietary dependency.
- The app's own design language — colour roles named for weather rather than for
  Material's component slots, a narrow type scale in tabular figures, a 4 dp
  spacing grid, and a light and dark plate that avoid pure white and pure black.
- Domain model for forecasts, hourly and daily weather, conditions, locations
  and errors, with canonical units and no wire formats.
- `SolarTime`: sunrise, sunset and daylight computed from the NOAA solar
  position equations, including the polar day and polar night cases.
- Precipitation intensity bands keyed to the conventional meteorological
  rainfall rates.
- A multi-provider architecture — a provider abstraction, geographic coverage
  with soft boundaries, capability-based filtering, deterministic scoring,
  health tracking with exponential backoff, and bounded failover.
- Open-Meteo provider, used as the global baseline.
- MET Norway provider, preferred where its 2.5 km Nordic model runs, including
  aggregation of its timeseries into daily values without double counting the
  six-hourly tail.
- Offline-first repository: cached reads never wait on the network, and a failed
  refresh leaves the previous forecast on screen with its age shown.
- Weather, locations and settings screens, with provider attribution in About.
- 89 unit tests across the solar calculations, intensity bands, provider
  selection, failover, both response mappers and the repository.

### Known limitations

- The forecast cache is in memory and does not survive the process.
- Locations are a short built-in list; there is no search and no use of the
  device's position.
- The precipitation timeline, temperature curve and daily forecast are not
  drawn yet.
- There is no widget, no background refresh and no settings beyond About.

[Unreleased]: https://github.com/Perszus/wetter/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Perszus/wetter/releases/tag/v0.1.0
