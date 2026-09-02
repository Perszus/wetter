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
- The README says so, and the store listings should not imply otherwise.

The `applicationId` stays the same on both (`lv.bolwarra.wetter`). Forking it per
store would split the listing, the reviews and the install count to solve a
problem it does not actually solve.

## Before any release

The listing should not go live until the app does the thing the listing
describes. As of 0.1.0 there is no precipitation timeline, which means there is
nothing worth screenshotting and the store copy would be describing an
intention. **Publish the first release when the timeline exists.**

Then, in order:

1. `./gradlew clean check assembleDebug` — everything green.
2. Update `CHANGELOG.md`: move `Unreleased` to the new version, dated.
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
   `versionCode` is a plain integer, incremented by one, never reused and never
   decreased. Both are written by hand — see the comment in that file for why
   they are not derived from git.
4. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. Both
   stores read it. Play truncates at 500 characters.
5. Commit, then tag: `git tag -a v0.2.0 -m "Wetter 0.2.0"` and
   `git push --follow-tags`. The tag must match the pattern F-Droid watches,
   `^v[0-9.]+$`, and must point at the commit whose `versionCode` you just set.

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

Submitting the app the first time:

1. Check the build works the way theirs will:
   ```sh
   git clone https://github.com/Perszus/wetter /tmp/wetter-clean
   cd /tmp/wetter-clean && git checkout v0.1.0
   ./gradlew assembleRelease        # must succeed with no keystore.properties
   ```
2. Open a *Request For Packaging* issue at
   <https://gitlab.com/fdroid/rfp/-/issues>, or go straight to a merge request
   against <https://gitlab.com/fdroid/fdroiddata> adding
   `metadata/lv.bolwarra.wetter.yml`. The content is kept in this repository at
   [`fdroid/lv.bolwarra.wetter.yml`](../fdroid/lv.bolwarra.wetter.yml).

After that, `AutoUpdateMode: Version` means F-Droid picks up new tags on its own.
A release usually appears within a few days. Nothing needs doing per release
beyond pushing the tag — unless the build recipe changes, in which case update
both copies of the yml.

### Keeping F-Droid able to build it

These are the constraints that break an F-Droid build. They are all already
satisfied; the point is not to break them.

- **No proprietary dependency**, nothing from Google Play Services, no
  library that F-Droid's scanner flags as non-free.
- **No API key baked into the source.** Neither weather service needs one, which
  is part of why they were chosen.
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
