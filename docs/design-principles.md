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
3. **The app is honest about age.** A forecast that is two hours old says so.
4. **No account, no telemetry, no advertising, no backend of ours.**
5. **One permission.** `INTERNET`. Location is optional and only requested when
   somebody chooses to use it.
6. **Free software, buildable from source**, with no proprietary dependency and
   nothing that F-Droid cannot reproduce.

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
  framework, no event bus, no charting library and no multi-module split: the
  whole object graph is a dozen lines in `WetterContainer`.
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
