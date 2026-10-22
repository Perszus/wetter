# Releasing

Wetter is published in two places, from one commit and one set of store text.

|  | Google Play | F-Droid |
|---|---|---|
| Artefact | `.aab`, uploaded | `.apk`, built by them |
| Built by | us | F-Droid's servers, from a git tag |
| Signed by | Google (Play App Signing) | F-Droid |
| Store text | `fastlane/metadata/…` | the same directory |
| Needs a keystore | yes | no |

## The signature is different on each store, and that is permanent

Play App Signing has been mandatory for new apps since 2021: whatever we upload,
Google re-signs with a key they hold. F-Droid signs with theirs. The two APKs
therefore have different signatures, and **Android will not update one over the
other**.

There is no way around this. What it means in practice:

- Somebody who installs from F-Droid must uninstall before installing from Play,
  and vice versa. Uninstalling deletes their saved locations.
- The store listings should not imply otherwise.

The `applicationId` stays the same on both (`lv.bolwarra.wetter`). Forking it per
store would split the listing, the reviews and the install count to solve a
problem it does not actually solve.

## Before any release

### What `./gradlew check` holds

Run `./gradlew clean check`. Besides the tests, lint and formatting, it fails on:

- any compiler warning, in any module or test source set;
- an unused resource, or a version check the minimum SDK makes pointless;
- a widget picker preview that no longer matches what the widget draws
  (`WIDGET_PREVIEWS=write ./gradlew :app:testDebugUnitTest --tests
  '*WidgetGalleryTest.pickerPreviewsAreCurrent'` redraws them);
- a manifest permission missing from the README or the privacy policy;
- a contacted service missing from the privacy policy, the README, the store text
  or the About credits;
- store text over F-Droid's limits, or no changelog for the `versionCode`;
- a version that disagrees between the build, the changelog and
  `fdroid/lv.bolwarra.wetter.yml`;
- a source file without its licence header;
- a document mentioned anywhere that is not published.

### What a person checks

On a phone or emulator, with the build being released:

1. Every screen in both themes: Today, Week with a day open, Month, Advanced,
   Places, the pin picker, the rain map, every Settings page.
2. The same at the largest system font size, and once on a tablet-sized window.
3. Each widget placed and resized, in light and dark mode, against its picker
   preview.
4. A place in a half-hour time zone and one near the date line, against the
   local clock.
5. Release notes and store text against what the build actually does.

### Cutting it

1. Update `CHANGELOG.md`: date the version being released.
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and both in
   `fdroid/lv.bolwarra.wetter.yml`. `versionCode` is a plain integer, incremented
   by one, never reused and never decreased.
3. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, at most
   500 characters.
4. Check the release build is reproducible (below).
5. Commit, then tag: `git tag -a v0.2.0 -m "Wetter 0.2.0"` and
   `git push --follow-tags`. The tag must match `^v[0-9.]+$` and point at the
   commit whose `versionCode` was just set. A published tag is never moved.

## Google Play

Needs `keystore.properties` in the repository root — gitignored, and the only
copy of the upload key. **If it is lost, the Play listing cannot be updated by
anyone, ever.** Keep it and the keystore off this machine as well as on it.

```properties
storeFile=../keys/wetter-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Then:

```sh
./gradlew bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

`bundleRelease` fails immediately if the keystore is missing, rather than
letting a long upload be rejected at the far end. `assembleRelease` deliberately
does not — see below.

Upload through the Play Console. First time only, the console also needs:

- **Privacy policy URL** — required for every app. Use
  <https://github.com/Perszus/wetter/blob/main/docs/privacy-policy.md>, or a
  GitHub Pages copy if a rendered page is preferred.
- **App icon**, 512×512 PNG, no alpha.
- **Feature graphic**, 1024×500.
- **Screenshots**, at least two. Put them in
  `fastlane/metadata/android/en-US/images/phoneScreenshots/` so F-Droid gets
  them too.
- **Data safety form.** The answers are below and should not need thinking about
  again.

### Data safety answers

| Question | Answer |
|---|---|
| Does your app collect or share any required user data types? | **No** |
| Is all user data encrypted in transit? | Yes — every request is HTTPS |
| Do you provide a way for users to request data deletion? | Not applicable; no data is collected. Uninstalling removes local storage |
| Data types collected | None |
| Data types shared | None |

Approximate location is *used* to fetch a forecast but is neither collected nor
shared: it is sent to a third-party weather service to answer a request and is
not stored by us or transmitted anywhere else. Play's form has no better box for
this; "not collected" is the accurate answer to what it asks.

### Content rating and category

- Category: **Weather**
- Content rating questionnaire: no to everything. The app has no user-generated
  content, no communication, no purchases, no ads.

## F-Droid

F-Droid builds from the tag, on their servers, with no keystore present. That is
why `assembleRelease` succeeds unsigned rather than failing — a guard there would
break their build.

Submitting the app the first time follows F-Droid's own documented process
(<https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md>), and it
uses their tooling rather than hand-written YAML.

