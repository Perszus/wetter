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
Play-specific in the app to flavour — no billing, no services, no proprietary
anything.

**The two stores will carry different signatures, and this is not fixable.**
Play App Signing is mandatory for new apps, so Google holds the key for anything
distributed there. Android will not update an F-Droid install over a Play one or
the reverse. The README says so plainly rather than letting somebody discover it
when their saved locations vanish.

**Release signing is optional, and deliberately so.**
`assembleRelease` succeeds without a keystore and produces an unsigned APK,
because that is exactly what F-Droid's build servers do and failing there would
break them. `bundleRelease` — which only ever exists to be uploaded to Play —
fails immediately without one, so an unsigned upload is caught in a second
rather than at the end of a long transfer.

**`versionCode` and `versionName` are written by hand.**
Deriving them from the repository, by commit count or `git describe`, makes the
build depend on clone depth. F-Droid does not guarantee a full clone, and a
version that changes with how the source was fetched is not a version.

**Locales are pinned to what is actually translated.**
Otherwise the resource set varies with whatever is installed on the machine
running the build, which is one more way two builds of one commit can differ.

**GPL-3.0-or-later, with an additional permission under section 7 for
distribution through application stores.**
Play's Developer Distribution Agreement can be read as imposing the further
restrictions section 6 forbids. The copyright holder may distribute their own
work however they like, so this changes nothing today — but it would become an
obstacle the moment somebody else contributes, and it is far easier to grant now
than to collect from a dozen people later. The permission is narrow: it removes
an obstacle to distribution and grants nothing else. See LICENSE-EXCEPTION.txt.

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
only way out is a `WeatherRepository` and a list of attributions. The split paid
for itself immediately — it turned up that the application had been holding a
Ktor `HttpClient`, which it had no business knowing existed.

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
MET Norway reports it, and in a Baltic winter it is most of what falls.

**Sunrise, sunset and daylight are computed rather than fetched.**
The NOAA solar position equations, in `SolarTime`. Not every provider publishes
them, and the timeline's night shading is not optional decoration — an unshaded
03:00 reads as an afternoon. Computing it costs one file of arithmetic, works
offline, and agrees with itself across providers. The alternative was a second
network call to a second service with a second set of terms.

---

## Data flow

**Offline-first means reads never touch the network.**
`WeatherRepository.observe` answers from the cache. `refresh` is a separate
call, and its failure leaves what is on screen exactly where it is.

**Thirty minutes of freshness.**
Roughly how often the models publish a new run. Refreshing more often spends
battery and somebody else's bandwidth to redraw the same numbers. A manual
refresh ignores the window — somebody who asks has a reason.

**Cache keys round coordinates to four decimals.**
A position read from the device differs in the far decimals every time. An
exact-match key would make every refresh a miss and every cached forecast
unreachable.

**The forecast cache is in memory, for now.**
Explicitly a placeholder. It gives the offline-first *flow* without yet giving
offline-first *behaviour*: closing the app empties it, and a widget could not
read it. `ForecastCache` is the interface Room will implement in the persistence
phase, which is the whole reason it exists with one implementation today.

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
The entire object graph is `WetterContainer` — about a dozen lines, read top to
bottom. A framework would add a compiler plugin, an annotation vocabulary and a
layer of indirection to solve a problem this app does not have.

**No charting library.**
The precipitation timeline will be ordinary Compose layout, with `Canvas`
confined to the one component that genuinely needs to draw. Making `Canvas` the
foundation of the app would make every future change a drawing problem.

**No Material You dynamic colour.**
The palette encodes meaning: precipitation owns the only saturated hue, and
temperature is quieter than rain. A wallpaper-derived scheme would break that
relationship on most devices.

**No bottom navigation bar.**
Locations and settings are visited a few times a month. Spending a permanent
strip of every screen advertising them costs more than the chevron beside the
location name.

**No type-safe navigation routes.**
No destination carries an argument, so the generated route types would buy
nothing. Revisit if one ever does.

**Notifications are not built and will not be until the core app is good.**
An elaborate notification system on top of an app that cannot yet draw a rain
timeline would be the wrong thing done well.

**No feature modules.**
See Modules above. `:app` holds the whole UI and will keep holding it.

---

## Open questions

**A bundled typeface.**
The app currently uses the system sans, whose tabular figures are good and which
costs no APK size. A bundled face under the SIL Open Font License would give
tighter control of the numerals the whole design leans on. Not decided.

**Where provider health should live.**
In memory today. If real use shows the app repeatedly waking to a provider that
has been down for hours, it should persist.

**How precipitation probability should be shown.**
Intensity drives bar height. Probability could be opacity, a second mark, or
nothing at all — showing both risks a chart that encodes two things badly rather
than one thing well. To be settled when the timeline is built.
