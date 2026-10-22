# Decisions

Choices that are settled, with the reason. If you are about to change one of
these, the reason is what you need to argue with.

Decisions specific to the multi-provider system live in
[providers.md](providers.md); the visual and architectural rules live in
[design-principles.md](design-principles.md).

---

## Platform

**minSdk 26, targetSdk and compileSdk 36.**
API 26 is where `java.time` exists natively, which removes the need for library
desugaring and lets the whole codebase use `Instant`, `LocalDate` and `ZoneId`
without a shim. That alone justifies the floor.

**Kotlin, Compose, and nothing else for the UI.**
No view-based screens, so the app does not need `com.google.android.material`.
The one activity is themed from a platform theme instead, which keeps a whole
dependency out of the tree.

**AGP 9.2.0 / Gradle 9.4.1 / Kotlin 2.3.20, built with JDK 17.**
Java 17 rather than 21 because 17 is what the toolchain here provides.

**AndroidX and Compose versions are pinned below the latest.**
Everything newer requires `compileSdk 37`, and nothing in the app needs an API
above 36. Bump these together with `compileSdk`, not before, and only for a
reason beyond the version number.

**No Google Play Services, Firebase, or any Google library beyond AndroidX.**
Required for F-Droid, and consistent with the app having no backend.

---

## Licence and distribution

**GPL-3.0-or-later.**
The usual choice for an F-Droid application and the strongest guarantee that
what is shipped stays inspectable. Every dependency must be compatible with it.

**`dependenciesInfo` is disabled for release builds.**
The dependency-metadata blob AGP normally embeds is signed with a Google key and
cannot be reproduced from source, which F-Droid rejects.

**No signing configuration in the repository.**
F-Droid builds and signs release artefacts itself from a clean checkout. A
release build here produces an unsigned APK on purpose.

**`applicationId` is `lv.bolwarra.wetter` and is permanent.**
Changing it after publication would orphan every installation. It is the same on
both stores: forking it per store would split the listing, the reviews and the
install count to solve a problem it does not actually solve.

**Published on both F-Droid and Google Play, from one commit and one set of
store text.**
`fastlane/metadata/android/` is read by both, so the description and changelog
are written once. The build needs no flavours, because there is nothing
Play-specific in the app to flavour: no billing, no services, no proprietary
anything.

**The two stores will carry different signatures, and this is not fixable.**
Play App Signing is mandatory for new apps, so Google holds the key for anything
distributed there. Android will not update an F-Droid install over a Play one or
the reverse.

**Release signing is optional, and deliberately so.**
`assembleRelease` succeeds without a keystore and produces an unsigned APK,
because that is exactly what F-Droid's build servers do and failing there would
break them. `bundleRelease`, which only ever exists to be uploaded to Play,
fails immediately without one, so an unsigned upload is caught in a second
rather than at the end of a long transfer.

**`versionCode` and `versionName` are written by hand.**
Deriving them from the repository, by commit count or `git describe`, makes the
build depend on clone depth. F-Droid does not guarantee a full clone, and a
version that changes with how the source was fetched is not a version.

**Locales are pinned to what is actually translated.**
Otherwise the resource set varies with whatever is installed on the machine
running the build, which is one more way two builds of one commit can differ.

**The release build is byte-for-byte reproducible, and `vcsInfo` is off.**
Two independent builds of a commit produce an identical APK. Getting there took
one setting: AGP embeds `META-INF/version-control-info.textproto` describing the
git checkout, and that was the only file that differed. Neither store requires
this, but it is what lets anyone confirm that a published binary contains this
source and nothing else, which is the point of shipping free software.
docs/RELEASING.md has the command to check it.

**GPL-3.0-or-later, with an additional permission under section 7 for
distribution through application stores.**
Play's Developer Distribution Agreement can be read as imposing the further
restrictions section 6 forbids. The copyright holder may distribute their own
work however they like, so this changes nothing today, but it would become an
obstacle the moment somebody else contributes, and it is far easier to grant now
than to collect from a dozen people later. The permission is narrow: it removes
an obstacle to distribution and grants nothing else. See LICENSE-EXCEPTION.txt.

**No NonFreeNet anti-feature; every service is disclosed instead.**
The flag is for an app that promotes, or depends entirely on, a non-free network
service. Wetter promotes none and depends entirely on none: forecast providers
are interchangeable behind the router and fail over to one another, Open-Meteo
is itself free software that the provider can be pointed at a self-hosted copy
of, and radar, observations, place names and map tiles each degrade to nothing
rather than breaking the app. The submission lists every service the app
contacts (Open-Meteo, MET Norway, RainViewer, the NOAA/NWS Aviation Weather
Center, Photon and OpenStreetMap), so reviewers can judge it themselves.

