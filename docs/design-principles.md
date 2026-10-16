# Design principles

What Wetter is for, and what it does not become. Code comments refer back to
this file; a change that contradicts something here should say so in the pull
request.

## The question the app answers

> Is it going to rain, when, how hard, how long, and when will it stop?

Everything else on the screen is context for that. A conventional weather app
leads with a large temperature and an illustration, and puts the hourly
precipitation two scrolls down. Wetter inverts that. It is modelled on a rain
gauge and a barometer, not a dashboard: measured, quiet, precise.

## Product rules

1. **Precipitation is the primary signal.** It gets the emphasis, the only
   saturated colour, and the most screen area. Temperature is quieter than rain.
2. **Show what is known at once, then fetch newer.** Every surface (the app,
   the dial, the widgets) draws from disk immediately and never waits on the
   network. Opening the app asks for the newest data, an open screen keeps
   asking, and in the background a clock timed to the radar wakes after each new
   sweep (`work/Heartbeat.kt`). A failed fetch never removes what is on screen.
   Radar, which carries the next half hour, is fetched first.
3. **Staleness is the app's problem, not the reader's.** The forecast screen
   carries no "updated 42 minutes ago" line; keeping the forecast current is work
   the app does. The cost: a device offline for a day shows day-old numbers
   without saying so. The age is in Settings for anyone who wants it.
4. **No account, no telemetry, no advertising, no backend of ours.**
5. **Few permissions, each for one stated reason.** `INTERNET` to ask the
   services about a place. Location is optional and requested only when somebody
   asks for their current location; notifications carry severe-weather warnings
   only; boot restarts the background clock.
6. **Free software, buildable from source**, with no proprietary dependency and
   nothing F-Droid cannot reproduce.
7. **Most evidence, least speculation.** Where two sources disagree, the one
   that looked wins. Radar sees the rain that is falling; a model's next two
   hours were computed in advance. So the near term is radar-led, and hands over
   to the models where the projection stops being an observation
   ([providers.md](providers.md)). When radar sees nothing falling, the screen
   does not say it is raining, even if a model does.
8. **The machinery is our problem, not the reader's.** Somebody judges a weather
   app by whether it matched the weather outside. No screen names a provider,
   carries a "radar-backed" badge, shows a confidence number or explains how a
   threshold was chosen. Failures take Wetter as their subject: Wetter did not
   answer, not "the weather service". The two exceptions are the reader's own
   business: no network, and no location chosen yet. Credits appear only where
   licences require them: in About, and on the maps.
9. **The finest resolution that is real.** The unit is a point in time. Take
   the finest signal a source genuinely has, and never let one source's
   publishing cadence (an hour, say) become the app's unit: every source is a
   line through time, read at the moment being answered (`PrecipitationFusion`).
   Finer stamps are not finer information. Open-Meteo serves quarter-hour stamps
   whose values are mostly the hour divided evenly, so they are not used, and no
   detail is manufactured to fill a gap. Radar is the only source that
   re-observes within the hour, which is why it leads the first one.

## Visual language

Elegant, quiet, technical, legible. Wetter avoids weather illustrations and
oversized icons, shadowed card stacks, decorative gradients, animation that
carries no information, and the default Material look.

Material 3 is a toolkit, not the design. The app defines its own colour roles
(`precipitation`, `temperatureWarm`, `night`, `hairline`) and derives a Material
`ColorScheme` from them, so the few Material components in use inherit the
app's ground. There is no dynamic colour: the palette encodes meaning, and a
wallpaper-derived scheme would break it.

The app has two plates, Pure White and Pure Black, both true grey; the widgets
follow the phone's light or dark mode on paper and night plates with a trace of
blue. Rain is the one saturated hue on all of them. Tiles are a single step of tone, with no shadow or
border. The dial is always dark, whatever the plate, so the pictures on its
glass always show ([visual-language.md](visual-language.md)). Every number that
can change is set in tabular figures, so a temperature ticking from 9 to 10
never shifts the layout.

## The rain axis

Every rain curve's vertical axis is `RainCurveBands` in `:domain`, shared by the
app's chart and the widgets. It is not a scale of millimetres: rates are wildly
unevenly spaced (a trace is a tenth of a millimetre, torrential fifty), so the
axis is anchored to the intensity bands instead.

- **The three levels take the same height.** They are a scale of the three words
  anybody acts on. A trace is the bottom of the light band, not a fourth level,
  and nothing sits above the chart that could read as more of it. Checked on the
  rendered pixels, not the constants.
- **Every surface agrees which band a rate is in** (`RainCurveBandsTest`). Where
  a rate lands within a band may differ: the widgets lift low rain within the
  light band, keeping its ceiling fixed, because they have no captions and a
  third of the height. A legibility problem on one surface is fixed on that
  surface, never by changing the shared axis.
- **Both axes are fixed.** Height is intensity and never adapts to the data;
  width is pixels per hour, the same every morning. A chart holding more than a
  screen scrolls rather than squeezes, and a short series makes a shorter chart,
  not a wider-drawn one.
- **Legends stay on the screen, measurements on the chart.** The band captions
  hold still while the hours slide past, drawn behind the curve so they never sit
  on the line being read.

## Architecture rules

- **The domain model represents weather, not an API response.** No integer
  weather codes, provider-shaped field names or wire formats above `data/`.
- **Units are canonical in the domain** (Celsius, millimetres, metres per second,
  hectopascals, percent) and converted once, when drawn.
- **Abstractions need a reason.** An interface with one implementation needs a
  second that is genuinely coming. No dependency-injection framework, event bus
  or charting library: the object graph is a handful of lines in
  `WetterContainer` and `WeatherData`.
- **Three modules, `:app -> :data -> :domain`, and the arrow points one way.**
  `:domain` is plain Kotlin, so Android and networking cannot reach the models
  or the policy. The UI is not split into feature modules.
- **Prefer the simpler of two valid designs** unless the other buys something
  concrete and nameable.
- **Comments explain why.** What the code does is visible; why it does it that
  way is not.

## What gets tested

Things that can actually break, rather than a coverage number: the arithmetic
(solar position, intensity bands, forecast age), the policy (which provider is
chosen, when failover happens), the mappers (each provider's real responses,
awkward parts included), the offline contract, the widgets' layout at every size
they can be given, and the release documents against the code.

Tests assert what must be true rather than values copied from elsewhere. Where
an absolute value is needed, it is derived in the test, so a reader can check it.
