# Wetter

A precipitation-first weather app for Android. No account, no tracking, no
advertising, no backend of its own.

Most weather apps lead with a temperature and a cartoon sun. The question people
actually open one to answer is narrower and more urgent: **is it going to rain,
when, how hard, and when will it stop?** Wetter is built around that question.

> **Status: not released yet.** The app is built and tested — the precipitation
> timeline, radar, the widget, severe-weather warnings and offline persistence
> all work. What remains is release engineering and store submission. See
> [Where it is now](#where-it-is-now).

---

## What it does differently

**Precipitation is the primary signal.** Rain gets the emphasis, the colour and
the space. Temperature is deliberately quieter.

**It picks a weather source per location.** Wetter is not an Open-Meteo client
with a logo on it. It knows about several meteorological services, what each is
good at and where, and chooses per location — preferring a regional model where
one runs a fine grid, falling back to a global one elsewhere, and failing over
when a service is having a bad afternoon. You never have to know which one
answered. See [docs/providers.md](docs/providers.md).

**There is always an hourly timeline.** The best model for a place often has the
shortest reach: MET Norway's 2.5 km Nordic model is hourly for about sixty hours
and six-hourly after that. Rather than choose between a better forecast and a
longer one, Wetter takes the hours the regional model has and continues with a
global one, joined exactly where the first stops. Coarse steps are never
stretched into hours to fill a gap.

**The near term comes from radar, not from a model.** A model predicts from a
simulated atmosphere initialised hours ago; a nowcast predicts from where the
rain actually is and which way it was actually observed to move. Both are
guesses about the future — one of them starts from a look out of the window.
So inside the hour the radar decides, its weight falling away as the
extrapolation ages until the model takes over. The first half hour is stepped
finer still, because a ten-minute sweep can be nine minutes old by the time
anybody looks. [docs/providers.md](docs/providers.md) sets out where the
handover is and why.

**It says "rain" only when it means it.** Below half a millimetre an hour the
app calls it drizzle, because that is what it is. The bar is measured against
aerodrome observations rather than picked by eye: a curve fractionally above
zero is not rain, and an app that says it is will be wrong on most grey days.

**It works offline.** Reading a forecast never waits on the network. A failed
refresh leaves the last forecast on screen and says how old it is, rather than
replacing it with an error. The last forecast survives the app being closed.

**It has no server.** Requests go from your device straight to a weather
service. There is nothing in between belonging to us, because there is nothing
in between at all.

**You can check all of that.** The release build is byte-for-byte reproducible:
build this source yourself and you get an identical APK, so the published binary
can be shown to contain this code and nothing else.

---

## Where it is now

| | |
|---|---|
| Domain model, provider abstraction | done |
| Open-Meteo and MET Norway providers | done |
| Geographic provider selection, health, failover | done |
| Adaptive hourly range — a short forecast extended from a second source | done |
| Precipitation timeline | done |
| Radar nowcast for the near term | done |
| Today, week and month views | done |
| Advanced conditions — sun and moon, air quality, pressure, humidity, dew point | done |
| Offline-first repository, Room persistence | done |
| Location search, saved locations, map pin | done |
| Home-screen widget | done — a resizable rain strip, three cells by one by default |
| Background refresh | done |
| Severe-weather notifications | done |
| Settings | done |
| Published to a store | not yet |

**628 tests** — 624 unit tests across `:domain`, `:data` and `:app`, and 4
instrumented. They cover the solar and lunar calculations, the intensity bands,
provider selection, failover, forecast stitching, the response mappers, radar
handling, the hazard thresholds, the repository and the formatting.

Reliability is checked against sources outside the app rather than against its
own assumptions — aerodrome observations for the precipitation threshold, an
independent almanac for solar times, and reanalysis data for the hazard bars.
See [docs/reliability-testing.md](docs/reliability-testing.md).

---

## Getting it

Not published yet. When it ships it will be on **F-Droid** and **Google Play**.

One thing worth knowing in advance: Google Play holds the signing key for
anything distributed through it, so the Play build and the F-Droid build cannot
share a signature and **Android will not update one over the other**. Pick a
store and stay with it. See [docs/RELEASING.md](docs/RELEASING.md).

---

## Permissions

Four, and what each is for:

| Permission | Why |
|---|---|
| `INTERNET` | Asking a weather service about a place. |
| `ACCESS_NETWORK_STATE` | Knowing whether a refresh can succeed before trying. |
| `ACCESS_COARSE_LOCATION` | **Optional.** Asked once, when you press "use my current location", for a single fix that is discarded as soon as the pin is placed. Nothing subscribes to updates. Coarse rather than fine because the question is which town this is — the finest model in the app resolves about 2.5 km, so a street-level fix would be precision the forecast cannot spend. The app is fully usable without it. |
| `POST_NOTIFICATIONS` | **Optional.** Severe weather only — a gale, torrential rain, heavy snow, ice, a thunderstorm, or a dangerous temperature. Not a channel for showers, tips or news about the app. There is a switch in Settings. |

The first two are normal permissions, granted at install. The other two are
asked for at the moment they are needed, not on first launch, and refusing
either leaves everything else working.

---

## Building

Requires JDK 17 and the Android SDK (compile and target API 36, minimum API 26).

```sh
./gradlew test            # every module's unit tests
./gradlew check           # tests, lint and formatting — what CI runs
./gradlew ktlintFormat    # fix formatting
./gradlew assembleDebug   # a runnable APK
```

There is no signing configuration in the repository and none is needed to build
or run a debug build.

---

## How it is put together

```text
:app  ──▶  :data  ──▶  :domain
```

Three Gradle modules, and the arrow only points one way. This is the layering
rule made into a compile error rather than something a reviewer has to remember.

```text
:domain     a plain Kotlin library — no Android, no networking, nothing but stdlib
├── model/          WeatherForecast, HourlyWeather, conditions, errors
├── provider/       the WeatherProvider port, capabilities, coverage,
│                   selection policy, health, forecast stitching
├── forecast/       the reading the UI asks for — rain windows, umbrella advice
├── hazard/         severe-weather thresholds, local to the place they apply to
├── radar/          nowcasting from radar frames, and where it hands over
├── curve/, chart/  the precipitation curve and its bands
├── air/, sky/      air quality, cloud decks, visibility
├── climate/        what counts as unusual here, rather than globally
└── SolarTime.kt    sunrise, sunset and daylight, computed rather than fetched

:data       an Android library — how weather is actually obtained
├── network/        the shared HTTP client (internal)
├── provider/       Open-Meteo, MET Norway, RainViewer, METAR, the router
├── repository/     offline-first forecast access
├── db/             Room persistence, so a forecast survives a cold start
├── location/       geocoding, saved locations, the device fix
└── WeatherData.kt  the only way in: hands back a repository and attributions

:app        the application
├── ui/screens/     today, week, month, locations, settings
├── ui/components/  the dial, the rain curve, the glyphs
├── ui/theme/       colour, type, spacing — generated, not hand-picked
├── widget/         the home-screen rain strip
├── notify/         severe-weather warnings
└── work/           background refresh
```

`:domain` has no dependency beyond the standard library, so Android or a wire
format cannot reach the models or the policy even by accident. Inside `:data`
the concrete providers, the HTTP client and the router are `internal`, so
nothing above can name them.

Further reading:

- [docs/design-principles.md](docs/design-principles.md) — what the app is for
  and what it refuses to become
- [docs/providers.md](docs/providers.md) — the multi-provider architecture, the
  radar handover, and the terms each provider is used under
- [docs/visual-language.md](docs/visual-language.md) — why every colour is
  generated from a contrast requirement rather than chosen
- [docs/reliability-testing.md](docs/reliability-testing.md) — how the readings
  are checked against sources outside the app
- [docs/decisions.md](docs/decisions.md) — decisions that are settled, and why

---

## Weather data

Wetter uses these services. Each requires attribution, and each is credited in
the app's About screen as well as here.

- **Open-Meteo** — <https://open-meteo.com> — weather, air quality and place
  search, licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
  Global coverage, no API key.
- **MET Norway** — <https://api.met.no> — weather data from the Norwegian
  Meteorological Institute, licensed
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Used under its
  [terms of service](https://api.met.no/doc/TermsOfService).
- **RainViewer** — <https://www.rainviewer.com> — the global radar composite the
  nowcast is built from.
- **NOAA / NWS Aviation Weather Center** — <https://aviationweather.gov> —
  aerodrome observations, used to check what is actually falling.
- **Photon** — <https://photon.komoot.io> — place names, © OpenStreetMap
  contributors.
- **OpenStreetMap** — map tiles for the location picker, © OpenStreetMap
  contributors.

No service is asked for anything beyond a latitude and a longitude, and
coordinates sent to MET Norway are truncated to four decimals.

---

## Privacy

Wetter collects nothing. There is no analytics library, no crash reporter, no
advertising identifier and no account. Cloud backup and device-to-device
transfer are switched off explicitly, so what the app stores stays on the device
it is on.

The one thing that leaves your device is a pair of coordinates, sent to a
weather service so it can answer. That is unavoidable for a weather app; making
it the *only* thing is the point.

See [docs/privacy-policy.md](docs/privacy-policy.md).

---

## Contributing

Bug reports and patches are welcome — see [CONTRIBUTING.md](.github/CONTRIBUTING.md).

The short version: adding a weather provider should mean implementing
`WeatherProvider` and adding one line to `WeatherData`, and nothing else.
Dependencies must be free software that F-Droid can build. Comments explain
*why*, not what.

---

## Licence

GNU General Public License v3.0 or later. See [LICENSE](LICENSE).

There is one additional permission, granted under section 7, allowing the work
to be distributed through application stores whose terms would otherwise
conflict with section 6 — which is what makes a Google Play listing possible
without asking every contributor for permission afterwards. It grants nothing
else: the source stays copyleft and a closed fork is still forbidden. See
[LICENSE-EXCEPTION.txt](LICENSE-EXCEPTION.txt).

Copyright © 2026 Roberts Kains.
