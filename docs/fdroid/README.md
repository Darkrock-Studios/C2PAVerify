# Getting C2PA Verify into F-Droid

Working notes and the ready-to-submit build recipe for the main F-Droid repository
(`f-droid.org`). This is separate from IzzyOnDroid, which distributes our own signed APKs from
GitHub releases and needs none of this.

`com.darkrockstudios.apps.c2paverify.yml` in this directory is the metadata file to copy into
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) under `metadata/`.

## Eligibility: we're clear

F-Droid builds every app from source in its own build server and requires the whole dependency
tree to be FLOSS. The one dependency worth checking was `c2pa-android`, which ships a ~21 MB
prebuilt `libc2pa_c.so` per ABI:

- **Licensing is clean.** `c2pa-android` and the `c2pa-rs` native code it wraps are both
  Apache-2.0/MIT; JNA is Apache-2.0/LGPL-2.1+; the app is MIT.
- **The delivery route is explicitly allowed.** The
  [Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/) permits prebuilt FLOSS
  binaries from a named set of trusted Maven repos — Maven Central, Google Maven, OSS Sonatype,
  OSS JFrog, **JitPack.io** and Clojars — and states that "library dependencies must be built
  from source *or provided in a trusted Maven repo*".
- **The scanner agrees.** `fdroidserver/scanner.py` hardcodes an `allowed_repos` list containing
  `jitpack.io`, `repo1.maven.org/maven2`, `maven.google.com` and `plugins.gradle.org/m2` — every
  repo declared in `settings.gradle.kts`. Its binary-blob check (`.so`/`.aar`/`.jar`/`.zip`)
  walks only our own source tree, and the sole tracked binary here is
  `gradle/wrapper/gradle-wrapper.jar`, which the scanner explicitly exempts.

So **we do not need to build c2pa-rs from source**, and the `includeGroup("com.github.contentauth")`
restriction on the JitPack repo is exactly the hygiene reviewers look for.

## Pre-submission checklist

Already satisfied:

- [x] Public source repo with a real FOSS license file (MIT)
- [x] All dependencies FLOSS, no GMS/Firebase
- [x] Every release tagged `vX.Y.Z`, matching the versionName
- [x] Fastlane metadata in-repo: `title.txt`, `short_description.txt` (51 chars, under the 80-char
      limit), `full_description.txt`, 512×512 `icon.png`, 1024×500 `featureGraphic.png`, phone and
      ten-inch screenshots
- [x] We are the author, so no upstream permission issue

To do before submitting:

- [ ] Cut a release whose changelog is also named after the F-Droid versionCode (see
      [Per-release chores](#per-release-chores)) — `changelogs/4004.txt` is added for 0.2.3
- [ ] Test the recipe locally (below) so the merge request arrives with a known-good build

## Testing the recipe locally

`fdroidserver` is packaged for Debian/Ubuntu (`apt install fdroidserver`) or `pip install
fdroidserver`. A plain local build is enough to validate the recipe; reproducing the real build
environment additionally needs the buildserver VM/container.

```bash
git clone https://gitlab.com/fdroid/fdroiddata.git
cd fdroiddata
cp /path/to/C2PAVerify/docs/fdroid/com.darkrockstudios.apps.c2paverify.yml metadata/

fdroid readmeta                                          # metadata parses
fdroid lint com.darkrockstudios.apps.c2paverify          # style/field checks
fdroid rewritemeta com.darkrockstudios.apps.c2paverify   # canonical formatting, diff should be empty

# Build one split; --test keeps output in a temp dir. Repeat per versionCode (1004/2004/3004/4004).
fdroid build --test --verbose com.darkrockstudios.apps.c2paverify:4004
```

Expect four separate full Gradle builds per release — one per ABI. That's the cost of the split
APK layout, and it is normal for F-Droid.

## Submitting

The merge-request route is the one F-Droid recommends for app authors, since it hands maintainers
a recipe they can build directly:

1. Fork `gitlab.com/fdroid/fdroiddata`, add `metadata/com.darkrockstudios.apps.c2paverify.yml`
   on a branch, push.
2. Open the MR. GitLab CI runs lint plus a build of the recipe.
3. Mention in the MR description: we are the upstream author; the app is MIT; the only native
   dependency is `c2pa-android` (Apache-2.0/MIT) pulled from JitPack, which is a policy-allowed
   trusted Maven repo; four build entries because of per-ABI splits.

Alternatively an [RFP issue](https://gitlab.com/fdroid/rfp/-/issues) requests packaging without
supplying a recipe — slower, and we already have the recipe.

Be ready for these questions:

- **"Where does the `.so` come from?"** This is the one substantive thing a thorough reviewer may
  raise. `c2pa-android`'s Gradle build has a `downloadNativeLibraries` task that fetches
  `https://github.com/contentauth/c2pa-rs/releases/download/c2pa-$version/c2pa-$version-$target.zip`
  and packs it into the AAR, with no checksum verification. So although the artifact reaches us
  through an allowed Maven repo, the binary itself originates from a GitHub release rather than a
  source build. It passes the automated checks; if a reviewer objects on principle, the fallback is
  a module that compiles `c2pa-c-ffi` with `cargo-ndk` for four ABIs (the policy does allow
  Rust/rustup prebuilt toolchains, via a `sudo` scriptlet in the recipe) — expensive enough that we
  should only do it on request.
- **"Why does it need INTERNET?"** To fetch the official C2PA conformance trust list from
  `raw.githubusercontent.com/c2pa-org/conformance-public`. No analytics, no proprietary backend;
  images are never uploaded.

## Known risks

- **Toolchain currency.** We build with AGP 9.0.0, Gradle 9.4.1, `compileSdk 36.1`. If the
  buildserver's SDK/build-tools lag, the recipe may need `sudo` scriptlets to install what's
  missing.
- **`gradle/gradle-daemon-jvm.properties`** makes Gradle auto-provision JDK 21 from
  `api.foojay.io`. fdroidserver's scanner explicitly exempts that filename, but the buildserver
  supplies its own JDK; if provisioning is blocked there, deleting the file is the fix.
- **Different signature.** F-Droid signs with its own key, so the F-Droid build is not
  interchangeable with our GitHub/IzzyOnDroid APKs or the Play build — users switching sources
  must uninstall first. Reproducible builds (which would let F-Droid publish our own signed APKs)
  are out of reach while the native lib is a downloaded blob.

## Per-release chores

Once accepted, `fdroid checkupdates` picks up new `vX.Y.Z` tags automatically via the
`UpdateCheckData`/`VercodeOperation` fields, and generates the four build entries itself. Two
things stay manual on our side:

- **Changelogs.** F-Droid shows `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`,
  where the versionCode is the *split* code — 4004 for 0.2.3, not 4. Google Play uses the base
  code. So each release wants both `<code>.txt` (Play) and `<code + 4000>.txt` (F-Droid); the
  latter is what the F-Droid listing displays.
- **Build-entry order.** If the ABI offsets in `app/build.gradle.kts` ever change, update
  `VercodeOperation` and keep the build entries sorted by resulting versionCode — fdroidserver
  zips the sorted codes onto the last N entries in file order.
