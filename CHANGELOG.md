# Changelog

Notable changes to Wetter. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Pure Black is the default plate.** A weather app is opened at the two ends
  of the day far more than in the middle of it, and that is when a bright page is
  worst; it is also the plate that costs least on an OLED screen. Paper is two
  taps away. The launch colour follows suit, and the `values-night` variant is
  gone: the app follows the phone's dark mode nowhere else, and a launch colour
  that did would flash white at somebody who chose black. Whoever picks Pure
  White has the window ground corrected as soon as the preference is read.
- **Portrait only.** The column is the whole layout — a dial, a switcher and a
  stack of tiles read top to bottom — and there is no landscape design of it to
  turn into.
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
- **The rain chart now holds a full day and shows six hours of it.** It opens
  exactly where it did before — the next six hours, at the size they were — and
  the rest of the day is pushed into view sideways. The reason the chart was six
  hours was that a day drawn across a phone flattens the part anybody is going
  to act on into a smear; that argument was about the *screen*, and only ever
  ruled out drawing a day at once.
- Pixels per hour is therefore now fixed the way the height is, and for the same
  reason: an hour is the same width every morning, so a shape means the same
  thing every morning. A short forecast makes a shorter chart rather than the
  same chart drawn thinner.
- Hold to read, drag to travel. A horizontal drag can only mean one thing and it
  now means moving through the day, so the reading moved to a press-and-hold —
  which is the right gesture for it anyway, since taking a reading is deliberate
  and travelling is not. Once held, the cursor still follows a finger as before.
  What is gone is the tap that used to flash a value instantly.
- The band names — light, moderate, heavy — stay put on screen while the weather
  slides underneath them, rather than living at the far end of the chart where
  finding out what the height means would have meant a trip to tomorrow evening.
  They are still drawn behind the curve, which is why they are not an overlay.
- Midnight is marked, with a faint full-height rule and the weekday's name in
  place of "0:00" on the axis. Six hours crossed a day boundary from one side at
  most; a day always crosses it, and "3:00" on its own then stops being an
  answer.
- The fused radar-and-model timeline runs a day rather than six hours, so the
  whole scrollable chart is one measurement end to end rather than radar for the
  visible part and the raw hourly rows for the rest, joined at whatever hour the
  radar happened to reach.
- **The week is a row of marks, not a table of millimetres.** Each day gives its
  sky as a small drawn glyph, then the temperature range. The rain column and the
  temperature bars are gone: `4.2 mm` has no feel to it — the same finding that
  fixed the rain chart's axis — and a week is skimmed rather than studied. What
  somebody wants off that page is which day is the wet one, and a mark answers it
  down a column faster than seven numbers can.
- The glyphs are drawn rather than shipped as drawables: one file, the theme's
  own colours, and no light/dark asset pairs to keep in step. The sky is in ink
  and only what falls takes the precipitation hue, so the blue marks down the
  column are the wet days and nothing else competes for the eye.
- **A day opens into its hours**, scrolled sideways: the time, the sky, the
  temperature, and how hard it is raining. One day at a time — seven open drawers
  is a page nobody can hold in their head.
- The hourly rain is height on the same fixed axis the chart and the widget use,
  so a bar there and a peak here mean the same wetness. It is not a number, for
  the same reason the chart's axis carries none.
- A wet hour is said three times at three sizes: the column is washed in the rain
  colour so it can be picked out of a strip that is still moving, its time is set
  in that colour, and the bar says how hard once you have stopped on it. The bar
  alone was not enough — one wet hour in twenty-four, at 0.4 mm, drew a two-pixel
  stub you had to scroll past the other twenty-three to find.
- Today's hours start at the current hour rather than at midnight. The rest of
  the row summarises the whole day and rightly includes the morning that has
  been; an hour-by-hour forecast of hours that have already happened is a stretch
  of strip to scroll past before reaching anything anybody can act on.
- **The month is a calendar of thirty days**, weekday columns in the reader's own
  week order, today outlined, wet days washed in the rain colour. Each square
  carries the sky and the day's high. It replaces the stub.
- Days past the forecast carry a **ten-year median** instead — what that date has
  actually done here — drawn so they can never be mistaken for a forecast: no
  condition mark where every forecast square has one, and a rank lighter in the
  ink. No service forecasts a month, and the ensembles that reach that far stop
  carrying information well before they stop producing numbers: measured at Rīga,
  day 33 of a 31-member run had two fifths of its members wet, which is the base
  rate for October there, and 16.8 °C between its warmest and coldest member.
- The boundary between the two is worked out on every draw and stored nowhere.
  Each square asks for a forecast and falls back to a normal only where there is
  none, so a square stops being a median the morning the forecast first reaches
  it — nothing to invalidate, nothing that can be left stale.
- The normals pool five days either side over ten years, about a hundred and ten
  samples a date. Ten samples is not a climate: the raw medians for late
  September ran 16.5, 14.2, 13.9, 13.1 °C on consecutive days, and September does
  not cool by 2.3 degrees in a day and warm back up. Wet is counted as a share of
  past days rather than averaged, so one year's storm cannot become a normal
  Tuesday.
- Open-Meteo is asked for all sixteen days it will give rather than seven — 14 KB
  to 31 KB per fetch. Its declared `maximumForecastDays` had been claiming reach
  the request never asked for, and the router ranks on that.
