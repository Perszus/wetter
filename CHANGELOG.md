# Changelog

Notable changes to Wetter. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

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
