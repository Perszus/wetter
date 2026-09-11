# Wetter

A precipitation-first weather app for Android. No account, no tracking, no
advertising, no backend of its own.

The question people open a weather app to answer is usually narrow: **is it
going to rain, when, how heavy, and when will it stop?** Wetter is built around
that one.

> **Status: not released yet.** The app is built and tested. Home-screen widgets
> are still being added. See [Where it is now](#where-it-is-now).

---

## What it does

- **Rain, down to the minute.** When precipitation starts, how it develops, and
  when it clears.
- **Radar for the near term.** Inside the hour the forecast comes from what radar
  is measuring now, handing over to the models after that.
- **A timeline that stays hourly.** Where a regional model runs out, a global one
  continues it, joined where the first stops.
- **The right source for the place.** Several meteorological services, chosen per
  location, with failover when one is unavailable.
- **"Rain" means rain.** Below half a millimetre an hour it is called drizzle.
- **Works offline.** The last forecast stays on screen with its age, and survives
  the app being closed.
- **Widgets.** A resizable rain strip on the home screen, with more shapes to
  come.
- **Severe weather warnings.** Gales, torrential rain, heavy snow, ice,
  thunderstorms and dangerous temperatures — nothing else.
- **Depth when you want it.** Cloud decks, air quality, pressure, humidity, dew
  point, sun and moon.
- **Free and open source.** GPL-3.0-or-later, and the release build is
  reproducible: build it yourself and you get a byte-identical APK.

---

## Where it is now

| | |
|---|---|
| Providers, selection, failover | done |
| Precipitation timeline | done |
| Radar nowcast | done |
| Today, week and month views | done |
| Advanced conditions | done |
| Offline persistence | done |
| Location search, saved locations, map pin | done |
| Background refresh | done |
| Severe-weather notifications | done |
| Settings | done |
| Home-screen widgets | in progress — the first one is done |
| Published to a store | not yet |

628 tests: 624 unit and 4 instrumented, across the three modules.

---

## Getting it

Not published yet. When it ships it will be on **F-Droid**.

---

## Permissions

| | |
|---|---|
| `INTERNET` | Asking a weather service about a place. |
| `ACCESS_NETWORK_STATE` | Checking whether a refresh can succeed. |
| `ACCESS_COARSE_LOCATION` | Optional. A single fix when you ask for your current location, discarded once the pin is placed. |
| `POST_NOTIFICATIONS` | Optional. Severe weather warnings, switchable in Settings. |

The optional two are requested when first needed. Refusing either leaves
everything else working.

---

## Privacy

Wetter collects nothing. No analytics, no crash reporter, no advertising
identifier, no account. Cloud backup and device-to-device transfer are disabled,
so what the app stores stays on the device.

What leaves your device is a pair of coordinates, sent to a weather service so
it can answer. Coordinates sent to MET Norway are truncated to four decimals.

See [docs/privacy-policy.md](docs/privacy-policy.md).

---

## Weather data

Each service below requires attribution, and each is credited in the app's About
screen as well as here.

- **Open-Meteo** — <https://open-meteo.com> — weather, air quality and place
  search, licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- **MET Norway** — <https://api.met.no> — weather data from the Norwegian
  Meteorological Institute, licensed
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), used under its
  [terms of service](https://api.met.no/doc/TermsOfService).
- **RainViewer** — <https://www.rainviewer.com> — the global radar composite.
- **NOAA / NWS Aviation Weather Center** — <https://aviationweather.gov> —
  aerodrome observations.
- **Photon** — <https://photon.komoot.io> — place names, © OpenStreetMap
  contributors.
- **OpenStreetMap** — map tiles, © OpenStreetMap contributors.

---

## Building

JDK 17 and the Android SDK. Compile and target API 36, minimum API 26.

```sh
./gradlew test            # unit tests
./gradlew check           # tests, lint and formatting — what CI runs
./gradlew ktlintFormat    # fix formatting
./gradlew assembleDebug   # a runnable APK
```

No signing configuration is in the repository, and none is needed for a debug
build.

---

## How it is put together

```text
:app  ──▶  :data  ──▶  :domain
```

```text
:domain     plain Kotlin — no Android, no networking
├── model/          forecasts, hours, conditions
├── provider/       the provider port, coverage, selection, stitching
├── forecast/       rain windows and the readings the UI asks for
├── hazard/         severe-weather thresholds
├── radar/          nowcasting from radar frames
├── curve/, chart/  the precipitation curve and its bands
├── air/, sky/      air quality, cloud decks, visibility
├── climate/        local climatology
└── SolarTime.kt    sunrise, sunset, daylight

:data       an Android library — where weather comes from
├── network/        the shared HTTP client
├── provider/       Open-Meteo, MET Norway, RainViewer, METAR, the router
├── repository/     offline-first forecast access
├── db/             Room persistence
├── location/       geocoding, saved locations, the device fix
└── WeatherData.kt  the module's entry point

:app        the application
├── ui/screens/     today, week, month, locations, settings
├── ui/components/  the dial, the rain curve, the glyphs
├── ui/theme/       colour, type, spacing
├── widget/         home-screen widgets
├── notify/         severe-weather warnings
└── work/           background refresh
```

Further reading:

- [docs/design-principles.md](docs/design-principles.md) — what the app is for
- [docs/providers.md](docs/providers.md) — the provider architecture, the radar
  handover, and each provider's terms
- [docs/visual-language.md](docs/visual-language.md) — how colour and type are
  derived
- [docs/reliability-testing.md](docs/reliability-testing.md) — how the readings
  are verified
- [docs/decisions.md](docs/decisions.md) — settled decisions
- [docs/RELEASING.md](docs/RELEASING.md) — cutting a release

---

## Contributing

Bug reports and patches are welcome — see [CONTRIBUTING.md](.github/CONTRIBUTING.md).

Adding a weather provider means implementing `WeatherProvider` and adding one
line to `WeatherData`. Dependencies must be free software that F-Droid can
build.

---

## Licence

GNU General Public License v3.0 or later. See [LICENSE](LICENSE).

One additional permission is granted under section 7, allowing distribution
through application stores whose terms would otherwise conflict with section 6.
It grants nothing else: the source stays copyleft and a closed fork is still
forbidden. See [LICENSE-EXCEPTION.txt](LICENSE-EXCEPTION.txt).

Copyright © 2026 Roberts Kains.
