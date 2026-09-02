# Wetter

A precipitation-first weather app for Android. No account, no tracking, no
advertising, no backend of its own.

Most weather apps lead with a temperature and a cartoon sun. The question people
actually open one to answer is narrower and more urgent: **is it going to rain,
when, how hard, and when will it stop?** Wetter is built around that question.

> **Status: early.** The architecture, the weather providers and the offline
> plumbing work and are tested. The precipitation timeline that the whole app
> exists for is not built yet, and the forecast cache does not survive the app
> being closed. See [Where it is now](#where-it-is-now).

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

**It works offline.** Reading a forecast never waits on the network. A failed
refresh leaves the last forecast on screen and says how old it is, rather than
replacing it with an error.

**It asks for one permission.** `INTERNET`. Location access is optional and only
requested if you choose to use your device's position; the app is fully usable
without it.

**It has no server.** Requests go from your device straight to a weather
service. There is nothing in between belonging to us, because there is nothing
in between at all.

---

## Where it is now

| | |
|---|---|
| Project, theme, navigation | done |
| Domain model, provider abstraction | done |
| Open-Meteo and MET Norway providers | done |
| Geographic provider selection, health, failover | done |
| Adaptive hourly range — a short forecast extended from a second source | done |
| Offline-first repository | done, but the cache is in memory only |
| Current conditions on screen | done |
| **Precipitation timeline** | **not started** |
| Temperature curve, daily forecast | not started |
| Room persistence | not started |
| Location search and saved locations | not started — a short built-in list stands in |
| Home-screen widget | not started |
| Background refresh, notifications, settings | not started |

117 unit tests cover the solar calculations, the intensity bands, provider
selection, failover, forecast stitching, both response mappers, the repository
and the formatting.

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
└── SolarTime.kt    sunrise, sunset and daylight, computed rather than fetched

:data       an Android library — how weather is actually obtained
├── network/        the shared HTTP client (internal)
├── provider/       Open-Meteo, MET Norway, the router (all internal)
├── repository/     offline-first forecast access
├── location/       the built-in location list (a stand-in)
└── WeatherData.kt  the only way in: hands back a repository and attributions

:app        the application
├── ui/components/  reusable pieces
├── ui/screens/     weather, locations, settings
├── ui/theme/       colour, type, spacing
└── WetterContainer.kt
```

`:domain` has no dependency beyond the standard library, so Android or a wire
format cannot reach the models or the policy even by accident. Inside `:data`
the concrete providers, the HTTP client and the router are `internal`, so
nothing above can name them.

Further reading:

- [docs/design-principles.md](docs/design-principles.md) — what the app is for
  and what it refuses to become
- [docs/providers.md](docs/providers.md) — the multi-provider architecture, and
  the terms each provider is used under
- [docs/decisions.md](docs/decisions.md) — decisions that are settled, and why

---

## Weather data

Wetter uses these services. Both require attribution, and both are credited in
the app's About screen as well as here.

- **Open-Meteo** — <https://open-meteo.com> — weather data by Open-Meteo.com,
  licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Global
  coverage, no API key.
- **MET Norway** — <https://api.met.no> — weather data from the Norwegian
  Meteorological Institute, licensed
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Used under its
  [terms of service](https://api.met.no/doc/TermsOfService).

Neither service is asked for anything beyond a latitude and a longitude, and
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

---

## Contributing

Bug reports and patches are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

The short version: adding a weather provider should mean implementing
`WeatherProvider` and adding one line to `WeatherData`, and nothing else.
Dependencies must be free software that F-Droid can build. Comments explain
*why*, not what.

---

## Licence

GNU General Public License v3.0 or later. See [LICENSE](LICENSE).

Copyright © 2026 Roberts Kains.