The metadata is ready in this repository, so the submission is a merge request
straight to fdroiddata; a Request For Packaging is for asking somebody else to
package an app and is not needed.

**1. Install fdroidserver and fork fdroiddata.**

```sh
pip install git+https://gitlab.com/fdroid/fdroidserver.git
git clone https://gitlab.com/YOUR_USERNAME/fdroiddata.git
cd fdroiddata
```

**2. Generate the metadata rather than writing it.**

```sh
fdroid import --url https://github.com/Perszus/wetter --subdir app
```

This writes `metadata/lv.bolwarra.wetter.yml`, which then needs editing by hand
to add what it cannot infer. The finished result is kept in this repository at
[`fdroid/lv.bolwarra.wetter.yml`](../fdroid/lv.bolwarra.wetter.yml) and can be
copied across, with one change: fdroiddata wants the `commit` of a build block to
be the tag's full commit hash (`git rev-parse v1.0^{commit}`), not the tag name.
The copy here keeps the tag, because it cannot know the hash of its own commit.
It is otherwise in `fdroid rewritemeta` canonical form and passes `fdroid lint`
with no findings.

**3. Check it with their tools before opening anything.**

```sh
fdroid readmeta                        # the file parses
fdroid rewritemeta lv.bolwarra.wetter  # canonical formatting; should be a no-op
fdroid checkupdates lv.bolwarra.wetter # the tag pattern finds the release
fdroid lint lv.bolwarra.wetter         # no findings
fdroid build -v -l lv.bolwarra.wetter  # builds the way their servers will
```

`fdroid lint` validates `Categories` against `config/categories.yml` in the
fdroiddata checkout, so it only means anything when run from inside one. There is
no category called `Time` — the app is `Weather`.

**4. Open a merge request** against <https://gitlab.com/fdroid/fdroiddata> once
the CI pipeline on your fork passes.

After that, `AutoUpdateMode: Version` means F-Droid picks up new tags on its
own, and a release usually appears within a few days. Nothing needs doing per
release beyond pushing the tag — unless the build recipe changes, in which case
update both copies of the yml.

### Why the metadata carries no description

`Summary` and `Description` are valid fields, and are deliberately absent.
F-Droid reads `fastlane/metadata/android/` from this repository instead — the
same directory Play reads — so the store text has one source rather than two
that drift.

### What the inclusion policy requires

Checked against <https://f-droid.org/en/docs/Inclusion_Policy/>. All of it is
already true, and the point is not to break it:

- **FLOSS licence** recognised by the FSF, OSI or DFSG. GPL-3.0-or-later is.
- **No proprietary tracking, advertising or analytics libraries.** These are
  "strictly forbidden", and there are none.
- **Built with a 100% FLOSS toolchain.** No Oracle JDK, no proprietary build
  step.
- **Binary dependencies only from trusted sources** — Maven Central, Google's
  Maven repository for AndroidX, the Android SDK. No vendored blobs.
- **No executable downloaded at runtime** without explicit opt-in. Wetter
  downloads JSON and map and radar images, nothing executable.
- **A distinct application id.** Duplicates are automatically rejected.
- **Actually maintained and actually useful** — not a demo, not a rebranding,
  not a website in a WebView.

### Keeping F-Droid able to build it

These are the constraints that break an F-Droid build. They are all already
satisfied; the point is not to break them.

- **No proprietary dependency**, nothing from Google Play Services, no
  library that F-Droid's scanner flags as non-free.
- **No API key baked into the source.** None of the services the app uses needs
  one, which is part of why they were chosen.
- **Buildable from a clean checkout** with `./gradlew assembleRelease` and
  nothing else — no prebuild step, no network fetch outside Gradle, no binary
  blob that cannot be rebuilt.
- **`dependenciesInfo` stays disabled.** The metadata AGP would otherwise embed
  is signed with a Google key and is not reproducible from source.
- **Locales are pinned** in `androidResources.localeFilters`, so the build does
  not vary with what is installed on the machine running it.
- **`vcsInfo` stays disabled** for release. AGP otherwise embeds a description
  of the git checkout the build came from, which was the one and only thing that
  differed between two independent builds of the same commit.

### The release build is reproducible — check it

Two builds of the same commit produce a byte-identical APK. That is worth
keeping true, so verify it when anything about the build configuration changes:

```sh
git clone --depth 1 https://github.com/Perszus/wetter /tmp/wetter-repro
cd /tmp/wetter-repro && ./gradlew assembleRelease
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
# must match the same command run anywhere else on the same commit
```

If the hashes diverge, unzip both and diff the entry hashes — that names the
offending file immediately, which is how `version-control-info.textproto` was
found.

This is not required by either store: F-Droid builds and signs the APK itself.
It matters because it means anyone can confirm that the published binary
contains this source and nothing else, which is the whole argument for shipping
free software in the first place.

## After a release

- Check the F-Droid build at <https://monitor.f-droid.org/builds> if it does not
  appear.
- Add a GitHub release pointing at the tag, with the changelog entry. This is
  where people who use neither store will look.
