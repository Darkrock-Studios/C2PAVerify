# AGENTS.md — C2PAViewer

Conventions for any agent (or human) working in this repo. See `PLAN.md` for the full design and
the living progress tracker.

## What this app is
An Android app that inspects and verifies **C2PA / Content Authenticity** metadata in still
images, MP4 video and PDF: pick or share a file in, see whether provenance data is present and
whether the signer is *trusted*, surface whether it is **AI-generated**, and drill into the full
manifest. `AssetFormats` is the list of what the bundled reader actually handles.

## CLI tooling available — prefer these over hand-rolling
- **`kotlin`** — the Kotlin toolchain CLI.
- **`android`** — the Android CLI. Use it for builds, installs, launching/managing
  emulators & devices, running instrumented/E2E tests, and SDK management. It can also install
  helper skills. Prefer it for anything device-related.
- **`gh`** — GitHub CLI. **This repo is not on GitHub yet** — do not assume a remote exists. Once
  it's pushed, use `gh` for PRs and issues.

## Build & stack
- **Gradle** (kts) with the `gradle/libs.versions.toml` **version catalog** — add every dependency
  and plugin there, reference via `libs.*`. (Amper was evaluated and declined; see PLAN.md.)
- Single `:app` module. AGP 9.2.1, Kotlin 2.2.10, Compose BOM 2026.02.01, Material 3, JDK 17,
  minSdk 29, targetSdk 36, namespace `c2paverify`.
- Key libs: Koin (DI), Room + KSP (structured storage), Preferences DataStore (simple prefs),
  Ktor (HTTP), Coil 3 + Telephoto (image + zoom), Napier (logging), kotlinx.serialization (JSON).
- **c2pa-android** comes from a *restricted* JitPack repo (`includeGroup("com.github.contentauth")`)
  and needs the JNA aar. It's Android/JVM-only with native binaries; keep it behind the
  `C2paReaderDataSource` interface and wrap its blocking calls on `Dispatchers.IO`.

## Architecture rules (enforced; a Konsist test guards them)
Strict layering, dependencies point **downward only**:

`ViewModel → UseCase → Service → Repository → DataSource`

- **DataSource** (L1): stateless. **Repository** (L2): stateful, combines data sources.
  **Service** (L3): stateful, combines repositories. **UseCase** (L4): stateless, orchestrates
  lower layers.
- **Stateful layers (Repository, Service) must NOT reference siblings** — only strictly-lower layers.
- **Use cases MAY compose other use cases** (they're stateless).
- **ViewModels access ONLY use cases, services, or repositories — never data sources.**
- **DI scope mirrors statefulness:** stateless items (data sources, use cases) → Koin `factory`;
  stateful items (repositories, services, the Room DB) → Koin `single`. One Koin module per layer.
- **KMP-clean seam:** `model/`, `repository/`, `service/`, `usecase/` contain **no `android.*`
  imports** so they can move to `commonMain` for a future iOS app. Android-only code lives in
  `datasource/` impls and `ui/`. Pure non-I/O domain logic (e.g. summary/AI detection) lives as
  functions in `model/`, not as use cases.

## Testing & TDD
- **Coverage is a requirement, not optional.** Write unit tests alongside each change.
- Prefer **JVM unit tests** (no device): parsing, summary/AI pure functions, cert/trust logic,
  repos/use cases/VMs (kotlinx-coroutines-test + Turbine + MockK + Koin-test).
- Keep the **single on-device E2E smoke test** green (Compose UI / UiAutomator, run via `android`).
- **Bug-fixing is TDD:** first write a **failing test that reproduces the bug**, confirm it's red,
  fix the code, confirm it's green. Most C2PA bugs are reproducible off-device via fixture JSON.
- **Test fixtures** come from https://github.com/c2pa-org/public-testfiles: vendor a small
  curated subset (no-manifest, valid signed, tampered, AI-generated, multi-ingredient) into
  `app/src/test/resources/` and `app/src/androidTest/assets/`. Note here when refreshed.
  The `2.2/` tree on `main` is an empty scaffold: `.gitkeep` only, no binaries. Real files are
  `legacy/1.4/image/jpeg/` (JPEG only) and, for every other format, the `contrib/google/assets/`
  corpus on the unmerged `darrellkindred/valtest` branch. Per-format fixtures refreshed 2026-08-28
  into `app/src/androidTest/assets/c2pa/upstream/`; see the README there.

## UI conventions
- Jetpack Compose + Material 3 only. Adaptive multi-pane via `material3.adaptive`
  (`NavigableListDetailPaneScaffold`) — deep-dive beside the asset on large screens.
- **Edge-to-edge** app-wide: content scrolls under translucent system bars, but resting content is
  inset-padded so it never sits *under* a bar. Drive padding from `WindowInsets`
  (`systemBars`/`safeDrawing` as `contentPadding`); no `fitsSystemWindows`, no hardcoded bar heights.
- Predictive back enabled (`android:enableOnBackInvokedCallback="true"`).
