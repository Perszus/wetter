# Design principles

What Wetter is for, and what it refuses to become. Code comments refer back to
this file; if a change contradicts something here, the discussion belongs in the
pull request rather than in the diff.

## The question the app answers

> Is it going to rain, when, how hard, how long, and when will it stop?

Everything else on the screen is context for that. A conventional weather app
leads with a large temperature and a weather illustration, then buries the
hourly precipitation two scrolls down in a row of cards. Wetter inverts that.

The instrument this is modelled on is a rain gauge and a barometer, not a
dashboard. It should read as measured, quiet, and precise.

## Product rules

1. **Precipitation is the primary signal.** It gets the emphasis, the only
   saturated colour in the palette, and the most screen area. Temperature is
   deliberately quieter than rain.
2. **Nothing waits on the network.** A cached forecast renders immediately. A
   refresh happens behind it, and its failure never removes what is on screen.
   The cache is on disk and a background worker keeps it fresh, so this holds
   across a restart rather than only within one session.
3. **Staleness is the app's problem, not the reader's.** There is deliberately
   no "updated 42 minutes ago" line. Somebody opening a weather app wants the
   weather, not a report on the app's own housekeeping; keeping the forecast
   current is work the app should simply do. The cost of this is real and worth
   knowing: a device offline for a day shows day-old numbers without saying so.
4. **No account, no telemetry, no advertising, no backend of ours.**
5. **One permission.** `INTERNET`. Location is optional and only requested when
   somebody chooses to use it.
6. **Free software, buildable from source**, with no proprietary dependency and
   nothing that F-Droid cannot reproduce.
7. **Most evidence, least speculation.** Where two sources disagree, the one
   that actually looked wins. Radar sees the rain that is falling; a model
   computed its next two hours before breakfast and cannot improve on them as
   the hour approaches. This is why the near term is radar-led and why the
   hand-over happens where the projection stops being an observation and starts
   being a guess of its own (docs/providers.md).

   The rule cuts both ways and that is the hard half. When the radar says
   nothing is falling, the screen may not say it is raining — even if a model
   does, and even though radar cannot see the sky and so cannot say what to put
   there instead. Naming what is *not* happening from an observation, and
   filling the rest from the model's cloud cover, is the honest reading; keeping
   the model's word for it because it is tidier is not.

   This rule is about what the app *does*, never about what it says. See rule 8.

8. **The machinery is our problem, not the reader's.** Somebody who downloads a
   weather app judges it by whether it matched the weather outside. They do not
   care which satellite, which model, which sweep, or how the two were weighed
   against each other, and telling them is not honesty - it is us showing our
   work in a place reserved for their answer.

   So no provenance labels, no "radar-backed" badges, no note that coverage is
   thin here, no explanation of how a threshold was chosen or a warning
   dismissed. Every one of those is a sentence about Wetter appearing on a
   screen whose whole job is to be a sentence about the sky.

   This does not soften rule 7 by one degree - it is why rule 7 has to be
   carried properly. The reader has no way to discount a bad reading, because
   they are not being told which readings to discount. Correctness is the only
   thing standing between them and a wrong answer, so it gets paid for in the
   engine and never in the interface.

## Visual language

The app should feel elegant, quiet, technical and highly legible. Specifically,
it avoids:

- weather illustrations and oversized icons
- stacks of rounded cards with shadows
- gradients used as decoration
- animation that does not carry information
- the default Material look applied wholesale

Material 3 is a toolkit here, not the design. The app defines its own colour
roles — `precipitation`, `temperatureWarm`, `night`, `hairline` — because
Material's role names describe a component library and give no place to say
"this is rain". A Material `ColorScheme` is derived from those roles afterwards,
so the handful of Material components in use inherit the app's ground rather
than introducing a second palette.

Two rules hold the palette together:

- Precipitation owns the only saturated hue.
- Neither the light nor the dark plate uses pure white or pure black. Both
  grounds are tinted slightly cool, so the rain hue reads as part of the same
  instrument rather than as an accent stuck onto grey.

Dynamic colour (Material You) is not supported, and this is deliberate: the
palette encodes meaning, and a wallpaper-derived scheme would break the
relationship between rain and everything else.

Sections are separated by a hairline and a gap rather than by cards. Cards imply
each block is a separate object; these are consecutive readings from the same
instrument, and the eye should run straight down them.

Every number that can change is set in tabular figures. A temperature ticking
from 9 to 10 that shifts the layout is exactly the kind of imprecision an
instrument must not show.

## Architecture rules

- **The domain model represents weather, not an API response.** No integer
  weather codes, no provider-shaped field names, no wire formats above `data/`.
- **Units are canonical throughout the domain**: Celsius, millimetres, metres
  per second, hectopascals, percent. Conversion happens once, at the point of
  rendering.
- **Abstractions need a reason.** An interface with one implementation is a
  smell unless a second is genuinely coming. There is no dependency-injection
  framework, no event bus and no charting library: the whole object graph is a
  handful of lines in `WetterContainer` and `WeatherData`.
- **The module boundary is the architecture rule made enforceable.** Three
  modules, `:app -> :data -> :domain`, and the arrow only points one way.
  `:domain` is a plain Kotlin library, so Android and networking cannot leak
  into the models or the policy even by accident. Three is the necessary
  number, not an architecture demonstration: the UI is not split into feature
  modules and should not be.
- **Prefer the simpler of two valid designs** unless the more complicated one
  buys something concrete and nameable.
- **Files stay small and single-purpose.** Screens are composed of components;
  a component that cannot be understood on its own screen is too big.
- **Comments explain why.** What the code does is visible; why it does it that
  way, and what was rejected, is not.

## What gets tested

Things that can actually break, rather than a coverage number:

- the arithmetic — solar position, precipitation intensity bands, forecast age
- the policy — which provider is chosen, when failover happens, when it does not
- the mappers — each provider's real response shape, including its awkward parts
- the offline contract — cache-first reads, and a failed refresh preserving data

Tests assert things that must be true rather than values copied from elsewhere.
Where an absolute check is needed, the expected value is derived in the test
itself, so a future reader can re-derive it instead of trusting it.

## Build order

The app is built in phases, each one compiling and testable, rather than in one
pass. Roughly:

1. Foundation — project, theme, navigation, domain model *(done)*
2. Weather data — HTTP, providers, repository *(done)*
3. Persistence — Room, a cache that survives the process
4. The visual phase — precipitation timeline, temperature curve, daily forecast
5. Locations — search, saved places, optional device position
6. Widget — a precipitation-focused home-screen widget
7. Background refresh
8. Settings
9. Polish, accessibility, tablets
10. F-Droid preparation

Advanced visualisation waits until the data flow underneath it is stable. A
placeholder chart drawn from invented data only teaches you that invented data
looks good.