- **Settings, as a panel over the app rather than a screen you travel to.**
  Groups down the left — General, Appearance, About — and the chosen group on the
  right. It opens for one change and shuts again, and the forecast never leaves
  the screen for it.
- **Units.** Temperature in Celsius or Fahrenheit, wind in m/s, km/h, mph or
  knots, precipitation in millimetres or inches. The domain still computes in
  Celsius, metres per second and millimetres throughout; these say only how a
  number is written.
- The units are **guessed from the first place you pick**, so somebody in Ohio
  does not have to find Settings before the app says anything they recognise.
  Guessed from the coordinates rather than from the address: a pin dropped in
  Montana may never get a country name, and a default that depended on a
  volunteer-run geocoder answering would be metric for some Americans and
  imperial for none of them, at random. Both long US borders are drawn as
  polylines — a rectangle takes Monterrey and Tijuana with it, and the 49th
  parallel takes Toronto, which sits below the latitude of Detroit.
- The guess is made once. Changing anything keeps the choice, and "chosen" means
  a row exists rather than that the values differ from the defaults — somebody in
  Boston who deliberately set Celsius must not be re-guessed at because they
  moved a pin.
- **Two plates: Pure White and Pure Black**, brought across from This Note and
  Orobos and built to the formulas those apps state. Pure Black is Ansel Adams'
  zone system — `Zone 0 #0A → I #1A → II #2A → III #3A`, layers about sixteen hex
  apart, brightness as elevation. Pure White is Kenya Hara's *White*: off-white
  paper, never `#FFF`, ink at near-black, and elevation as a subtle darkening
  because at L* 97 a lifted surface clamps to white and vanishes. Both are true
  grayscale, which is the whole of what "pure" means in the names.
- There is no "follow the system". Each plate is a whole design rather than a
  light switch, and the cost is stated plainly: a phone that flips to dark at
  sunset will not take this app with it.
- **The only saturated colour left is on the weather glyphs.** The rain chart,
  the hour strip, the month's wet days, the next-rain dot and the widget's curve
  all draw in ink now. The chart keeps its intensity ramp — it runs from a quiet
  label's tone up to a headline's instead of through the blue.
- **The map is looked at before it is chosen from.** Pan and zoom as much as you
  like and nothing is picked; one tap places the pin, and it then stays on that
  ground while the map moves under it. The crosshair welded to the centre made
  every pan a commitment and opened already claiming an answer nobody had given.
- Zoom buttons on the map. It had pinch and nothing else, which is fine on a
  table, awkward one-handed, and undiscoverable — nothing on screen said the map
  could zoom at all.
- **Use my current location**, from one fix, asked for at the moment it is
  pressed. Coarse permission only, the framework's own `LocationManager` rather
  than Play Services, and the network provider before GPS: a fix to a few hundred
  metres in about a second is well inside any model's grid, and a reader can
  nudge a pin but cannot nudge a spinner. Nothing subscribes to updates and
  nothing runs in the background.

### Removed

- **The explanations.** The note under the advanced view saying how far the
  forecast had run against nearby airports and how many predictions that was
  learned from; the "Local fix" figure beside it; the lines under the unit and
  theme choices explaining how they were guessed and why they do not follow the
  phone. The correction still happens — it is simply not narrated. The job is to
  give a good reading, not to talk about how it was arrived at.
- The week's summary tile. Every figure in it was already on the page above it,
  said more precisely.

### Fixed

- **The learned temperature correction was counting the same hour a dozen
  times.** A forecast for eight o'clock is written down on every refresh, so one
  hour arrives in the record ten or twelve times over. Measured on a real phone:
  201 checked predictions at one place were 24 distinct hours — read as 201
  samples, which is full strength, so the app had started subtracting 1.3 °C from
  every temperature on the screen after a single day of evidence. Errors are now
  collapsed by the hour they are about, and the sample count is hours verified.
- **A clear airport report was not being recorded as a dry hour.** An absent
  present-weather group was treated as "nobody looked" rather than "nothing was
  falling", so the only precipitation observations that ever reached the record
  were wet ones. On the same phone: 112 checked model predictions across 12
  hours, every one of them wet, which makes any skill score computed from them
  meaningless. A report that was actually filed and carries no weather group is
  now the dry observation it is; silence with no report behind it stays unknown.
- The system bar icons were painted from the *phone's* dark mode rather than from
  the app's, so choosing a dark plate on a phone set to light left dark icons on
  a near-black ground — a blacked-out strip where the clock should be. The plate
  now tells the insets controller which way it runs. The app still draws under
  the status bar; drawing under somebody's clock is not the same as covering it.
- The dial's porcelain ramp read as three flat bands on a zone plate. It was
  built from the surface ladder, which those plates space at roughly twice this
  app's own — right for separating panels, wrong for shading a single object.
  Objects now shade by a normalised fraction of that ladder, so a bowl is the
  same depth on every plate.
- The map's tap handler was keyed on the map position, so it tore down and
  rebuilt the whole gesture detector on every frame of a pan.
- The dial's unit letter was a hardcoded "C" and the rain rate a hardcoded
  "mm/h", so Fahrenheit read `64° C` and inches read `0.02 mm/h`.

- The fused timeline used to pad its tail with zeros when asked for more than
  the sources could answer. A step neither the radar nor the model covers is an
  absence of evidence, and returning it as a rate of zero would have drawn
  confident dry weather out of nothing — and told the line under the chart that
  it does not rain tonight. The series is now cut where the evidence stops.

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