**Backup is off, explicitly and twice.**
`allowBackup="false"` covers older releases, and a `data_extraction_rules` file
excludes everything from both cloud backup and device transfer, because the
attribute is deprecated from Android 12 and the rules file is what the platform
actually reads. The app holds a location list and a cached forecast; both are
trivially rebuilt and neither should leave the device.

---

## Modules

**Three modules: `:app` -> `:data` -> `:domain`.**
Each earns its boundary by turning a rule a reviewer had to remember into one
the compiler enforces.

`:domain` is a plain Kotlin library rather than an Android one. It has no
dependency beyond the standard library, so Android, Ktor and Compose cannot
reach the weather models, the selection policy or the solar geometry even by
mistake. Its tests run without the Android Gradle plugin, in about a second.

`:data` cannot see the UI. The concrete providers, the router, the HTTP client
and the cache are `internal` to it; the only way in is `WeatherData`, and the
only way out is a `WeatherRepository` and a list of attributions.

**The UI is deliberately not split into feature modules.** Three is the number
that buys enforcement. More would be an architecture demonstration, which is
exactly what design-principles.md rules out.

**Shared test code lives in `:domain`'s test fixtures.** The fake provider is
needed by both modules' tests, and a published `testFixtures` source set shares
it; the alternative is two copies that drift apart.

## Domain

**`java.time` throughout, not `kotlinx-datetime`.**
This is an Android application, not a multiplatform library, and minSdk 26 makes
`java.time` free. One less dependency and no conversions at the boundaries.

**Units are canonical in the domain and converted only for display.**
Celsius, millimetres, metres per second, hectopascals, percent. Carrying a unit
with every value would mean every calculation has to ask what it is holding.

**Sunrise and sunset live on `DailyWeather`, not on `WeatherForecast`.**
They are properties of a day at a place, and the timeline needs them for every
day it draws, not only for today.

**`WeatherCondition` includes `SLEET`, which no WMO code produces.**
MET Norway reports it, and in a northern winter it is much of what falls.

**Sunrise, sunset and daylight are computed rather than fetched.**
The NOAA solar position equations, in `SolarTime`. Not every provider publishes
them, and the timeline's night shading is not optional decoration: an unshaded
03:00 reads as an afternoon. Computing it costs one file of arithmetic, works
offline, and agrees with itself across providers. The alternative was a second
network call to a second service with a second set of terms.

---

## Data flow

**Offline-first means reads never touch the network.**
`WeatherRepository.observe` answers from the cache. `refresh` is a separate
call, and its failure leaves what is on screen exactly where it is.

**Ten minutes of freshness.**
The radar's cadence. Models publish at a different minute for every service and
run, and asking costs little when nothing has changed: MET Norway answers "not
modified" to a conditional request and the HTTP cache answers inside `Expires`.
A manual refresh ignores the window: somebody who asks has a reason.

**Cache keys round coordinates to four decimals.**
A position read from the device differs in the far decimals every time. An
exact-match key would make every refresh a miss and every cached forecast
unreachable.

**The forecast cache is Room, one JSON row per place.**
A forecast is read and written whole, so it is stored whole, and a payload that
no longer parses after an update is a cache miss that costs one request. The
widgets and the background refresh read the same rows as the screen.

**Ktor over OkHttp, not Ktor's Android engine.**
Connection reuse and correct coroutine cancellation matter when two providers
may be tried in one refresh. Both are Apache-2.0 and build from source.

**JSON parsing ignores unknown keys.**
Providers add fields to their responses regularly. An app already installed on
somebody's phone must not start reporting "malformed response" because a new
variable appeared upstream.

---

## Deliberately not done

**No dependency-injection framework.**
The entire object graph is `WetterContainer`, about a dozen lines, read top to
bottom. A framework would add a compiler plugin, an annotation vocabulary and a
layer of indirection to solve a problem this app does not have.

**No charting library.**
The rain curve, the dial and the hour strips are drawn directly. A library would
bring its own axis, its own interpolation, which overshoots, and its own styling,
and each of those is a decision this app makes for itself.

**No Material You dynamic colour.**
The palette encodes meaning: precipitation owns the only saturated hue, and
temperature is quieter than rain. A wallpaper-derived scheme would break that
relationship on most devices.

