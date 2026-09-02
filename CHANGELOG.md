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
- **The precipitation timeline.** One bar per hour across the local day, height
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