**The dial is always dark, on every theme.**
Its glass pictures are light and cannot show on a light face; drawing them dark
on light read as a negative. So a light plate lends the dial its dark counterpart
in the same tone, and any new theme does the same (docs/visual-language.md).

**No bottom navigation bar.**
Locations and settings are visited a few times a month. Spending a permanent
strip of every screen advertising them costs more than the chevron beside the
location name.

**No type-safe navigation routes.**
No destination carries an argument, so the generated route types would buy
nothing. Revisit if one ever does.

**One notification, and it is severe weather.**
Deliberately narrow: storms, gales, torrential rain, heavy snow, ice and the two
ends of the thermometer, up to a day ahead, at the same thresholds the amber mark
on the dial uses.

Extreme ultraviolet and unhealthy air are detected and drawn but never notified.
The first is most summer days across half the world; the second is a condition a
city has for a season rather than an event that arrives. Both would teach
somebody to swipe warnings away without reading them, and the one that matters
is the one they would then miss.

There is no digest, no "rain starting in 20 minutes", no daily forecast push.
The app has one thing worth saying without being opened.

**A hazard threshold is a property of the place, not of the planet.**
Heat, cold and wind are judged against what the location itself does at this
time of year, the 95th and 99th percentiles of a decade of ERA5, from the same
archive the Month page keeps, bounded by the published physiological bands so
the local figure can move the bar without moving it off the scale.

This is not an invention. Meteoalarm publishes one colour scale and leaves every
number behind it to the national services; the NWS says plainly that its cold
criteria are set from local climatology, and issues advisories as mild as
−3.9 °C. A single global number is the thing neither of them does, and the
reason is visible in the data: −25 °C is a dead threshold in Rīga and 32 °C of
heat index is a nightly one in Kolkata.

Wind is the exception and only tightens. Beaufort and the NWS gust criteria
agree to within a few per cent across two centuries and neither adjusts for
location, so a calm place does not earn a lower bar; a windy one earns a higher.

Rain and snow keep absolute thresholds because theirs are *rates*: 20 mm in an
hour overwhelms drainage anywhere. Ice keeps its because freezing rain is rare
and dangerous everywhere.

**One source of truth for how hard it is falling.**
A provider's symbol carries two claims in one word: *what* is falling and *how
hard*. The first is the provider's to make; the second the app measures against
its own scale, so a word can never disagree with the bar drawn under it (a MET
Norway day peaking at 1.0 mm/h arrives as `DRIZZLE`, which the scale calls light
rain).

The rule: **the temperature decides what is falling, the rate decides how
hard, and the symbol keeps only what it alone knows**: the sky, the character of
the fall, the hazard. `WeatherCondition.atRate` handles intensity and
`appropriateFor(temperature)` the kind, and every surface draws `.appearance`
rather than the raw symbol: the hour, the day, and the current conditions on the
dial.

Only two pairs are renamed: drizzle against rain, snow grains against snow.
Showers stay showers, because intermittency is not intensity and a rate cannot
see it. Thunder stays thunder. Freezing drizzle and freezing rain stay as
reported: the difference between them is a hazard level rather than a word, and a
provider that has said which one it is knows more than a millimetre count.

An absent rate changes nothing. Null is not evidence of a light hour.

**Provider health persists.**
What is known about each provider's recent failures is kept on disk, so an app
waking to a service that has been down for hours does not try it from scratch.
Nothing is aggregated and nothing leaves the device.

**Precipitation probability is a figure, not a mark.**
Intensity drives the curve's height. Probability is given as a number in the
hour's details rather than as opacity or a second mark, so the chart encodes one
thing well instead of two badly.

**The map is OpenStreetMap's own tiles; addresses come from Photon.**
OSM's tile policy prohibits bulk and pre-emptive fetching and offline downloads,
and asks for an identifying User-Agent and attribution on the map. Interactive
viewing is what it serves, so only the tiles in view are requested and the
credit sits on the map. No mapping library: the app already owns Web Mercator
for the radar.

Photon rather than Nominatim, although Nominatim measured faster: Nominatim's
policy forbids interactive type-and-see use, which Photon is built for. It is
asked once the map has been still for a moment, never during a drag, and nothing
depends on an answer arriving: a point is identified by its coordinates and only
described by its address.

**No feature modules.**
See Modules above. `:app` holds the whole UI and will keep holding it.

---

## Open questions

**A bundled typeface.**
The app currently uses the system sans, whose tabular figures are good and which
costs no APK size. A bundled face under the SIL Open Font License would give
tighter control of the numerals the whole design leans on. Not decided.
