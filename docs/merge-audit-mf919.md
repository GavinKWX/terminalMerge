# Merge Audit — ShareCommerce.Terminal (MF919) + MF919 Pro

**Date:** 2026-09-03 · **Scope:** feasibility audit only, no code changes · **Verdict:** merge is feasible and worth doing

---

## 1. Executive summary

Two Android payment-terminal apps are maintained in parallel by the same two-person team.

| | ShareCommerce.Terminal ("OLD") | MF919Pro ("PRO") |
|---|---|---|
| repo | `share-commerce/ShareCommerce.Terminal` | `share-commerce/ShareCommerce.Terminal_MF919Pro` |
| applicationId | `com.sc.mf919` | `com.sc.mf919pro` |
| version | 2.2.26 | 1.0.04 |
| minSdk / targetSdk / compileSdk | 24 / 33 / 33 | 29 / 35 / 35 |
| UI | ~55 Activities, manual Intent navigation | 1 Activity + Nav graph, ~50 Fragments |
| source | 53 Java + 250 Kotlin, ~81k LOC | 40 Java + 233 Kotlin, ~55k LOC |
| history | 1135 commits since 2022 | 117 commits since Nov 2025 |

The two repos have **unrelated git histories** — different root commits. PRO was created by copy-paste, not by forking.

They already share the same hardware SDK (byte-identical `ysdk_6.14` jar), the same ISO 8583 stack, the same TMS protocol, the same DB schema shape, and the same 10-flavor `client` dimension.

**The cost of not merging, measured:** in 2026 alone, **14 commit subjects are character-for-character identical across both repos** — `Enhance Manual Settle also update Settle_Last_Tag`, `Fix Pos Condition for Paydee Moto and BF63 Crash`, `add SettlementBlock handling for BSN CZ`, `enhance autosettle in hibernate`, and ten more. Every fix is being written twice by the same two engineers, who together land ~25–40 commits/month across the pair. One OLD commit is literally titled `take aux code from mf919 pro`.

**Recommendation:** a shared `:core` Android library plus two app modules, in a new repository, delivered in phases. All five stated constraints are satisfied by this structure — three of them automatically.

### The five constraints, at a glance

| # | constraint | verdict |
|---|---|---|
| 1 | Keep both bundle ids `com.sc.mf919` + `com.sc.mf919pro` | **Satisfied directly** — each app module sets its own |
| 2 | `TransactionReceiver` stays Java on MF919, Kotlin on Pro | **Satisfied by construction** — each stays in its own module |
| 3 | MF919 HTTP: old integration only, no `IsOldIntegration` key | **Satisfied** — one injected flag; OLD has no such concept today anyway |
| 4 | Pro supports both old and new integration | **Satisfied** — PRO's existing auto-detect is preserved unchanged |
| 5 | Attend UI keeps its current look on each device | **Satisfied by construction** — attend screens never move |

---

## 2. How far apart are they?

Measured with `norm.sh` (in this folder): the package token is rewritten on both sides, whitespace stripped, then compared. Re-runnable at any time.

| bucket | count |
|---|---|
| identical after package rename | **42** |
| same name, drifted | **77** |
| old-only | **125** |
| pro-only | **91** |

The old-only/pro-only figures are inflated by the Activity to Fragment rename — `AttendActivity.kt` vs `AttendFragment.kt` counts once on each side. Those are the same screens rewritten, not missing features.

**Worst-drifted shared files** (changed lines / file length):

| file | drift |
|---|---|
| `java/activity/DataAdapter.java` | 2395 / 2483 |
| `java/activity/Database.java` | 2285 / 2310 |
| `kotlin/helper_common/HTTPServer.kt` | 2226 / 1829 |
| `kotlin/activity/MainActivity.kt` | 1308 / 737 |
| `kotlin/helper_common/iso/IsoActivity.kt` | 1103 / 3044 |
| `kotlin/database/infrastructure/DbHandler.kt` | 463 / 654 |
| `kotlin/helper_common/ServiceHolder.kt` | 445 / 741 |
| `kotlin/activity/AppServices.kt` | 366 / 788 |

**Resources:** 153 identical, 24 differ, 87 old-only, 106 pro-only. `strings.xml` looks catastrophic at 703 diff lines but is 340 of 344 keys in common with only 4 unique each way — the diff is pure reformatting.

**Jars are already aligned.** Every jar both projects use is byte-identical, including `ysdk_6.14` (md5 `39e92cd8...`). The only divergence is a stale older YSDK copy each side keeps around, commented out in both build files.

---

## 3. Target structure

```
newrepo/
 - core/            Android library, namespace com.sc.terminal.core, minSdk 24
 - app-mf919/       applicationId com.sc.mf919      x 10 client flavors
 - app-mf919pro/    applicationId com.sc.mf919pro   x 10 client flavors
```

### Why two app modules, not a `device` flavor dimension

Both projects already use a `client` flavor dimension with the same 10 flavors, so a single-module design would need a second dimension crossed with it — 10 x 2 x 3 = 60 build variants. More decisively, **AGP permits only one `namespace` per module**, so one side would lose its `BuildConfig`/`R` package.

### Does this change the app-to-app intent name POS vendors depend on? No.

The vendor contract is the component name `applicationId/fully-qualified-class`. Under this structure both halves are byte-identical to today:

| | today | after merge |
|---|---|---|
| MF919 | `com.sc.mf919/com.sc.mf919.java.activity.TransactionReceiver` | unchanged |
| MF919 Pro | `com.sc.mf919pro/com.sc.mf919pro.kotlin.activity.TransactionReceiver` | unchanged |

Each app module keeps its own `namespace` **and** its own `applicationId`, so the existing relative manifest entry `android:name=".java.activity.TransactionReceiver"` (OLD manifest line 215) still resolves to exactly the class it resolves to now. The `ACTION_SEND` + `text/plain` implicit filter stays in `app-mf919`'s manifest; `.dev`/`.uat` `applicationIdSuffix` still applies per app; `taskAffinity="${applicationId}.recovery"` and the OLD-only `${applicationId}.provider` authority resolve per app as before. The return leg is unaffected under any design — it targets the vendor's own `Package_Name`/`Activity_Name` extras.

This is a concrete argument *against* the single-module alternative. There, `namespace` is module-wide: with namespace `com.sc.mf919`, PRO's manifest entry `.kotlin.activity.TransactionReceiver` (PRO manifest line 82) would silently resolve to `com.sc.mf919.kotlin.activity.TransactionReceiver` — a class that does not exist. It is fixable by writing every `android:name` fully-qualified, but the failure mode is a vendor-visible `ActivityNotFoundException` from a one-character manifest slip, on a surface already integrated with third parties.

> **Regression test, once per app per phase:** launch each `TransactionReceiver` by explicit component name from a stub vendor app, plus one implicit `ACTION_SEND` launch against MF919, and confirm the reply intent arrives with the expected extras.

### What moves into `:core`, in order of difficulty

**Free — already package-neutral.** `helpers/`, `tms/`, `enums/` sit in root packages (`package helpers`, `package tms.handlers`, `package enums`) under `app/src/main/java/`, *not* under `com.sc.mf919*`. They move with **zero renaming**: ~4.0k LOC, of which `tms` is 30 of 44 files byte-identical and `enums` 5 of 7.

**Next tier.** `kotlin/database` (3.2k/3.1k LOC), `kotlin/scheduler`, `kotlin/data_enum`, `java/utils`, `java/device`. Both `DbHandler`s already sit at `DATABASE_VERSION = 1` and run their own migration mechanism, so the 28-vs-3 migration gap is a data question, not a schema-version collision.

**Hard tier.** `HTTPServer`, `IsoActivity`, `ServiceHolder`, `MfHelper`, `TmsHelper`, `AppServices`.

**Added 2026-09-07 — the four `iso/*/IsoStepsNew.kt` files belong in the *next* tier, not this one.** They were never classified above. Each is ~93% identical to its Pro twin (90–119 diff lines of ~1450), so they are the largest cheap de-duplication in the programme — the opposite of `IsoActivity` beside them. See §13; the prerequisite is R8, not a seam.

---

## 4. Constraint-by-constraint verdict

### (1) Two bundle ids — satisfied directly

Each app module sets its own `applicationId` and `namespace`. Existing `.dev`/`.uat` suffixes on debug/stag carry over unchanged. Both fleets continue to upgrade in place.

### (2) TransactionReceiver split — satisfied, and simpler than expected

> **Correction to the brief:** `TransactionReceiver` is an **`AppCompatActivity`** in both projects, not a `BroadcastReceiver` — the name is historical. The vendor contract is therefore the *exported component name + intent extras*, not a broadcast action. Neither project has a `BroadcastReceiver` anywhere in the vendor path.

- **OLD** — Java, 1090 lines, one monolithic `switch`. `exported="true"` **with** an `<intent-filter>` for `ACTION_SEND` + `DEFAULT` + `image/*` + `text/plain`, so vendors can also launch it implicitly.
- **PRO** — Kotlin, 90 lines, delegating to `TransactionParser` then `TransactionRouter` then 5 use cases. `exported="true"`, `launchMode="singleTask"`, **no intent-filter at all** — explicit component launch only.

Both stay in their own app module, in their own language, with their manifest entry preserved verbatim. The OLD `<intent-filter>` block must be kept character-for-character.

**Reply-leg difference to flag:** OLD's `TransactionTransmitter` sets `ACTION_SEND` and uses `getLaunchIntentForPackage`; PRO's sets no action and no `FLAG_ACTIVITY_NEW_TASK`. A vendor filtering on `ACTION_SEND` behaves differently between them. Keep both transmitters per-app — do not unify.

### (3) + (4) Integration modes — satisfied cleanly

**OLD has no `isOldIntegration` concept at all.** A grep across the whole OLD source tree for `isOldIntegration|IsOldIntegration|oldIntegration|newIntegration|PaymentChannel|PreAuthType|PaymentCode` returns **zero matches** (verified 2026-09-03). OLD's single schema *is* what PRO calls "old integration", and PRO's `oldIntegrationType()` is a faithful port of it.

So the shared `HTTPServer` needs exactly one injected policy flag:

```
supportsNewIntegration = false   // app-mf919     -> always oldIntegrationType()
supportsNewIntegration = true    // app-mf919pro  -> keep current auto-detect
```

That gives MF919 old-only behaviour with no key required, exactly as specified, and leaves Pro untouched.

### (5) Attend UI unchanged — satisfied by construction

Attend screens live in their app modules and are never touched. Recording the delta here so nobody "tidies" it later:

| | OLD `activity_attend.xml` | PRO `fragment_attend.xml` |
|---|---|---|
| root | `NestedScrollView` into vertical `LinearLayout` | `ConstraintLayout` |
| top | full-bleed merchant logo `ImageView` | 45%-height banner slider (`FragmentImageSlider`) |
| primary action | 200/300dp **square** SALE tile | full-width SALES pill |
| secondary | 4 buttons (INFO, PRINT, SETTLEMENT, VOID) | 6 buttons (+ HISTORY, PAPER ROLL, CONTACT US, APP INFO) |
| bottom nav | in-layout | in `activity_main.xml` |
| sizing | hard-coded dp throughout; no dimens file; SR800 handled at **runtime** via `ServiceHolder.SR800_MODEL` in 13 files | hard-coded dp throughout; an unreferenced `values/dimen.xml`; **no SR800 handling of any kind** |
| click wiring | XML `android:onClick` | Kotlin debounced listeners |
| icons | `sale_icon`, `logon_icon`, ... | `ai_sales`, `ai_icon01` ... `ai_icon11` |
| Oxpay variant | `AttendActivityOxpay` exists | **no Oxpay screen at all in PRO** |

> **Correction (2026-09-07).** An earlier version of the sizing row claimed MF919 used
> `@dimen/attend_*` with a `values-h500dp` bucket for the SR800, and that only PRO hard-coded
> its dp values. That was wrong in both directions and was never verified: neither app
> references `@dimen/` anywhere (0 distinct references in either res tree), no `values-h500dp`
> bucket exists, no `attend_*` dimen exists, and it is PRO — not MF919 — that carries a
> `values/dimen.xml`, itself unreferenced. MF919's only screen-size adaptation is the runtime
> `SR800_MODEL` branch; the sole resource qualifier in either app is MF919's `values-night`.
>
> This is not academic. Because PRO has no SR800 handling at all, its card-payment background
> was a fixed `550dp` on a unit whose usable height is ~439dp (480x480 at density 175), and it
> overflowed on hardware. Found and fixed on the SR800 on 2026-09-07 by switching the view to
> `0dp` (match constraints), which it was already constrained top and bottom for.


`attend_denomination` is the exception — the two layouts differ by a single 5-line diff and are trivially shareable if ever wanted.

---

## 5. Risk register

| # | risk | severity | evidence / mitigation |
|---|---|---|---|
| **R1** | Toolchain split must be unified | high | OLD: AGP 8.13 / Gradle 8.13 / Kotlin 1.8.0 / Java 11. PRO: AGP 8.6.1 / Gradle 8.7 / Kotlin 1.9.0 / Java 1.8. PRO is on the *older* AGP despite being the newer codebase. Unify on AGP 8.13 + Gradle 8.13 + Kotlin 1.9.x + Java 11; adopt PRO's `libs.versions.toml` (OLD has no catalog). |
| **R2** | `:core` must compile at minSdk 24 | **low — de-risked** | Only two shared-candidate files use API-sensitive APIs: `HTTPServer.kt` (`CompletableFuture`, API 24) and `IsoActivity.kt` (`java.util.function.Consumer`, API 24). Both are exactly at minSdk 24, and **OLD already ships `Consumer` at minSdk 24 today**. No `java.time` anywhere outside Pro's UI layer; no desugaring configured or needed. |
| **R3** | Navigation seam | high | Shared `HTTPServer` starts concrete Activities on OLD but emits `UiEvent.FragmentNavigation(R.id....)` on PRO — a library module cannot reference either app's `R`. **`AppBus` already exists in both, near-identical**, and is the natural seam: a neutral `AppScreen` enum in `:core`, translated per app (`startActivity` vs `navController.navigate`). Cost: one enum + two ~50-line translators. |
| **R4** | Long-lived merge branch rots | high | ~25–40 commits/month across both repos from the same two engineers. Phase the work; keep each phase short and mergeable. |
| **R5** | Migration-count asymmetry | medium | OLD has 28 migrations (`Migration123` to `Migration2221`), PRO has 3 (`1001`/`1002`/`1004`). Both at `DATABASE_VERSION = 1`. Migrations stay per-app; only `DbHandler` + repos are shared. |
| **R6** | Feature asymmetry — *decided 2026-09-04, see §11.3* | medium | OLD-only: BNPL flows, Oxpay screens, SR800 gating, MDB/vending controller, `QrPayTableRepo`, boot receiver, `FileProvider`, RS232 transport. PRO-only: use-case layer, `intent_helper`, TTS, lucky draw / paper-roll TMS, image slider, `CounterGuard`, `LogRedact`. Decide per feature: `:core`, one app, or drop. |
| **R7** | Directory-case mismatch | low | `Helpers/` (OLD) vs `helpers/` (PRO) — both declare `package helpers`. Works on Windows, breaks on case-sensitive CI. |
| **R8** | **MF919 allocates transaction counters without a lock** — *found 2026-09-07, see §12* | **high** | PRO allocates STAN/invoice/batch through one `synchronized` read-modify-write (`IsoBatchInfoRepo.allocateCounter`, 26 call sites). MF919 has **no** such function and does read-increment-write inline at 36 `updateBatchInfo` sites. These tables are written from the nanohttpd worker, the ISO thread, the schedulers and the UI thread (§9), so overlapping transactions can take the same counter — duplicate STAN/invoice to the acquirer. Live defect in the shipping fleet, not a merge artefact; also blocks sharing the ISO stack. |

---

## 6. Pre-existing defects found during the audit

Worth fixing whether or not the merge proceeds.

| finding | location |
|---|---|
| Live `E99` / `"TODO"` placeholder on the old-integration else-branch | PRO `TransactionRouter.kt:92-95` |
| Retry cache is dead code — `RETRY_CACHE_WINDOW_MS = 0L`, intended `5_000L` commented out on the line above | PRO `HTTPServer.kt:85` |
| `TransactionResultFragment` omits `OriTransactionRRN` / `OriTransactionApprovalCode`, which OLD's `TransactionResultActivity` writes — silent contract regression for vendors reading them off a void result | PRO |
| `SHC007` means "Terminal System Error" on OLD, "Product Is Not Configured" on PRO | both |
| `WebSocketClient.kt` sits in `kotlin/helper_common/` but declares `package com.sc.mf919pro.kotlin.activity`; OLD's same-named file correctly declares `...kotlin.helper_common`. A stale `com.sc.mf919...BaseActivity` import is present but commented out | PRO |
| `ExampleInstrumentedTest.kt` asserts `packageName == "com.sc.mf919"` — stale, would fail if run | PRO `src/androidTest/java/com/sc/mf919/` |
| `bcprov-jdk15on-160.jar` and `sun.misc.BASE64Decoder.jar` sit in `app/libs/` but are declared as dependencies in neither project | both |
| Full Compose BOM + `material3` + `activity-compose` on the classpath with `compose` **not** enabled as a build feature; only usage is a stray import | PRO `ContactUsFragment.kt:8` |
| `androidx.viewpager2` used but not declared — resolves transitively via `me.relex:circleindicator` | PRO `AttendDenominationFragment.kt:19` |
| `special` is a declared flavor with no `src/special/` directory | both |
| Four call sites bypass `HelperCommon.getHomeScreenIntent:291` and hard-code `AttendDenominationActivity`, ignoring `UNATTENDED_MODE` | OLD `CardPaymentActivity.kt:226`, `DenominationPaymentOptionActivity.kt:363`/`:384`, `GenerateQrActivity.kt:815` |

---

## 7. Should MF919 adopt Pro's Fragment/Nav structure?

**Verdict: Pro's structure is better, but converting is out of scope for this merge.**

| | OLD (Activities) | PRO (Fragments + Nav) |
|---|---|---|
| LOC for the same screen set | ~35.2k | **~24.1k (-31%)** |
| `<activity>` in manifest | 64 | 7 |
| navigation call sites | 247 raw `startActivity(` | 110 `navigateSafe`/`navigateToHome` |
| wrong-destination guard | none | `BaseFragment.navigateSafe()` |
| nav destinations | — | 44 in `nav_graph.xml` |

It also removes a live class of bug — the four `getHomeScreenIntent` bypasses in section 6 become structurally impossible, because PRO centralises the same decision in `MainActivity.kt:479-491` and `BaseFragment.navigateToHome:139-154`. minSdk 24 is not an obstacle; Navigation supports API 14+.

**Reasons to defer:**

- ~35k LOC touched — the largest single chunk of the whole programme, on the fleet that is actually shipping (v2.2.26) with live vendor integrations.
- MF919's exclusive screens have **no Pro counterpart to copy from**: BNPL (6 screens), Oxpay (2), MDB/denomination vending, SR800 gating. Net-new conversions with no reference implementation.
- Directly worsens R4 — a branch that large will rot at the current commit rate.
- The only merge-side benefit is collapsing R3, which costs one enum plus two small translators anyway.

Converting is a *structural* change only — the attend layout XML is reusable verbatim inside a Fragment, so constraint 5 is unaffected either way. Run it as a follow-on (C1) once `:core` is stable.

---

## 8. Enhancements to fold into the migration

The merge touches the build, the package layout and the shared core anyway. These are the improvements whose cost is near-zero *because* of that, plus the ones the merge makes possible for the first time.

### Tier A — land inside the merge; marginal cost close to zero

| | enhancement |
|---|---|
| **A1** | **Promote `LogRedact` to `:core`.** Both fleets already redact card data at the known ISO sites, but OLD does it with a private `maskTrack2()`/`maskPanLike()` helper **copy-pasted into 5 files** (`iso/bsn/IsoStepsNew.kt`, `iso/bsn_cardzone/IsoStepsNew.kt`, `iso/gobiz/IsoStepsNew.kt`, `iso/paydee/IsoStepsNew.kt`, `iso/IsoActivity.kt`), all delegating to `Utils.hideCardDetails`. PRO centralises it in `LogRedact.kt` (101 lines, 37 call sites) **and adds `registerCardData()`/`scrubPans()`** — a catch-all scrubber for PANs printed anywhere, not just at known sites. Its own comment records that the site-by-site approach "was measured to miss 10 raw PANs". Sharing it deletes 5 duplicates and gives MF919 a backstop it lacks. Logs are uploaded to TMS from both apps, so this is compliance-relevant. |
| **A2** | ~~**One source of truth for integration mode.**~~ **DONE 2026-09-04** (`83a3d5a6`, `1ec39731`). Both surfaces now defer to `helpers.IntegrationMode` in `:core`, unit-tested. **Decided rule:** the flag is read **by value** — only boolean `true` or the string `"true"` (case-insensitive, trimmed) selects old integration. `"1"`, `"yes"`, `"on"`, an explicit `false` and a bare present-but-empty value do **not**. This reverses HTTPServer's long-standing `has()` check, so a caller explicitly sending `"IsOldIntegration": false` is now honoured as new integration. One check covers both shapes: Gson renders a JSON boolean as `"true"`/`"false"` via `asString`, and intent extras are `HashMap<String,String>`. **The old-format amount fallback is kept** — a caller on old integration need not send the flag at all (MF919 has no such field, so its vendors never do), and without it `"10.00"` would be read as 10 cents. Detected per surface: a quoted `TransactionAmount` over HTTP, a non-digits-only amount over intents. |
| **A3** | **Vendor response-contract parity.** Restore `OriTransactionRRN` / `OriTransactionApprovalCode` to PRO's card result; settle `SHC007` on one meaning. |
| **A4** | **Dependency hygiene.** Adopt `libs.versions.toml` across all three modules; drop the unused Compose stack; declare `viewpager2` explicitly; delete the two orphan jars and the two stale commented-out YSDK jars. |
| **A5** | **Delete dead code while moving it.** The `E99`/`"TODO"` branch; the unreachable retry cache; ~185 commented-out lines in `activity_unattend.xml`. |
| **A6** | **Fix what breaks the merge anyway.** `Helpers/` vs `helpers/` case mismatch; `WebSocketClient.kt`'s directory/package mismatch; the stale `androidTest` package assertion. |

### Tier B — the merge makes these practical for the first time

| | enhancement |
|---|---|
| **B1** | **Unit tests on `:core`.** There are currently **zero real tests in either repo** — one stale instrumented example in PRO, and OLD's test dependencies are all commented out with no `test`/`androidTest` source set at all. `:core` is pure logic with no UI, so it is the first place tests are cheap. Highest-value targets are what has already caused production bugs: amount conversion (RM to cents at the `TransactionParser`/`SaleUseCase` boundary), `TransactionType` routing per mode, ISO field build/parse, TMS model de/serialisation, and the `DbHandler.selectListData` TypeToken trap. |
| **B2** | **CI.** Neither repo has any CI config. One pipeline — build `:core`, run its tests, assemble both APKs — is the main structural payoff of the monorepo, and it is what stops the duplicate-commit pattern. |
| **B3** | ~~**Release signing — confirm intent.**~~ **RESOLVED 2026-09-04: by design, not a gap.** Both projects sign all three build types, including `release`, with the debug keystore. This is deliberate and load-bearing: distribution is via TMS rather than the Play Store; a **manufacturer signing layer** is applied before upload to TMS, and that is the signature the fleet trusts; and because Android only permits an in-place update from an APK signed with the same key, moving `release` onto a separate CI keystore **would break app updates on every deployed terminal**. Recorded in `docs/building.md` and in a comment on the release block of both `build.gradle.kts` files so it is not "fixed" later. No action. |
| **B4** | **Variant filter.** 2 apps x 10 client flavors x 3 build types. Filter to what is actually shipped to keep sync and CI times sane. |

### Tier C — deliberate follow-on projects, after `:core` is stable

- **C1** — MF919 Activity to Fragment conversion (section 7).
- **C2** — Migration consolidation: squash OLD's 28 migrations into a baseline schema.
- **C3** — Real HTTP routing. `serve()` **never reads `session.uri`** in either project — any path and any method hits the same handler. Add versioned routes while keeping the legacy catch-all for existing vendors.
- **C4** — Feature reconciliation from R6, decided per feature rather than in bulk.

> **Explicitly out of scope during the merge:** attend layout changes (constraint 5), any vendor-visible contract change beyond A3, `TransactionType` renumbering, and DB schema changes beyond what a move requires.

---

## 9. Performance and threading — in the migration, or after?

**Verdict: split it along the module boundary.** The shared core's threading is decided *inside* the merge because reconciling those files forces the decision; the whole-app performance audit runs *after*, against a baseline captured *before*.

Measured posture:

| | OLD | PRO |
|---|---|---|
| raw `Thread(` | **148** | 36 |
| `runOnUiThread` | **152** | 31 |
| `Handler(` | 143 | 99 |
| `Thread.sleep` / `DelayMili` | **79** | 37 |
| `ServiceHolder` mutable `var`s | 48 (2 `@Volatile`) | 40 (6 `@Volatile`) |
| `Atomic*` | 23 | 33 |
| `Mutex` / `withLock` | **0** | **0** |
| `GlobalScope` | 0 | 0 |

PRO is roughly 4x leaner on raw threads and blocking sleeps for a comparable app; most of OLD's excess sits in the Activity layer.

### Decide inside the merge — unavoidable

- **`ServiceHolder` (Phase 2).** A global mutable singleton with ~48 `var`s, almost no `@Volatile`, no locking anywhere, written from the nanohttpd worker thread, the ISO thread, the schedulers and the UI thread. Two divergent copies cannot be merged without stating its concurrency contract.
- **`HTTPServer` (Phase 3).** OLD busy-waits `while (responseMsg == null && ...) { Utils.DelayMili(100) }` for up to 300 s on the nanohttpd worker thread; PRO uses `CompletableFuture.get(timeout)` plus an in-flight claim with takeover. PRO's model is strictly better and adopting it is forced anyway.
- **`AppServices` lifecycle and scheduler threading** (Phase 2–3).
- **Log I/O.** `AsyncLogWriter` is already async in both — share one copy.
- **DB access model.** WAL was enabled and then reverted in OLD (`Migrate isoDB to dbHandler and sqllite to wal`, then `Remove wal db`). Settle it once in `:core`, with the reason recorded.

### Defer to a separate audit afterwards

- OLD's 148 raw threads / 152 `runOnUiThread` / 79 blocking sleeps are concentrated in the per-app Activity layer the merge never touches — and a large share disappears if C1 proceeds.
- UI jank (the `keypad ui lag` and `Fix Keypad debounce crash issue` commits), cold-start time, memory, ANR profile.
- A whole-app StrictMode / systrace / thread-contention pass.

**Why defer rather than bundle:** the merge is already ~136k LOC of movement. A concurrency rewrite layered on top makes regressions unbisectable — a field incident could not be attributed to the move versus the threading change. On payment terminals that ambiguity is expensive.

### Do before Phase 0 — baseline capture (~1 day)

Measure on both apps and record the numbers: cold-start time, transaction round-trip (tap to result), HTTP response latency, log-write I/O throughput, and a StrictMode run snapshotting current violations. Without this there is no way to answer "it got slower after the merge" with evidence — nor to demonstrate that adopting PRO's `HTTPServer` model actually improved MF919.

---

## 10. Recommended phasing

| phase | scope | enhancements folded in |
|---|---|---|
| **pre-0** | Baseline capture on both apps (section 9). | — |
| **0** | New repo; both projects imported as subtrees preserving history; unified toolchain; two app modules building green against an empty `:core`. | A4, A6, B2, B3, B4 |
| **1** | Move `helpers` + `tms` + `enums` + jars into `:core`. Package-neutral, near-zero risk. | A1, A5, first B1 tests |
| **2** | `database`, `scheduler`, `data_enum`, `java/utils`, `java/device`. Reconcile `ServiceHolder` — 95 symbols common, 33 old-only, 13 pro-only, largely additive on each side. | B1 |
| **3** | `HTTPServer` behind the `supportsNewIntegration` flag and the `AppScreen` navigation seam; then the ISO stack. | A2, A3, B1 |
| **4+** | Follow-on projects, individually scoped. | C1–C4 |

**Exit criteria for every phase:** both APKs installable, and a real transaction driven on hardware — a card sale and a void per app, plus one vendor-intent round trip per app proving the `TransactionReceiver` contracts are untouched.

---

## 11. Open questions for the team

1. ~~**Release signing**~~ — **answered 2026-09-04.** Intentional. A manufacturer signing layer is applied before upload to TMS, distribution does not go via the Play Store, and switching `release` to a separate CI keystore would break in-place updates on deployed terminals. See B3 and `docs/building.md`.
2. ~~**Android 7 fleet**~~ — **answered 2026-09-04: yes, terminals running Android 7 are still in the field.** `:core` therefore stays at **minSdk 24** and this is a hard constraint, not a default to revisit. Any shared code must compile and run at API 24; `java.time` and other API 26+ APIs stay out of `:core` unless desugaring is introduced deliberately.
3. **Feature reconciliation (R6)** — **mostly answered 2026-09-04:**
   - **BNPL — stays MF919-only.** Its 3 tables, 3 repos and 6 screens remain per-app; this is part of why MF919 carries 21 database tables to Pro's 19.
   - **Oxpay — stays MF919-only.** Includes `AttendActivityOxpay` and the flavor bounce in `MainActivity`.
   - **MDB / vending — to be PORTED INTO Pro.** ~1217 LOC across `MdbController.kt` and `MorefunMdbTransport.kt`, plus 5 consumer sites (the denomination/vending screens and `DeviceHelper`). Pro already carries the event plumbing — `AppBus` defines `UiEvent.MdbStateChange` and `UiEvent.MdbVendingPrice` — but has no controller behind them. This is net-new feature work for Pro, not a merge step, and should be scoped separately from the `:core` phases.
   - **SR800 — STILL OPEN.** 14 files gate on `ServiceHolder.SR800_MODEL`, usually as `UNATTENDED_MODE && model == SR800`. Needs a decision.
4. ~~**`SHC007`**~~ — **answered 2026-09-04. MF919's wording wins, with the specific cause appended.**
   - `ResponseCode` stays `SHC007`.
   - `ResponseDescription` stays **`Terminal System Error`** — this is the existing MF919 wording (31 sites today vs Pro's 14), so vendors matching on the string are unaffected.
   - When the call arrives via **POS integration / App-to-App** and the product is unavailable, the cause is appended in parentheses: **`Terminal System Error (Product Is Not Configured)`**.
   - So Pro's standalone `Product Is Not Configured` becomes a qualifier rather than a replacement — backward compatible for anyone doing a prefix or equality match on the old text, while still surfacing why it failed. Implement as part of A3 in Phase 3.

---

## 12. Decision: repos and models stay per-app (2026-09-07)

`kotlin/database/repo` (19 MF919 / 16 Pro) and `kotlin/database/model` (19 / 17) **do not move
into `:core`**. Both apps share the storage engine -- `DbHandler` behind the `DbTable` / `DbSchema`
seam, already in `:core` -- but each keeps its own repositories and models, because the two
products are expected to diverge in data design.

### Consequence for the ISO stack

This settles section 3's "hard tier" question for ISO. The stack is 7 files, ~9.2k LOC each side,
four acquirer variants (bsn, bsn_cardzone, gobiz, paydee), and it imports back into its app:

| ISO imports | MF919 | Pro |
|---|---|---|
| `java.activity.*` (Utils, base classes) | 31 | 30 |
| `kotlin.database.repo.*` | 22 | 22 |
| `kotlin.database.model.*` | 7 | 7 |
| `kotlin.data_enum.variables.*` (TransData) | 5 | 5 |

With repos and models staying per-app, ISO **cannot move by relocation**. It can only be shared
behind a data seam, the way MDB was shared behind `MdbHost`. The surface is small enough for that
to be realistic -- about 15 distinct operations, against `MdbHost`'s 14:

- `IsoBatchInfoRepo`: `getBatchInfo`, `updateBatchInfo`, `allocateCounter`
- `SecureDataRepo`: `getDecryptedSingle`, `setSecureData`
- `SettlementSummaryRepo`: `updateData`, `getSelectiveData`, `getRecordValue`
- `ProductListRepo`: `getSingle`, `updateData`
- `ReceiptUploadRepo.updateData`, `PrintReceiptRepo.deleteVoidedInvoice`
- `ReversalBatchTableRepo`: `insertToDb`, `getBatchData`, `deleteSuccessReversalRecord`
- `IsoBatchLongInfoRepo.updateBatchLongInfo`

**But the two stacks do not agree on how counters are allocated**, and that has to be resolved
before any sharing -- see R8. Until it is, ISO stays per-app.

### R8 (new, high) - MF919 allocates transaction counters without a lock

Pro allocates STAN / invoice / batch counters through one synchronized read-modify-write:

```kotlin
fun allocateCounter(...): String = synchronized(counterLock) {
    val current = getBatchInfo(mContext, tag, subtag)?.let { Utils.atoi(it.value) } ?: 0
    var next = current + 1
    if (next > max) next = 1
    val formatted = String.format("%0${width}d", next)
    updateBatchInfo(mContext, formatted, tag, subtag)
    formatted
}
```

`allocateCounter` appears **26 times in Pro's ISO stack and 0 times in MF919's**. MF919 instead
does read-increment-write inline across its 36 `updateBatchInfo` call sites, with no lock. Section
9 already established that these tables are written from the nanohttpd worker thread, the ISO
thread, the schedulers and the UI thread, so two overlapping transactions can read the same
counter and both use it -- duplicate STAN or invoice numbers reaching the acquirer.

Pro also carries `CounterGuard` (`kotlin/helper_common/CounterGuard.kt`), which MF919 has no
equivalent of.

This is not a merge artefact; it is a live defect in the shipping MF919 fleet, and it is the
reason a shared ISO stack cannot simply adopt either side's behaviour. Fixing it means routing
MF919's 36 sites through an atomic allocator -- a change to the most money-critical path in the
app, and one that needs its own testing pass rather than riding along with a move.


## 13. ISO stack: the forming files measured and tiered (2026-09-07)

Section 3's hard tier names `IsoActivity`, and the measurement below confirms that placement. What
it never classified is the four **`IsoStepsNew`** files -- they appear in the audit only inside A1,
as the sites holding a copy-pasted `maskTrack2()`. This section tiers them, and the answer is the
opposite end of the scale from `IsoActivity`: measured with package names and line endings
normalised,

| unit | cross-app diff | of | share? |
|---|---|---|---|
| `CardDataTags.kt` | 8 | 68 | yes, trivially |
| `bsn/IsoStepsNew.kt` | 90 | 1460 | **yes** |
| `bsn_cardzone/IsoStepsNew.kt` | 106 | 1460 | **yes** |
| `gobiz/IsoStepsNew.kt` | 119 | 1463 | **yes** |
| `paydee/IsoStepsNew.kt` | 115 | 1448 | **yes** |
| `IsoHelperNew.kt` | 195 | 243 | no — 80% drifted, needs its own pass |
| `IsoActivity.kt` | 1103 | 3044 | later — this is the *sending* half |
| *(for contrast)* `HTTPServer.kt` | 2535 | ~2000 | no — more different than same |

So `IsoActivity` belongs exactly where section 3 put it, and the forming files do not belong
near it.

The four acquirer variants differ from **each other** by ~1,130-1,166 lines. Those are real
protocol differences and stay separate. But each differs from its Pro twin by only ~100 lines,
which decompose into exactly three functional items -- and Pro is ahead on all three:

1. `certLog { }` (lazy lambda, cert-only) vs an unconditional `helperLog.appendLine`.
2. `LogRedact.track2()` from `:core` vs a local `maskTrack2()` copy -- **audit item A1**.
3. `IsoBatchInfoRepo.allocateCounter` vs open-coded get/increment/put -- **R8**.

The rest is comments and imports.

### What this changes about the plan

**Forming is separable; sending is not, yet.** Splitting the stack along that line is the largest
single de-duplication available in the programme: roughly **5,800 lines per app**, at a fraction of
`HTTPServer`'s risk.

- **`IsoStepsNew` x4 + `CardDataTags` -> `:core`.** Prerequisite is **R8** plus A1, not a seam:
  once MF919 allocates counters atomically and uses the shared redactor, these files are shareable
  nearly verbatim.
- **`IsoActivity` stays per-app for now.** It is the sending/orchestration half and holds the 22
  repository calls, so sharing it needs the ~15-method data seam described in section 12 -- which
  the repos-stay-per-app decision makes mandatory rather than optional.
- **`IsoHelperNew` needs its own reconciliation** before it can go either way.

Ordering therefore: **R8 -> A1 in MF919 -> share the forming files -> seam + `IsoActivity`.**

## 14. Infrastructure audit (2026-09-07)

**Closed since the original audit:** CI exists (`.github/workflows/build.yml`: builds `:core`,
runs its unit tests, builds both apps, uploads APKs) -- B2. Version catalog adopted, 60 entries --
A4. Orphan jars gone; `core/libs` holds 4, all declared with `api(files(...))` -- A4. Compose BOM
removed -- A4. Signing resolved and documented -- B3.

**Still open:**

| # | finding | severity |
|---|---|---|
| I1 | **CI builds 1 of 11 client flavors** (`sharecomm` only). Nine others have their own `src/` source sets and none is ever built. **Severity corrected 2026-09-09 -- see below: they contain no code, so this is medium, not high.** | ~~high~~ medium |
| I2 | ~~**`special` flavor is declared in both apps with no `src/special/` directory.**~~ **Not a defect -- withdrawn 2026-09-09, see below.** | -- |
| I3 | **B4 variant filter not done** -- 10 flavors x 3 build types x 2 apps = **60 variants**. | medium |
| I4 | **No lint or static-analysis step** in CI. | medium |
| I5 | **JDK drift** -- CI pins 17, local builds run on Android Studio's bundled JDK 21, `compileOptions` targets Java 11. All three satisfy AGP 8.13, but the combination is untested. | low |

I1 is the highest-value fix: a flavor matrix, or at minimum one additional flavor.

**Deferred 2026-09-09.** The whole infrastructure group -- I1, I3, I4, I5 -- is pushed to a later
phase by decision, not dropped. (I2 is withdrawn; see section 22.) None of them blocks the merge
work, none affects what ships today, and the code-facing gap among them is I4 rather than I1 --
see section 22 for why I1's original "weakest point in the pipeline" rating was overstated. Revisit
as its own piece of work rather than interleaved with module moves.

### Local builds were reporting FAILED on success (fixed 2026-09-07)

Gradle's experimental HTML problems report writes to a single file in the **root** project's build
dir. Any process holding it open makes every build exit non-zero with
`AccessDeniedException: build/reports/problems/problems-report.html`, long after all real work has
succeeded -- and Android Studio's daemon holds it. So a CLI build run with the IDE open reported
`FAILED` on a green build, which on payment code is worse than no signal: it hides genuine
failures. Disabled via `org.gradle.problems.report=false` in `gradle.properties`, with the
reasoning recorded there.

CI was never affected -- it runs no IDE - so the pipeline has been honest throughout.

## 15. SHC007: recommendation (step 2 open; A3b fixed 2026-09-08)

`SHC007` carries four descriptions, and one of them wants the opposite client behaviour from the
others. The question a vendor actually needs answered is *"might the transaction have been
processed?"*, because that decides whether a retry is safe:

| code + description | processed? | correct client action |
|---|---|---|
| `SHC000` System Busy | **no** -- rejected outright | retry immediately, safe |
| `SHC007` Terminal System Error | probably not | escalate |
| `SHC007` Unexpected Error | probably not | escalate |
| `SHC007` **Terminal Response Timeout** *(Pro only)* | **possibly yes** | **query first, then retry** |

This also rules out the tempting shortcut of folding the timeout into `SHC000`: that would tell a
vendor a possibly-completed transaction is safe to retry blindly, which risks double charges. It is
worse than the present ambiguity.

### A3b -- MF919 took that shortcut (found 2026-09-08, **fixed same day**)

The two apps did not agree on what a 300 s timeout is. Both wait `300_000L`, then:

| app | site | timeout response (before) | after |
|---|---|---|---|
| Pro | `HTTPServer.timeoutJson()` | `SHC007` / `Terminal Response Timeout` | unchanged |
| MF919 | `HTTPServer.kt` bounded-wait fallback | **`SHC000` / `System Busy`** | `SHC007` / `Terminal Response Timeout` |

So the option ruled out above was what MF919 shipped, on the code whose documented meaning is
*rejected outright, safe to retry immediately*. An integrator following the contract would retry a
possibly-completed transaction. Two things made it less bad than it reads -- the terminal is
single-in-flight so a duplicate cannot run concurrently, and the operator sees the second payment
screen -- but neither is a control, and neither prevents a second charge.

**Fixed:** MF919's timeout fallback now emits `SHC007` / `Terminal Response Timeout`, byte-identical
to Pro. This is a vendor-visible change, but it is the safe direction: it moves a possibly-completed
transaction out of the "retry immediately" bucket. It does not resolve the SHC007 collision itself
-- callers must still branch on `ResponseDescription` -- that remains step 2.

Deliberately **not** changed: `busyJson()` and `defaultError(type = 0)`. Those are genuine
rejections where nothing ran, and `SHC000` is the correct answer. Only the bounded-wait fallback
moved, so "SHC000 near the 300 s mark" is no longer a case that can occur in MF919.

Two further facts that bear on any retry advice, both verified in Pro's `HTTPServer`:

- **The duplicate-request cache is disabled.** `RETRY_CACHE_WINDOW_MS = 0L`, so the replay branch in
  `submitRequest` is unreachable and an identical body after a timeout starts a **second
  transaction**. The comment there is explicit that enabling it is a vendor-visible behaviour change
  needing a decision, not a constant flip -- correctly parked, but it means there is no safety net.
- **Retrying while still in flight is safe.** An identical body arriving before the first request
  returns attaches to the pending response rather than starting a second transaction. That is the
  only safe retry window, and a timeout closes it.

Also surfaced, and both belong in the vendor guide: `SHC999` (Pro, unhandled exception in the void
flows -- its description is the raw `ex.message`, so it is unstable text on the wire and must never
be matched on) and `SHC006` (MF919 card-read timeout, written to the transaction's ISO-style
response-code field alongside values like `ZW`/`ZR`, **not** to `ResponseCode`). Neither is in
`EnumResponseCode` yet.

**Done, not deferred:** the alignment was the smaller change and strictly reduces the double-charge
risk, so it was applied rather than parked. Step 2 -- giving the timeout its own code -- is
unaffected and still needs integrator coordination; when it happens, both apps now move together
from one place instead of two.

**Recommended, in order:**

1. **Now -- documentation only, no code change.** State in the vendor integration guide (maintained
   outside this repo) that `SHC007` with description `Terminal Response Timeout` means *query before
   retry*, and that a timed-out request must be queried rather than retried blindly. The facts that
   guide needs are in A3b below. Zero risk, zero contract change.
   `enums.EnumResponseCode` already records the collision, and `EnumResponseCodeTest` asserts that
   it currently exists, so the issue fails loudly when someone resolves it rather than being
   quietly forgotten.
2. **At the next planned contract revision -- give the timeout its own code** (e.g. `SHC012`), with
   notice to integrators. This is the correct end state, but it is a vendor-visible addition and a
   strict code whitelist would reject an unknown value, so it needs coordination rather than a
   quiet edit.

Not recommended: a parallel `Retryable` field. It splits the contract across two places, existing
vendors ignore it, and option 2 would still be needed eventually.


## Appendix — how these numbers were produced

- Divergence counts: `norm.sh` in this folder. Re-run at any time; it writes `ident.txt`, `near.txt`, `oldonly.txt`, `proonly.txt` beside itself.
- All file paths, line numbers and version numbers were re-checked against the working trees on 2026-09-03.
- Verified directly: OLD has **0** occurrences of any new-integration token; both projects' `release` build type uses `signingConfigs.debug`; the 5 duplicate `maskTrack2`/`maskPanLike` copies in OLD; PRO manifest line 82 and OLD manifest line 215.
- Neither project's working tree was modified. Nothing was committed in either repo.

## 13b. ISO forming: converged to 0 diff (2026-09-08)

All six forming units are now **byte-identical between the two apps** (package line aside). Both
apps build; `:core` tests pass.

| unit | diff before | after |
|---|---|---|
| `CardDataTags` | 8 | **0** |
| `IsoHelperNew` | 217 | **0** |
| `bsn/IsoStepsNew` | 80 | **0** |
| `bsn_cardzone/IsoStepsNew` | 82 | **0** |
| `gobiz/IsoStepsNew` | 95 | **0** |
| `paydee/IsoStepsNew` | 91 | **0** |
| **total** | **573** | **0** |

**Rule applied: MF919 wins on everything wire-affecting.** MF919 had just completed a fresh acquirer
certification and Pro was never updated from it.

### Wire-affecting differences found and resolved

- **`IsoHelperNew` is a data table, not drifted code.** Structurally identical -- 6 enum entries,
  lookup by name, not position. The real difference was **12 values**: `BatchUpload` processing code
  `""` vs `"000000"` (5 acquirers), `SaleComp` POS condition `"0611"` vs `"0011"` (4 acquirers), plus
  `SaleCompCard` / `DebitAdjust` / `CreditAdjust` and the `reversalDes` field, which exist only in
  MF919. Pro took MF919's table wholesale.
- **DF03 guard** -- all four MF919 variants keep the original processing-code tag when
  `processCode` is empty; Pro overwrote it with an empty DE3. Ported to Pro (first DF03 site per
  file; the second site is unguarded in both, as in MF919).
- **Missing empty-secure-key guard** -- `gobiz` and `paydee` had a second key-load site where Pro
  did not check TMK/TAK, so it would build DE57 and compute a MAC from empty keys instead of
  declining `ZV`. Ported to Pro.
- **`9F6E` missing from Pro's tag lists** -- GOBIZ and PAYDEE, master and UPI. Changes which EMV
  tags are collected. Added; deliberately **not** added to BSN, where MF919 does not have it either.

### Logging policy: both apps on `certLog` + `LogRedact`

Settled after trying the other direction first. Both apps now:

- gate certification diagnostics (DE57/DE59 cleartext, MACs, DUKPT hex) behind `certLog { }`, which
  is `inline` + lambda so a release build compiles it away entirely and a debug build gets logcat
  only -- never `TerminaLog.txt`, which `uploadAllTerminalLog` ships to TMS;
- mask track2 through `:core`'s `LogRedact.track2`, replacing MF919's local `maskTrack2` copy.
  **This closes audit item A1**;
- print **no** key material. MF919 had four live leaks -- KSN, the derived DUKPT data key and the
  TLE header KCV in `bsn`, the FTK in `bsn_cardzone` -- all via `println`, which has no
  `BuildConfig.DEBUG` gate and therefore reached logcat in release builds. All four are gone.

Where the two apps gated differently, the safer gate won: Pro's wider `certLog` coverage over
MF919's, and MF919's `certLog` on `Dukpt Hex` over Pro's `appendLine`.

### Also fixed

8 mangled edits in Pro where a comment had landed between `val` and the identifier; 14 redundant
`DT3` comments removed (`allocateCounter` documents itself); one `gobiz` log line dumping the wrong
buffer (`TransData.transactionDb` instead of `ptrValue`); 5 dead imports; import order aligned.

### Dependencies moved to `:core` (2026-09-08)

New package **`crypto`** in `:core`: `Dukpt` (891), `DukptVariant` (101), `Encryption` (93) and
`BitSet` (55) -- **1,140 lines that existed twice now exist once.** All four were byte-identical
across the apps; `terminalLib.jar`, Dukpt's only external dependency, was already in `core/libs`.

Two things the drift measurement had not shown:

- **`BitSet` had to come too.** `Dukpt` and `DukptVariant` refer to it with no import, so it reads as
  `java.util.BitSet` until you notice the 55-line same-package wrapper of that name -- it exists
  because `java.util.BitSet` has no "constructed length" accessor.
- **`Encryption` was not self-contained.** One call, `Utils.paddingWith`, a pure 13-line static.
  It now lives in `:core` as `utils.StringUtils.paddingWith`, and **both apps' `Utils.paddingWith`
  delegates to it**, so the logic exists once rather than being copied a third time.

`TleGobiz` used `Encryption` as a same-package class and gained an explicit import.

### `IsoUtil` is not a free move

The section-13 table called `IsoUtil` free on the strength of a 0-line cross-app diff. That measures
drift, not dependency closure -- the same error section 13 made about the forming files. Its 420
lines reference:

| needs | count |
|---|---|
| `Global.iso.err.*` (`fileOutOfRange`, `invalidInputLen`) | 9 |
| `Global.iso.tag.*` (`MTI_RESP`, `BMP_RESP`, `TPDU_RESP`) | 9 |
| `Utils.*` (`memcpy`, `bcd`, `debugLogPrint`, `byteArrayToHexString`, `Byte`, `write`) | 14 |
| `EmvTag` | 1 |

So `IsoUtil` moves only after `Global` and a `Utils` subset do. `Global` is 2 diff lines and mostly
constants, which makes it the obvious next step.

### What still blocks the forming files

| dependency | lines | cross-app diff | |
|---|---|---|---|
| ~~`Dukpt`, `DukptVariant`, `Encryption`, `BitSet`~~ | ~~1,140~~ | 0 | **moved to `:core/crypto`** |
| `Global` | 450 | 2 | next -- mostly constants |
| `EmvTag` | 307 | 5 | after `Global` |
| `IsoUtil` | 420 | 0 | after `Global` + a `Utils` subset |
| `Utils` | 1,951 | 291 | drifted |
| `TransData` | 545 | 208 | drifted, mutable global state |

Seam surface if they do not move: 15 `Utils` members (pure statics), 6 repo methods,
`BuildConfig.DEBUG`, and **25 `TransData` members** -- the last is the real design problem.

### Drift check (added 2026-09-08)

`scripts/check-iso-forming-drift.py` compares the six pairs with `mf919pro` -> `mf919` and trailing
whitespace normalised, and fails on any other difference. It runs as the **first** CI step -- it
needs no JDK or Gradle, so a divergence fails in seconds rather than after a 20-minute build. Also
runnable locally: `python scripts/check-iso-forming-drift.py`.

Verified both ways: passes on the converged tree, and fails with the offending lines when a one-word
change is injected into one copy.

Delete the check when these files move to `:core` -- it exists only because they cannot yet.
*(Superseded: the files moved on 2026-09-08 and the script and its CI step were removed. See the end
of this section. The path above no longer exists -- it is kept for the record of why it existed.)*

### On-device validation (2026-09-08)

Both builds, attend mode, real contactless card, live host (`devterminal.share-commerce.com`).

| | MF919 on SR800 (.151) | Pro on MF919 Pro (.162) |
|---|---|---|
| result | **approved**, resp `00` | **approved**, resp `00` |
| RRN / approval | 625113001828 / 661278 | 625113001830 / 835036 |
| amount | RM1.00 | RM1.00 |
| receipt upload | `RESP_CODE=0000` | `RESP_CODE=0000` |

The moved crypto is exercised end to end: `derivedFutureKey` advanced the DUKPT KSN
(`...821000001` -> `...821000002`) through `crypto.Dukpt` / `crypto.DukptVariant`, and DE57 plus the
MAC went through `crypto.Encryption` -- all from `:core`, with no linkage error on either device.

The logging policy was verified on the terminals themselves. In each app's `TerminaLog.txt` -- the
file uploaded to TMS -- today's transaction produced **zero** occurrences of `Current KSN`,
`Current Dukpt`, `TLE Header KCV`, `FTK`, `Dukpt Hex`, or `CLEAR before/after padding`. MF919 still
holds 20 `Mac Iso` lines, all dated **2026-09-07**, from the build before the conversion; none from
today. `certLog` is doing what it claims.

### `Global` -> `constants.TerminalConstants` (2026-09-08)

Moved and renamed. Option 2 was chosen deliberately: two classes named `Global` in different
packages would have been a trap for whoever reads this next, so `:core` gets a distinct name and the
call sites were rewritten -- 460 in MF919, 320 in Pro, across 52 files.

**Three of the six members were dead.** `paymentInterfaceConfig`, `paymentInfo` and `appConfig`
(lines 215-406, 192 lines) are referenced **nowhere** outside `Global.java` itself -- and they were
the only reason the class looked unmovable, because they alone pulled in `R.string` and
`R.drawable`. Deleting them left a class with no app dependency at all. The earlier judgement that
`Global` "needs splitting" was wrong: it needed **deleting**, then moving whole.

Two references remained in `iso`, both trivial:

- `Utils.debugLogPrint("TAG:isoInfo", scheme)` -- that method is a one-line wrapper over
  `Timber.tag(..).d(..)`, and `:core` already uses Timber, so it calls Timber directly.
- `AcquirerLogoDataEnum.BSN.name()` -- evaluates to the literal `"BSN"`.

**449 lines x 2 apps became 254 lines in `:core`.** `cube`, `tlv`, `iso` (with `err` and `tag`),
`settings`, `isoInfo`, `filesInfo`, `paymentMethod` and `envSettings` all moved; nothing stayed
behind, so the app `Global.java` was deleted from both.

`IsoUtil` and `EmvTag` are now unblocked on the `Global` side; `IsoUtil` still needs a six-method
`Utils` subset (`memcpy`, `bcd`, `debugLogPrint`, `byteArrayToHexString`, `Byte`, `write`).

### On-device validation after the rename

Re-ran the same attend-mode card sale on both terminals with the rebuilt APKs. `Global.iso` feeds
ISO field construction -- 79 references in the forming files alone -- so this needed real hardware,
not a green build.

| | MF919 on SR800 (.151) | Pro on MF919 Pro (.162) |
|---|---|---|
| result | **approved** | **approved** |
| approval code | 669788 | 165533 |
| AID | A0000000031010 (VISA) | A0000000041010 (Mastercard) |
| KSN advance | ...822000001 -> ...822000002 | -> ...823000002 |

MF919's VISA batch totals moved 100/1 -> 200/2 across the two runs, so the settlement counters
tracked correctly through both moves.

### `EmvTag` + `Tlv` -> `:core/emv`, and the first `Utils` slice (2026-09-08)

`EmvTag` could not go alone. It holds a `Tlv` field and calls into it, so `Tlv` (798 lines, **0**
cross-app diff) moved with it -- both now in package **`emv`**. Between them they needed 9 `Utils`
methods; `Tlv` needed 7 more.

**`utils.ByteOps`** in `:core` is the first slice of `Utils`: `memcpy` (x2), `memset` (x2),
`strlen`, `byteArrayToHexString` (x3), `hexStringToByteArray`, `get_ushort`, `set_ushort` (x2),
`set_short`, plus the three private helpers they call (`arrayCopy`, `arrayFill`, `toByte`). Every
one was byte-identical across the apps. **Both apps' `Utils` now delegates these 13 methods to
`ByteOps`**, so the logic exists once rather than three times -- the rest of `Utils` stays per-app
and still drifted.

Logging was handled the same way as `TerminalConstants`: `Utils.debugLogPrint(tag, msg)` and
`Utils.printLog(msg)` are one-line Timber wrappers, so the moved files call Timber directly.
`printLog` logged under the tag `"Utils"` and that tag is preserved verbatim -- the MDB test harness
greps for it. `Utils.zeroPadding` became `AmountFormat.zeroPadding`, which was already in `:core`.

One trap worth recording: `BaseActivity.kt` resolved `Tlv` through a wildcard
`import com.sc.mf919.java.activity.*`, so deleting the class produced an unresolved reference with
no import line to update. Any future move out of that package needs a wildcard sweep, not just an
import rewrite.

### On-device validation

| | MF919 on SR800 (.151) | Pro on MF919 Pro (.162) |
|---|---|---|
| result | **approved** (`Response Code ::3030`) | **approved** |
| approval code | 000285 | 174043 |
| AID | A0000000031010 | A0000000031010 |
| KSN advance | -> ...824000002 | -> ...825000002 |

`EmvTag` and `Tlv` build every ISO field on every transaction, so a green build was not enough here.

### `IsoUtil` -> `:core/iso` (2026-09-08)

Moved. `write2File` turned out to be a **false dependency** -- the only call site is
`//Utils.write2File();`, commented out, so the app file-path problem it looked like never existed.
The dead comment was removed rather than carried across, since it named a class `IsoUtil` no longer
depends on.

Two primitives were added to `utils.ByteOps` (`bcd2bin`, `Byte2Int`, both byte-identical across the
apps, both now delegated from each app's `Utils`); `debugLogPrint` went to Timber as before.
Everything else `IsoUtil` needed -- `TerminalConstants`, `EmvTag`, `HexUtil`, the `ByteOps`
primitives -- was already in `:core`. 420 lines x 2 apps became 419 in `:core`.

### On-device validation

| | MF919 on SR800 (.151) | Pro on MF919 Pro (.162) |
|---|---|---|
| result | **approved** (`Response Code ::3030`) | **approved** |
| approval code | 966246 | 131494 |
| AID | A0000000041010 | A0000000031010 |
| KSN advance | -> ...827000002 | -> ...828000004 |

MF919 also completed a host **sign-on** (`signOnResult :: true`, `stringRespCode :: 3030`) -- a full
ISO 0800 built end to end through the moved `IsoUtil` / `Tlv` / `EmvTag`, which the sale path alone
does not cover.

One first attempt on MF919 declined with `Transaction Result :: -1` after `Search Card End`. That
was a card-search timeout with the card sitting on the other terminal's reader, not a code failure;
the re-run approved.

### `TransData`: seamed, not moved (2026-09-08)

`TransData` **stays per app**, by decision. It is a model, and the two copies differ for real
reasons rather than by drift:

| | MF919 only | Pro only |
|---|---|---|
| properties | `denominationProduct`, `denominationType`, `reqBatchNo`, `reqCardPan`, `reqInvoiceNo` | `additionalInfo`, `amountString`, `correlationRef`, `reqAuthId` |
| functions | -- | `ifCurrentSession` |

112 of ~117 properties and 11 of 12 functions are shared, but MF919's extras are vending/denomination
and Pro's are the newer HTTP contract fields. Moving it whole would have meant dragging
`IsoInfoModel` and `DbModelDenominationList` into `:core` -- both models -- plus a `ServiceHolder`
seam, and giving each fleet the other's fields. That contradicts the models-stay-per-app decision in
section 12.

**Instead: `iso.TransactionData`**, an interface holding exactly the **25** members the ISO forming
code touches. All 25 already existed in both apps; 23 declarations were byte-identical and the other
two differed only in nullability (`addHexStrIntoTransDB` / `addHexStrWithPadIntoTransDB` took
`String?` in MF919, `String` in Pro). The seam takes MF919's nullable form, and Pro's
implementations gained the same null guard MF919 already had -- behaviour-preserving, since no
caller passes null, and it removes two lines of drift as a side effect.

`iso.CurrentTxn` is the process-wide holder, registered in each app's Application class next to
`TerminalInfo`, `DbSchema` and `MdbController` -- the same shape as [mdb.MdbHost]. It **throws** if
read before registration rather than returning a dummy: a dummy would let a transaction form an ISO
message against an empty buffer and put it on the wire, which is worse than failing at startup.

`CurrentTxnTest` (5 tests) exercises all 25 delegates -- distinct values per property so a delegate
wired to a neighbouring field fails, `assertSame` on the two buffers because the forming code mutates
them in place and a copy would silently discard every write, and argument-order checks on the five
functions. 63/63 core tests pass.

Cost: **0** changes to the 4,045 in-app `TransData.` call sites.

### On-device validation

| | MF919 on SR800 (.151) | Pro on MF919 Pro (.162) |
|---|---|---|
| result | **approved** (`Response Code ::3030`) | **approved** |
| approval code | 470501 | 974756 |
| KSN advance | -> ...831000002 | -> ...830000002 |

### The three repositories: seamed (2026-09-08)

Same treatment, same reason -- repos stay per app. **`iso.TransactionStore`** covers the persisted
side of forming: counters, the acquirer product row, injected keys. Six methods across
`IsoBatchInfoRepo`, `ProductListRepo` and `SecureDataRepo`, whose signatures were already identical
in both apps.

The seam is expressed in primitives, which is what makes it work without moving any model:

- `getBatchInfo(...)` and `getDecryptedSingle(...)` return per-app row models, but the forming code
  only ever reads `.value` off them -- so the seam returns `String?`.
- `getSingle(...)` returns `DbModelProductListGet`, of which exactly six fields are read
  (`AcqCode`, `Product`, `AcqMid`, `AcqTid`, `Ksn`, `PinKsn`). The seam has its own
  `AcquirerProduct` value type carrying those six and nothing else.
- `updateData(...)` keeps map-shaped arguments. A typed API here would invent structure rather than
  capture it: the forming code already passes column names as string literals.

`Context` stays in the signatures because that is how the forming code passes it today; the
alternative -- each app sourcing it from its own `ServiceHolder` -- would hide the dependency for
no gain.

`iso.CurrentStore` is the holder, registered alongside `CurrentTxn`. Per-app adapters are
`Mf919TransactionStore` and `ProTransactionStore`, ~55 lines each, pure forwarding.

`CurrentStoreTest` (6 tests) covers all six delegates with distinct arguments, so a delegate that
swaps `tag`/`subtag` or drops the criteria map fails there rather than writing the wrong database
row mid-transaction. `ksn`/`pinKsn` are asserted separately -- the likeliest pair to be crossed.
This needed `unitTests.isReturnDefaultValues = true` on `:core`: the seam takes a `Context` it only
passes along, and a JVM test cannot otherwise construct one. **69/69 core tests pass.**

### On-device validation

| | MF919 on SR800 (.151) | Pro on MF919 Pro (.162) |
|---|---|---|
| result | **approved** (`Response Code ::3030`) | **approved** |
| approval code | 479011 | 305253 |
| KSN advance | -> ...835000002 | -> ...834000002 |

No `CurrentTxn used before register` or `CurrentStore used before register` on either device.

### The forming files moved (2026-09-08)

Done. `IsoStepsNew` x4, `CardDataTags` and `IsoHelperNew` now live in `:core` under `iso/`, and the
app copies are gone. **Every ISO message on both fleets is now built by one body of code.**

`IsoHelperNew` moved with them, `IsoInfoModel` included. It is a model by name, but its substance is
the ISO message template table -- MTI, processing code, POS condition, field lists -- it was already
at 0 cross-app diff, and it was one of the six units the drift check guarded. The models ruling in
section 12 is about the per-app database layer, and that is untouched.

What the rewrite came down to:

| was | now |
|---|---|
| `TransData.x` | `CurrentTxn.x` |
| `IsoBatchInfoRepo.allocateCounter` | `CurrentStore.allocateCounter` |
| `IsoBatchInfoRepo.getBatchInfo(...)?.let { it.value }` | `CurrentStore.batchInfoValue(...)?.let { it }` |
| `ProductListRepo.getSingle(...)` + column-named fields | `CurrentStore.acquirerProduct(...)` + `AcquirerProduct` |
| `ProductListRepo.updateData(hashMapOf, hashMapOf)` | `CurrentStore.updateProduct(mapOf, mapOf)` |
| `SecureDataRepo.getDecryptedSingle(...)?.value ?: run { "" }` | `CurrentStore.secureValue(...) ?: ""` |
| `SecureDataRepo.setSecureData` | `CurrentStore.storeSecureValue` |
| 15 `Utils` members | `ByteOps`, `StringUtils`, `AmountFormat`, `crypto.DataHash`, Timber |
| `EmvUtil.getCurrentTime` | `HelperDate.getDateString` |

Two things that needed a decision rather than a rewrite:

- **`HelperDate` pins `Locale.ENGLISH`; `EmvUtil.getCurrentTime` used the device locale.** For the
  DE12 timestamp that is a fix, not a risk -- a wire field must not follow the device locale -- and
  it is numerically identical under any Latin-digit locale. Called out because it touches a field on
  the wire.
- **`hashDataWithClearText` went to its own file, `crypto.DataHash`**, rather than into `ByteOps`: a
  SHA-1 digest is not a byte primitive. It was moved verbatim, including its
  `System.out.println` of the digest -- a hash rather than a key, but still a card identifier on
  logcat, and worth revisiting.

`:core` gained ThreeTenABP as a dependency: the void timestamp uses `org.threeten.bp` because
`java.time` needs API 26 and `:core` is minSdk 24.

**The same-package trap bit again, in both apps.** `IsoActivity` lived in the package the forming
files were leaving, so it referenced `IsoInfoModel`, `IsoHelperNew` and the three step objects with
no import at all. Deleting the files produced unresolved references with nothing to rewrite. Worth
remembering for `IsoActivity` itself, which is the next candidate.

**`scripts/check-iso-forming-drift.py` is retired**, along with its CI step. It did its last job
correctly on the way out -- exit 2 with `MISSING: ...` rather than silently passing once the files
were gone. With one copy there is nothing left to drift.

### On-device validation

| | MF919 on SR800 (.151) | Pro on MF919 Pro (.162) |
|---|---|---|
| result | **approved** (`Response Code ::3030`) | **approved** |
| approval code | 148514 | 652769 |
| AID | A0000000041010 | A0000000031010 |
| KSN advance | -> ...839000002 | -> ...838000002 |

Pro's log shows `IsoStepsBsnCardZone: Forming Iso Message` from the `:core` copy. No
`NoClassDefFoundError`, no `used before register` on either device.

## Running total in `:core`

| package | lines |
|---|---|
| `iso/` | 6,772 |
| `crypto/` | 1,172 |
| `emv/` | 1,107 |
| `constants/` | 254 |
| `utils/ByteOps` | 192 |
| **total** | **9,497** |

### `IsoActivity`: measured, and it should NOT be converged (2026-09-08)

Attempted, then stopped on the evidence. MF919 2,977 lines / Pro 2,782, **1,325 lines apart** after
the safe wins. Unlike the forming files that gap is not noise:

| bucket | lines |
|---|---|
| real code differences | 687 |
| comments | 246 |
| blank / brace | 201 |
| logging | 183 |
| imports | 8 |

39 of MF919's 45 functions and Pro's 41 are shared. The rest are **features, not drift**:

| MF919 only | lines | |
|---|---|---|
| `processSaleCompCardPresented` | 145 | matches MF919's `SaleCompCard` template |
| `processAdjustment` | 94 | driven by `AdjustmentActivity`; **Pro ships no adjustment UI** |
| `readTag` / `readOrZero` / `persistenceScope` | 31 | local helpers; Pro has equivalents under other names |

| Pro only | lines |
|---|---|
| `runLastReversalIo` | 78 |
| `constructSettlementValueString` | 6 |

Converging to 0 would mean pushing ~240 lines of transaction types into Pro that it has no screens
for, and MF919 taking Pro's reversal path on top of its own. **IsoActivity is the orchestration
layer, and that is where the two fleets legitimately differ** -- the same conclusion `TransData`
reached, for the same reason. The protocol half that *should* be shared already is: it moved with
the forming files.

Recommendation: leave `IsoActivity` per app. If any of it is worth sharing later, extract specific
shared functions rather than the file.

### Applied while measuring

- **A1 is closed.** MF919's local `maskPanLike` is gone; its 3 call sites now use
  `helpers.LogRedact.track2`, which handles the same separators and keeps BIN+last4 instead of
  delegating to `hideCardDetails`.
- **`Utils.ASCIItoHexString("ZS")` -> `StorageGuard.RESP_CODE_HEX`** at 5 sites in MF919, matching
  what Pro already did.

### D8 -- Pro's host-in-flight guard is a boolean where it must be a counter

The clearest defect the comparison turned up, and it is in Pro.

`TransData` is a process-wide singleton, and `sendToHost()` blocks for up to 60s with the response
parsed straight into it. Anything that re-owns `TransData` in that window corrupts a live
authorization, so callers gate on an in-flight flag first.

| | where | how |
|---|---|---|
| MF919 | `IsoActivity` | `AtomicInteger`, `isHostRequestInFlight = count > 0` |
| Pro | **`EmvFragment`** | **`var isHostRequestInFlight = false`** |

MF919's own comment states the reason it is a counter: *"settlement/batch-upload flows issue several
sendToHost() calls and a bool would be cleared by the first one to finish."* Pro ships the boolean,
so during settlement the guard drops while later host calls are still running, and
`CardPaymentFragment` reads it as clear. It is also scoped to a Fragment rather than the object that
owns the host calls.

**Fixed 2026-09-08, and it was worse than first described in both directions.**

Pro's flag was not merely a bool where a counter was needed -- it was raised *only* around the two
EMV sale paths in `EmvFragment`, so **settlement and batch upload ran with the guard down the whole
time**, never having raised it at all.

And MF919 had the mirror gap. Its counter is raised inside `sendToHost`, which covers the 0200 and
the response parse but **not** the tail of `processOnlineSale` where the approval is persisted -- so
a back press after the approval arrived, before the row was written, was ungated. MF919's three
`CardPaymentActivity` gates were reading a guard that had already dropped.

Each app had the half the other was missing. The fix gives both the same two-level arrangement,
which is strictly better than either shipped:

| | raises the counter |
|---|---|
| `IsoActivity.sendToHost` | every host call -- sale, settlement, batch upload, reversal |
| `IsoActivity.withHostRequest { }` | the whole caller flow, build through persist |

`withHostRequest` is in both apps' `IsoActivity`, wrapping the sale dispatch: 2 sites in Pro's
`EmvFragment`, 3 in MF919's `EmvActivity`. **Nesting is the whole reason this is a counter** -- the
wrapped block calls `sendToHost` itself, sometimes several times, and each call increments and
decrements again inside. A boolean would be cleared by the first inner call to finish and leave the
outer window unguarded, which is exactly the failure MF919's original comment described.

Pro's `EmvFragment.isHostRequestInFlight` is now a read-only delegate to `IsoActivity`, so its
existing reader in `CardPaymentFragment` is untouched. No writes to the old flag remain.

Validated on both terminals: MF919 approved `APPR_CODE 172664` (`Response Code ::3030`), Pro
approved `APPR_CODE 983265`.

### Settlement + back-press interrupt (tested 2026-09-08)

Real settlement on both terminals, `KEYCODE_BACK` sent 2 s in, mid host call.

| | MF919 on SR800 | Pro on MF919 Pro |
|---|---|---|
| settlement | **completed** -- `Settled OK :: ResponseCode :: [00]`, stan 001852, rrn 625117001852 | **completed** -- `Process Settlement [END]`, `signOnResult :: true` |
| totals | saleCount=5, saleTotal=5.00, batch closed | 13 records uploaded, batch closed |
| screen after BACK | stayed on `SettlementActivity` | stayed on the batch-upload dialog |
| `customOnBackPress` reached | **no** | **no** |

**No defect -- but not for the reason the fix suggests.** The press never reaches the Activity or
Fragment: `ProgressDialogFragment` sets `isCancelable = false` ("disables BACK button + outside
touch"), so the modal dialog swallows it and the back handler is unreachable for the whole host
window.

Pro's run is the clearest evidence for why the guard is a counter: the dialog read
`Uploading...(12/13)`, i.e. **13 sequential `sendToHost` calls in one flow**. A boolean would have
read clear from the moment the first upload returned, with twelve still to go.

### The settlement back gate (added 2026-09-08)

`SettlementActivity.customOnBackPress` (MF919) and `SettlementFragment.customOnBackPress` (Pro) did
**not** consult `isHostRequestInFlight`: MF919's logged "User Cancel", built a home-screen Intent
and navigated away; Pro's called `popBackStack()`. Both now refuse while the host owns the
transaction, mirroring the three gates already in `CardPaymentActivity`:

```kotlin
if (IsoActivity.isHostRequestInFlight) {
    helperLog.appendLine(helperLogClassName, "IGNORE OnBack Press :: host request in flight, cannot leave settlement")
    helperLog.logToFile(EnumLogFileName.TerminaLogException)
    Toast.makeText(mContext, "Processing, please wait", Toast.LENGTH_SHORT).show()
    return
}
```

**Pro's gate goes ahead of its `appIntent = false` / `appHTTP = false` writes.** Those ran before
the navigation decision, so even a press that was ultimately going to be swallowed had already wiped
the transport flags and stranded whoever asked for the settlement.

**Pro needed a second gate, and the test is what found it.** The first pass gated
`SettlementFragment`, but a settlement actually runs from **`SettleOptionFragment`** -- the log said
`SettleOptionFragment: OnBack Press Detected`, from a handler that had none. That one matters more:
its `appHTTP` branch calls `HTTPServer.setResponseMessage()`, so a mid-settlement exit would hand
the POS caller a partial settlement as if it were final, and clear `appIntent`/`appHTTP` on the way
out. All three of Pro's now gated: `CardPaymentFragment`, `SettlementFragment`, `SettleOptionFragment`.

### Re-tested with real settlements (2026-09-08)

Fresh transactions first -- both terminals share MID 011110000000014 / TID 40000014, so an SR800 sale
gives Pro's settlement host records to upload and forces the batch-upload phase.

| | MF919 on SR800 | Pro on MF919 Pro |
|---|---|---|
| batch | 1 sale, RM1.00 | 3 records |
| phase reached | `Batch Upload | Settlement...` | `Batch Upload | Uploading...(2/3)` + trailer |
| BACK presses during | 10 | 10 |
| gate hits | **0** | **0** |
| settlement | **completed** -- `Settled OK :: [00]`, stan 001862, rrn 625117001862, saleCount=1 saleTotal=1.00 | **completed** -- trailer + `Process Settlement [END]`, `DONE CREDIT / DEBIT CARD!` |
| first press delivered | after completion -- `User Cancel :: back pressed` | 6 s after completion -- `OnBack Press Detected` |

**Conclusion: the gate's in-flight branch is unreachable, and that is the correct outcome.** The
modal `ProgressDialogFragment` absorbs every press for the whole host window; presses are *deferred*
rather than dropped, and the first one is delivered once the dialog dismisses -- by which time the
guard is legitimately down, so it is allowed through. Twenty presses across two real settlements,
including a 3-record batch upload and a settlement trailer, produced zero gate hits and zero
interrupted settlements.

So the dialog is the working protection and the gate is dormant defence, verified not to over-block:
the normal path still leaves the screen on both apps. It earns its place by making the requirement
the settlement screen's own rather than a side effect of a shared dialog that any future
`isCancelable` change would silently remove.

**One caveat worth keeping:** the counter only rises when a host call starts, so the moment between
tapping SETTLE and the first `sendToHost` is not covered by the guard -- only by the dialog. Wrapping
each app's settlement invocation in `IsoActivity.withHostRequest { }`, the way the sale paths now
are, would close that too.

## D9 -- Pro crashes if the settlement screen detaches (found 2026-09-08, NOT fixed)

The back-press testing killed Pro once, at 17:42:26. Real crash, `reason=2 (SIGNALED) status=9`:

```
FATAL EXCEPTION: Thread-34
java.lang.IllegalStateException: Fragment SettleOptionFragment{31ef1ac} not attached to an activity.
  at androidx.fragment.app.Fragment.requireActivity(Fragment.java:1000)
  at BaseFragment.getTransDataViewModel(BaseFragment.kt:101)
  at BaseFragment.getTransData(BaseFragment.kt:104)
  at SettleOptionFragment$Trans$doInBackground$6.run(SettleOptionFragment.kt:656)
```

`SettleOptionFragment` runs a raw `Thread` for the whole settlement, polling the progress text once a
second:

```kotlin
loadingLoop = true
object : Thread() {
    override fun run() {
        while (loadingLoop) {
            if (transData.loadingTitle.isNotEmpty()) { ... }   // transData -> requireActivity()
            sleep(1000)
        }
    }
}.start()
IsoActivity.processSettlement(...)   // blocks here; loadingLoop = false only after it returns
```

`transData` resolves through `BaseFragment.transDataViewModel`, which is
`ViewModelProvider(requireActivity())`. So the loop calls `requireActivity()` **every second, from a
background thread with no exception handler, for the entire settlement**. Detach the fragment at any
point in that window and the next tick takes the process down.

**MF919 is immune, and the reason matters:** its `SettlementActivity` has the identical loop, but
reads `TransData.loadingTitle` -- the global singleton -- so there is no `requireActivity()` in it.
Pro's ViewModel migration is what put a lifecycle-bound call inside a background poll.

Two related weaknesses in the same block: `loadingLoop = false` runs only after
`processSettlement` returns, so a throw there leaves the thread spinning; and nothing stops the
loop calling `updateProgress` on a detached fragment.

**Not the back gate's failure.** The press was correctly allowed through -- no host call was in
flight at that moment, the screen was loading settlement records. No gate on `customOnBackPress` can
prevent this, because the crash is in a background thread, not in the navigation path.

### Fixed 2026-09-09

Three changes in the one block, all in `SettleOptionFragment`:

```kotlin
loadingLoop = true
val progressTxn = transData          // resolved here, on the caller's thread, and closed over
object : Thread() {
    override fun run() {
        while (loadingLoop) {
            if (!isAdded) break      // detached: no dialog left to update
            if (progressTxn.loadingTitle.isNotEmpty()) { ... }
            sleep(1000)
        }
    }
}.start()
try {
    IsoActivity.processSettlement(...)
} finally {
    loadingLoop = false              // was a trailing assignment: a throw left the thread spinning
}
```

Capturing `transData` is the fix proper -- the loop no longer resolves a ViewModel, so
`requireActivity()` is gone from it entirely. `isAdded` stops it updating a dead dialog, and the
`finally` closes the spin-forever path.

**Verified on the terminal (Pro at 192.168.100.162), two rounds, 25 `KEYCODE_BACK` presses each from
the moment SETTLE was tapped.** Round 2 produced exactly the condition that killed it yesterday:

```
10:00:37.578 -(SettleOptionFragment) SettleOption OnDestroyView :: screen ended
10:00:40.417 -(IsoActivity) -----------------Process Settlement [END]-------------------->
```

The fragment was destroyed **2.8 s before the settlement finished** -- about three ticks of the
one-second loop running detached, which is what took the process down on 2026-09-08. Same pid
throughout (4697), settlement completed, `logcat -b crash` empty for the day.

Not changed: `requireContext()` on the `processSettlement` call itself is still lifecycle-bound. Its
window is a single call at the start rather than a poll for the whole settlement, so it is a much
smaller target -- but it is the same class of problem and worth a look if this screen is revisited.


## 16. The last identical files: only one was movable (2026-09-09)

A sweep for files byte-identical across the two apps found 24, ~1,165 lines. Reviewed one by one,
**three of the four largest are anchored to their app by something the text comparison cannot see**:

| file | lines | blocker |
|---|---|---|
| `CrashHandler.kt` | 409 | six app classes, including `HTTPServer` and `ServiceHolder` -- the two most drifted files in the repo. Needs a seam, not a move. |
| `CrashRecoveryActivity.kt` | 147 | `com.sc.mf919.R` and 7 resource refs. Movable only by putting an Activity, its layout and a manifest entry into `:core`. |
| `UpdateAppAlertDialog.kt` | 35 | an `Activity` that launches each app's own `MainActivity`, registered in each manifest. Not worth a seam for 35 lines. |
| `DatabaseOpenHelper.java` | 167 | **none** -- moved. |

That is the third time today identical text has hidden a dependency (`BitSet` under `Dukpt`,
`IsoUtil` under `Global`/`Utils`, and now these). Identical text says two copies have not drifted;
it says nothing about what they are attached to.

### `DatabaseOpenHelper` -> `:core/database`

Its only app references were `Utils.debugLogPrint` and `Utils.printErrorLog`, both Timber
one-liners, handled the way every other move handled them. `mContext.getAssets()` needs no change --
it reads through the Context the caller passes, so each app still supplies its own asset DB.

**Two methods had to be widened to `public`.** `createDataBase()` and `createDataBase(byte[])` were
package-private, which worked while `DataAdapter` sat in the same package. Nothing else about the
class changed, and `read()` was left package-private because nothing outside calls it. This is a
detail a text diff also cannot surface: the compiler found it, not the comparison.

Both apps build; both start clean on hardware (MF919 .165 and Pro .162), which exercises this class
directly -- it copies the SystemTrace DB from assets on every launch.

Remaining identical-but-unmoved: 23 files, 998 lines, most of them `DbModel*` classes that stay per
app under the section 12 decision.


## 17. `CrashHandler` seamed to `:core` (2026-09-09)

409 lines, 0 cross-app diff, and the file section 16 called "needs a seam, not a move". Seamed and
moved: `helpers.CrashHandler` now lives in `:core`, with `helpers.CrashHost` supplying the app side.

The import list overstated the coupling. Measured, the handler needs **seven** things from its app:

| seam method | replaces |
|---|---|
| `ipAddress()` | `Utils.getIPAddress()` / `Utils.UNKNOWN_IP` |
| `isWifi(context)` | `TmsHelper.checkIsConnectedWifi` |
| `buildLabel()` | `BuildConfig.FLAVOR` + `BUILD_TYPE` |
| `callerChannel()` | `ServiceHolder.appHTTP` / `appIntent` |
| `notifyCallerBusy(payload)` | the `appHTTP` gate + `HTTPServer.setResponseMessage` |
| `launchRecovery(...)` | building the `CrashRecoveryActivity` Intent |
| `requestReboot()` | `DeviceHelper.getDeviceService().setProperties` |

Two dependencies were **not** in the imports and only turned up by reading the body:
`CrashRecoveryActivity` (same package) and `AppServices` -- the latter a false alarm, it appears
only in a comment. That is the fourth time today an import scan understated coupling.

`HelperCommon.getSession()` was inlined rather than seamed. `HelperCommon` lives in each *app* under
a `helpers` package -- so it was never in `:core` despite the import reading like it -- and it is 145
lines apart between the apps. `getSession()` itself is three lines minting a random id, with no app
state, so `:core` now generates its own.

`TransactionData` gained `stan` and `invoiceNo`, which the handler records so a crash can be matched
to the transaction in flight. Both already existed identically in both apps; `CurrentTxnTest` now
asserts them, called out as an easy pair to cross.

### `CurrentCrashHost` does not throw when unregistered

Deliberately unlike `CurrentTxn`. It is read from inside a crash handler, and a handler that throws
on a missing registration turns a recoverable crash into a frozen terminal -- the exact failure mode
the class was written to prevent. Every accessor degrades to a harmless default instead, so a crash
*during startup*, before registration, is still written down and still restarts.

### Verified by forcing a real crash on both terminals

`adb shell am crash`, which goes through the normal uncaught-exception path.

| | MF919 (.165) | Pro (.162) |
|---|---|---|
| pid | 15280 -> 15430 | 5968 -> 6113 |
| crash id | d5251d82 | 922ecea1 |
| `buildLabel()` | `build=sharecomm/debug` | `build=sharecomm/debug` |
| `callerChannel()` | `ecr=none` (correct -- nothing waiting) | `ecr=none` |
| `ipAddress()` / `isWifi()` | `192.168.100.165` / `true` | recorded |
| `CurrentTxn` reads | `stan=000001 invoice=000001 batch=000001 tid=40000014` | same |
| `launchRecovery(...)` | `crashId=d5251d82 attempt=1 delayMs=2000 deadPid=15280` | `crashId=922ecea1 attempt=1 delayMs=2000 deadPid=5968` |
| recovery | `Relaunched MainActivity` | `CrashRecoveryActivity` displayed, app back |

Five of the seven seam methods are exercised end to end there. `notifyCallerBusy` correctly no-opped
(`ecr=none`) and `requestReboot` only fires after repeated crashes, so neither ran -- both are
covered by the fallbacks in `CurrentCrashHost` regardless.

Duplication remaining across the two apps: **22 files, 589 lines**, mostly `DbModel*` classes that
stay per app under the section 12 decision.


## 18. `:core` stays UI-free (decided 2026-09-09)

`CrashRecoveryActivity` was assessed for the same treatment as `CrashHandler` and **deliberately
left per app**. The decision generalises, so it is recorded as a rule rather than a one-off:

> **UI stays at the app level. `:core` has no resources and no UI toolkit dependency.**

The assessment that produced it:

- **It cannot be seamed the way `CrashHandler` was.** Of ~110 lines of body, only `killIfStillAlive`
  (9 lines) and the relaunch flags (~15) are UI-free. The rest is `setContentView`, three
  `findViewById`, a `getString` per countdown tick, a `setText` on relaunch. A `:core` controller
  with the Activity left behind would dedupe ~25 lines and split a coherent class to do it.
- **A wholesale move would cost `:core` its first UI dependency.** Both apps' themes are
  `Theme.AppCompat.*` parents, so the class needs `AppCompatActivity`; `androidx.activity` alone is
  not in the version catalog and would be off-pattern besides. `:core` today has no `res/` at all
  and no UI library -- gson, timber, threetenabp, kotlin-reflect, core-ktx, zxing, coroutines.
- **What is being given up:** 147 lines of Kotlin, a 71-line layout and 6 strings stay duplicated.
  All are currently 0-diff, and the 4 colours the layout uses are identical in both apps.

The same rule excludes `UpdateAppAlertDialog` (35 lines, also an Activity, also 0-diff), which
section 16 had already set aside for its `MainActivity` reference and manifest entry.

**Consequence to plan around:** the crash screen is now the one place where a change has to be made
twice. It has been stable and identical, so the exposure is small -- but if it starts changing, this
decision is worth revisiting rather than quietly editing both copies.


## 19. Response-code literals moved onto `EnumResponseCode` (2026-09-09)

**183 sites across 31 files** now read their code and description from the enum instead of holding
string literals. Verified string-for-string: the pairs emitted after the rewrite match the
pre-rewrite inventory exactly, count for count -- 40 `SHC005 User Cancel the Transaction`, 31
`SHC007 Terminal System Error`, and so on down to the singletons. **No wire string changed.**

### The enum was missing six strings it was already shipping

The sweep inventoried 43 distinct (code, description) pairs actually in the source against the
enum's 22 constants. Six were live on the wire and absent from the class that calls itself the
single source of truth:

`Invalid Payment Channel` / `Invalid Ref ID` / `Invalid PosReference No` / `Invalid Transaction ID` /
`Trade amount should be greater than 0` / `Trade amount too large` -- all `SHC001`.

Added verbatim and pinned by a test, for the same reason as the rest: a vendor may be matching on
the text.

### D10 -- `SHC010` MyDebit descriptions differ only in casing

Two spellings ship for the same condition:

| sites | description |
|---|---|
| 2 | `MyDebit Completion Void is **N**ot **S**upported` |
| 2 | `MyDebit Completion Void is **n**ot **s**upported` |
| 2 | `MyDebit Preauth Void is **N**ot **S**upported` |
| 2 | `MyDebit Preauth Void is **n**ot **s**upported` |

The enum holds the capitalised form. The 4 capitalised sites were rewritten in the main sweep; the
4 lowercase ones were held back, because picking a spelling changes a vendor-visible string and that
is a contract decision rather than a cleanup.

**Decided and applied 2026-09-09: the capitalised form wins.** All 8 now read from
`MYDEBIT_COMPLETION_VOID_UNSUPPORTED` / `MYDEBIT_PREAUTH_VOID_UNSUPPORTED`, across both sinks each
file uses (`txnMap` for the app-to-app caller, `jObject` for HTTP) and the operator toast as well --
the toast is the same sentence, so leaving it behind would just have re-opened the drift from the
other side. 20 enum references across the four files: `VoidOffSaleActivity`, `VoidPreauthActivity`
(MF919), `VoidPreAuthFragment`, `VoidSaleCompFragment` (Pro).

**Wire impact, stated plainly:** 4 sites that previously emitted `... is not supported` now emit
`... is Not Supported`. That is the point of the fix -- one spelling on the wire -- but it *is* a
change a vendor matching on description would see, unlike the rest of section 19.

Four diagnostic log lines still carry the lowercase prose and were left alone: they are not contract
text, two of them are prefixed `REJECT :: ` so they were never the same string anyway, and one says
`PreAuth` where its neighbour says `Preauth`. Not worth churning; noted so it is not mistaken for a
missed site.

### The parameterised `SHC001` family stays as literals (decided 2026-09-09)

87 emission sites use `Invalid Parameter - (X)` across 13 field names. **Assessed and deliberately
not converted.**

**The format has no drift.** All 157 occurrences across both apps use the byte-identical shape
`"Invalid Parameter - (Field)"` -- no spacing variants, no bracket variants. That is the opposite of
what the sweep found elsewhere: six descriptions missing from the enum, and two casings of the
MyDebit text live on the wire. Those were real defects that consolidation exposed. Here there is
nothing to expose; the conversion would only confirm what is already uniform.

**And the helper would not protect the part that can go wrong.** The proposed call site is
`EnumResponseCode.invalidParameterDescription("TransactionAmount")` -- the field name is still a
hand-typed string. The helper pins the format, which has never drifted, and leaves the 13 field
names exactly as exposed as they are today. Real protection would mean making the field names
constants as well, which is a second and larger change and wants evidence that a wrong field name
has ever shipped. None was found.

**What it would cost:** 87 sites of churn on vendor-visible code for no behaviour change, and the
format string moving out of the enum into a companion helper -- so `EnumResponseCode` would stop
being the single place a description is defined, which is the property that made the other 183
conversions worth doing.

`invalidParameter(field)` remains in the companion with **zero call sites**. It was added
speculatively when the enum was written and never adopted. Left in place as the documented shape if
this is ever revisited, but it is dead code today and should be read that way.

### State

Raw `"SHCxxx"` literals remaining: **54 in MF919, 64 in Pro** -- the 87 parameterised sites, the 4
lowercase MyDebit ones, and 19 code literals that had no description on an adjacent line (mostly
reads and comparisons rather than emissions).

Java call sites use `getCode()` / `getDescription()`: Kotlin's `val` in an enum constructor is a
private field with a getter, so `.code` compiles only from Kotlin. 42 sites in
`TransactionReceiver.java` were switched; the compiler caught it.


## 20. `HTTPServer`: measured, and the proposed split assessed (2026-09-09)

### Measurement

| | MF919 | Pro |
|---|---|---|
| lines | 1,853 | 2,267 |
| **cross-app diff** | **2,352** | |
| functions | 31 | 35 |
| shared names | 20 | 20 |
| app-only functions | 11 | 15 |

**Both apps support the same transports** -- HTTP/NanoHTTPD, WebSocket, Cable/RS232 -- so the
divergence is not feature scope. It is that **Pro has been rearchitected and MF919 has not**:

```
checkTransactionType     MF919 = 838 lines      Pro = 33 lines
```

Pro's reads the integration flag and delegates to `oldIntegrationType` / `newIntegrationType`.
MF919's is the original monolith: one `when (txnType)` covering Sale, Void, Settlement, PreAuth,
SaleComplete, MOTO, EPP and PosReference enquiry, 60-180 lines a branch. Around it Pro has grown a
dispatch layer MF919 has no equivalent of -- `submitRequest`, `dispatchTransportRequest`,
`deliverToTransport`, `finishInFlight`, `handleCancelWhileBusy`, `timeoutJson`, `errorJson`, CORS
and WebSocket-client handling.

The give-away is in the shared names: 20 functions share a name and almost no implementation.

| | MF919 | Pro |
|---|---|---|
| lines inside shared-name functions | 1,363 | 446 |
| lines in app-only functions | 283 | 1,626 |

### The proposed split

> Transport (HTTP, WebSocket, cable) moves to `:core`. The old-vs-new integration check is shared in
> `:core` too, and `oldIntegrationChecking` / `newIntegrationChecking` dispatch back to the app,
> which owns the handlers.

**The layering is right** -- it is the seam pattern already proven three times here (`MdbHost`,
`TransactionData`/`TransactionStore`, `CrashHost`): protocol and plumbing in `:core`, per-fleet
behaviour behind an interface. Three things have to be true first, and today two are not.

**1. The transport layer is not shareable yet -- 469 lines apart.**

| function | MF919 | Pro | diff |
|---|---|---|---|
| `startHttpServer` | 25 | 24 | **1** |
| `serve` | 32 | 69 | 83 |
| `cableConnectionReceiving` | 63 | 36 | 73 |
| `startServeCable` | 76 | 49 | 71 |
| `handleIncomingRequest` | 80 | 47 | 43 |
| `readRequestBody` | 22 | 26 | 40 |
| `setResponseMessage` | 29 | 28 | 35 |
| `resetCommunicationPort` | 32 | 21 | 33 |
| `startWebSocketServer` | 52 | 58 | 28 |
| `checkWebSocketIncoming` | 27 | 6 | 25 |
| `onBackToRS232` | 24 | 10 | 22 |
| `stopWebSocketServer` | 10 | 11 | 15 |
| **total** | **472** | **385** | **469** |

Nearly 100% divergence against its own size. `startHttpServer` is effectively identical; everything
else needs the convergence pass the forming files had (573 -> 0) before it can move.

**2. There are two different in-flight models, and they must be reconciled, not merged.**

| | mechanism |
|---|---|
| MF919 | `AtomicBoolean requestInFlight` + `tryClaim` / `releaseInFlight` + `inFlightTransport` |
| Pro | `InFlight` class with origin/body/claimedAt, `submitRequest` / `finishInFlight`, a 300 s timeout, stale-claim takeover, duplicate-body attach, cancel-while-busy |

Pro's is the more capable of the two and is where the `SHC007 Terminal Response Timeout` behaviour
lives -- see section 15 A3b, where the two were aligned at the surface but not structurally. MF919
adopting it is a real gain beyond de-duplication.

**3. What "old integration" actually means -- clarified 2026-09-09, and it is not what the names
suggest.**

> "Old integration" **is MF919's integration method.** Pro introduced a new one and supports **both**
> -- old, so callers already integrated against MF919 keep working, and new, for vendors integrating
> fresh. MF919 speaks only its own, which from Pro's side *is* the old one. A third terminal model is
> coming that will speak **only** the new method.

This explains the asymmetry the measurement found. `helpers.IntegrationMode` is already in `:core`
with 10 unit tests, but **only Pro uses it** -- both call sites are Pro's `HTTPServer` and
`TransactionParser`. **MF919 contains no `IsOldIntegration` reference at all**, and correctly so:
every request it receives is old-integration by definition, so there is nothing to branch on.

**The constraint this puts on the design.** Today MF919 treats every request as old. If `:core`
starts routing on `IntegrationMode`, a request carrying `IsOldIntegration: false` -- or a numeric
`TransactionAmount`, which the same rule reads as new -- would newly route to a "new" branch MF919
cannot serve. That is a wire-visible behaviour change on a vendor-facing entry point. **MF919's seam
implementation must therefore route both branches to its single handler**, preserving today's
behaviour exactly. Not a shortcut: it is the correct mapping, because for MF919 both branches *are*
the same protocol.

**And this is the argument for doing the split at all.** The third model is new-integration only.
With transport and routing in `:core` behind a handler seam, that model implements the new branch
and declines the old one -- it does not inherit an 838-line `when (txnType)` it will never use, and
it does not fork `HTTPServer` a third time. The seam stops being de-duplication and becomes the
extension point the next terminal needs.

**Naming caution.** "Old" and "new" describe when the protocols were introduced, not what they are.
A reader meeting `handleOld` in `:core` could reasonably think it means deprecated-within-MF919. If
the seam is written, the interface should say plainly in its KDoc that *old* = the MF919 protocol and
*new* = the protocol Pro introduced, or use names that carry it.

### Suggested order, if this is taken on

1. **Converge the transport layer** (469 lines) -- same job as the forming files, and the same
   verification bar: `HTTPServer` is the vendor-facing entry point, so every change is on the wire
   path and wants real transactions on both terminals.
2. **Pick one in-flight model.** Recommend Pro's; MF919 gains timeout, stale-claim takeover and
   cancel-while-busy handling it does not have.
3. **Define the seam.** The old-integration question is answered: MF919 is old-only, Pro is both,
   the coming model is new-only.
   ```kotlin
   interface IntegrationHandlers {
       /** The MF919 protocol. MF919's only path; Pro's compatibility path. */
       fun handleOld(request: JsonObject)
       /** The protocol Pro introduced. Pro and the next terminal model. */
       fun handleNew(request: JsonObject)
   }
   ```
   `:core` owns transport -> parse -> `IntegrationMode.isOld(...)` -> `host.handleOld/handleNew`.
   Pro routes to its existing two functions. **MF919 routes both to its single `when (txnType)`** --
   required, not a shortcut, or a request flagged new would hit a branch it cannot serve.
4. **Move transport + dispatch to `:core`**, leaving the handlers per app.

Steps 1 and 2 are the bulk of the work and carry the wire risk. Step 3 is small once they are done.
Step 4 becomes mechanical, as it did for the forming files.

**Not started.** This is analysis only; no code changed.


## 21. Verification pass (2026-09-09)

Re-checked the audit's claims against the working tree rather than trusting the narrative.

### Current state, measured

| | |
|---|---|
| `:core` | **18,869 lines**, 118 files |
| duplication across the two apps | **22 files, 589 lines** (86 files present in both, 64 drifted) |
| build | both apps assemble |
| tests | **70, 0 failures** |
| seams registered in both Application classes | `TerminalInfo`, `DbSchema`, `MdbController`, `CurrentTxn`, `CurrentStore`, `CurrentCrashHost` |

`:core` by package: `iso` 6,786 · `tms` 2,742 · `helpers` 2,179 · `utils` 1,748 · `mdb` 1,347 ·
`crypto` 1,172 · `emv` 1,107 · `database` 1,012 · `constants` 254 · `data_enum` 238 · `enums` 166 ·
`env` 121.

### Claims checked

- **Every file path the audit names still exists**, with one exception:
  `scripts/check-iso-forming-drift.py`, which sections 13b/16 describe and which was deleted when
  the forming files moved. The retirement *is* recorded later in the same section, so the document
  was chronological rather than wrong -- but a reader landing on the earlier paragraph would try to
  run a script that is gone. A forward pointer has been added there.
- **Response-code conversion holds:** 202 `EnumResponseCode` references in MF919, 178 in Pro.
- **D8, D9, D10 fixed** and verified on hardware; see their sections.

### Still open, confirmed by re-measurement

| item | evidence |
|---|---|
| ~~**I2**~~ | withdrawn -- `special` is correct as designed, see section 22 |
| **I1, I3, I4, I5** | **deferred to a later phase by decision (2026-09-09).** CI flavor coverage, variant filter, lint/static analysis, JDK drift. I4 is the pick of them; I1 was re-rated medium |
| **§15 step 2** -- SHC007 timeout its own code | needs integrator coordination |
| **§19** -- 87 parameterised `SHC001` sites | assessed and deliberately left as literals |
| **§20** -- `HTTPServer` | measured and analysed; not started |
| **D9 residual** | `IsoActivity.processSettlement(requireContext(), ...)` at `SettleOptionFragment:679` is still lifecycle-bound -- a single call at the start rather than a poll, so a much smaller target than the loop that was fixed |
| **G2** -- QR auto-void | still untested; needs QR provisioning |

### Not open, worth stating

`IsoActivity`, `TransData`, the repositories, the `DbModel*` classes and the two UI files
(`CrashRecoveryActivity`, `UpdateAppAlertDialog`) are **decided, not outstanding** -- sections 12,
16, 18 and the `IsoActivity` assessment. They account for most of the 589 duplicated lines and most
of the 64 drifted files. The merge is not "incomplete" by that amount; it is complete up to
deliberate boundaries.


## 22. I1 and I2 corrected: flavor source sets hold no code (2026-09-09)

Both findings rested on an assumption I never checked. Measured now:

**Every flavor source set in both apps contains only branding assets.** Across all 18 of them the
file inventory is **145 `.png`, 8 `.xml`, 8 `.bmp` -- and zero `.kt` or `.java`.** Launcher icons,
notification icons, footer logos, a splash background. Flavors otherwise differ only through Gradle
config:

```kotlin
create("sharecomm") {
    dimension = "client"
    resValue("string", "app_name_about", "Share Commerce")
    manifestPlaceholders["appName"] = "ShareCommerce"
}
```

### I2 is withdrawn -- `special` is correct as it stands

`special` is configured exactly like every other flavor but deliberately has **no source set**, so it
falls back to `main`'s default resources and builds a generic "Payment APP". **The absent
`src/special/` is the mechanism, not an oversight** -- it is how a default-resource build is
produced. The original finding ("either give it sources or delete the flavor") was a misreading of
an intentional design.

### I1 stands but was overstated

The original wording was *"flavor source sets are exactly where per-client bugs hide, so this is the
weakest point in the pipeline"*. That is wrong on the evidence: with no code in them, a per-client
**code** defect is not possible. What an unbuilt flavor can still hide is a **resource** problem -- a
missing icon density, a malformed PNG or XML, a `resValue`/placeholder typo -- which fails the build
or ships a broken icon, but cannot produce divergent behaviour at the till.

So the finding is real and worth fixing (a build matrix, or one extra flavor in CI), but it is
**medium, not high**, and it is not the weakest point in the pipeline. On this evidence **I4 -- no
lint or static analysis in CI at all -- is the more valuable gap**, because that one does bear on
code.

### The general lesson, again

This is the same failure mode as `BitSet` under `Dukpt`, `IsoUtil` under `Global`, and the four
"identical" files in section 16: **a structural observation was treated as evidence about content.**
"Nine flavors have source sets" was true; "so per-client bugs hide there" did not follow, and one
`find` would have shown it. Worth remembering when reading the rest of this document -- the
measurements are sound, the inferences drawn from them deserve the same scepticism.


## 23. Transport convergence attempted -- and it is not a standalone step (2026-09-09)

Started section 20's step 1. Got 11 lines, then stopped on structure. **469 -> 458.**

### What converged

- **MF919 took Pro's `stopWebSocketServer` fix.** MF919 guarded the teardown with
  `if (socketConnected)`, so a server whose bind was still in progress -- `socketConnected` not yet
  true -- was skipped, leaking the server and holding port 8080. Pro already stopped unconditionally
  and nulled the reference. A real MF919 defect, fixed.
- A dead `//startServeCable()` line removed. `startHttpServer` is now identical.

### Why the other 458 lines are blocked

The remaining divergence is not drift. It decomposes into exactly two things, and each is a decision:

**1. MF919 supports RS232 cable; Pro does not.** Measured:

| | `usbSerialPort` refs | `getUsbSerialPort` | `"RS232"` branches |
|---|---|---|---|
| MF919 | 9 | 2 | 4 |
| Pro | **0** | **0** | **0** |

MF919 branches on `CABLE_CONNECTION` for `USB` (via `serialPortDriver`) and `RS232` (via
`usbSerialPort`); Pro handles only `USB`. This runs through `startServeCable` (71),
`cableConnectionReceiving` (73), `resetCommunicationPort` (33) and `onBackToRS232` (22) --
**~199 of the 458 lines**. Converging cannot mean deleting it: that is shipped hardware support.

**2. The in-flight model is interleaved with the transport functions, not layered above them.**
`checkWebSocketIncoming` is the clearest case -- MF919's body *is* `isCancelRequest` /
`handleCancelRequest` / `tryClaim` / `busyJson` / `releaseInFlight`, while Pro's is a single
`dispatchTransportRequest(Origin.WEBSOCKET, ...)`. The same split runs through `serve` (83),
`handleIncomingRequest` (43) and `setResponseMessage` (35).

### Correction to the section 20 plan

Section 20 listed "converge the transport layer" and "pick one in-flight model" as steps 1 and 2.
**They are the same work.** Converging `serve`, `handleIncomingRequest`, `setResponseMessage` and
`checkWebSocketIncoming` *is* choosing an in-flight model, because that logic lives inside them.
There is no meaningful transport-only convergence to do first -- 11 lines was all of it.

### What the revised sequence looks like

1. **Decide RS232.** MF919 keeps it -- it is real hardware support. So either the converged cable
   layer carries both modes (Pro gains code it does not exercise), or the cable transport stays per
   app behind the seam while HTTP/WebSocket move. The second is likely better and worth costing.
2. **Adopt Pro's in-flight model in MF919** -- `submitRequest` / `finishInFlight` / timeout /
   stale-claim takeover / cancel-while-busy, replacing `tryClaim` / `releaseInFlight`. This is an
   MF919 refactor on the vendor-facing request path, not a merge, and it is where the real gain is.
3. Only then is there a transport layer worth moving.

Step 2 is the substantial piece and carries wire risk on both apps' request handling. It wants its
own session and real transactions on both terminals, exactly as the forming-file convergence did.

**Stopped here deliberately.** The 11 safe lines are applied and MF919 builds; the rest is not
convergence work, it is a refactor decision.


## 24. RS232 ported into Pro (2026-09-09)

Section 23 left the RS232 asymmetry as a decision: MF919 supported RS232 cable, Pro did not, and
converging could not mean deleting shipped hardware support. **Decided: port it into Pro.**

### What was ported

**`DeviceHelper`** gained a `SerialPort` import, a `usbSerialPort` static and `getUsbSerialPort(path)`
-- taken verbatim from MF919. Pro had none of it, so nothing in Pro could open a USB serial port at
all.

**`HTTPServer`** gained the fields (`usbSerialPort`, `usbPath`, `baudRate`/`dataBits`/`stopBits`/
`parity`) and the RS232 path in four places:

| function | change |
|---|---|
| `startServeCable` | `if (USB) / else` became `when` with USB / RS232 / else; opens via `openAndInit` |
| `cableConnectionReceiving` | now takes `connMethod` and reads via `serialPortDriver.recv` or `usbSerialPort.read` |
| `resetCommunicationPort` | RS232 branch resets and reopens the USB serial port |
| `onBackToRS232` | answers on whichever cable is open |

Parity confirmed: both apps now show 9 `usbSerialPort` references, 4 `"RS232"` branches, and
`getUsbSerialPort` in `DeviceHelper`.

### Two things deliberately not carried across

- **`rs232(msg)` was not ported.** MF919 defines it at line 1731 and **nothing calls it** -- dead
  code. Porting it would have doubled the dead code rather than the capability.
- **MF919's in-flight logic stayed behind.** Pro's `cableConnectionReceiving` keeps its own
  `dispatchTransportRequest`; only the *read* branch was added. What moved is the transport
  capability, not the architecture -- that decision is still open (section 23, step 2).

### One bug fixed on the way

Pro's `startServeCable` else-branch had `portOpen = false` and `cableRequest = false` **inside**
`serialPortDriver?.let { }`. With a null driver they never reset, so the receive loop kept spinning
on a closed port. MF919 has them outside the block; Pro now does too.

### Where the transport layer stands

**469 -> 458 -> 396.** `startServeCable` fell from 71 to 4 and `startHttpServer` is identical. The
remaining 396 is almost entirely the in-flight architecture -- `serve` (83),
`cableConnectionReceiving` (71), `handleIncomingRequest` (43), `readRequestBody` (40),
`setResponseMessage` (35), `checkWebSocketIncoming` (25) -- which is section 23 step 2 and a
refactor decision, not convergence.

### Verification limit -- read this before trusting the port

Both apps build and `:core`'s 70 tests pass, and MF919's own RS232 path is untouched. But **the new
Pro path has not been exercised**: it needs a terminal configured `CABLE_CONNECTION = RS232` with a
USB-serial cable attached, and today's bench has neither. The code is a faithful transcription of a
path that works in MF919, which is not the same as a tested path in Pro. **Treat Pro's RS232 as
unverified until someone runs a transaction over it.**


## 25. MF919 adopted Pro's in-flight model (2026-09-09)

Section 23 step 2, the piece that section flagged as "the substantial one... wants its own session and
real transactions". Done, and exercised on hardware.

### What replaced what

MF919's `tryClaim` / `releaseInFlight` / `busyJson` / `isCancelRequest` / `handleCancelRequest` /
`serveOne` are gone. In their place, taken from Pro:

| new | does |
|---|---|
| `submitRequest(origin, body)` | the one admission point for HTTP, cable and websocket |
| `InFlight` + `CompletableFuture` | the slot carries the answer, so a transport that cannot block gets a callback |
| `finishInFlight` | releases the slot and records the body for the retry cache |
| `dispatchTransportRequest` | cable / websocket entry, with its own timeout arm |
| `deliverToTransport` | routes the answer by origin |
| `handleCancelWhileBusy` | TransactionType 0 terminates a waiting session instead of being refused |
| `timeoutJson` / `errorJson` / `corsResponse` | shared response shapes |

`serve()` no longer polls `responseMsg` every 100 ms until a 300 s deadline; it blocks on
`future.get(RESPONSE_TIMEOUT_MS)`. `setResponseMessage` no longer switches on `inFlightTransport`;
it completes the future and lets the waiter deliver.

### Three behaviour changes, all wire-visible

1. **A duplicate request now attaches to the running transaction instead of being refused.** Two
   byte-identical bodies get one transaction and two identical answers. This is the point of the
   change: under `tryClaim` the retry got SHC000 and the POS had to try again, which is how a double
   charge happens.
2. **A cancel arriving while a transaction is committing is now refused, not obeyed.**
   `handleCancelWhileBusy` returns null when `appRunningProcess` is set, so the request falls through
   to SHC000. MF919's old `handleCancelRequest` tore the session down regardless of how far along it
   was. MF919 was already inconsistent about this -- `handleIncomingRequest`'s inner branch checked
   `appRunningProcess`, the serve() intercept did not. Now both respect it.
3. **A cancel arriving when nothing is running answers `00 / "No Session Running"`, not SHC009.**
   Old MF919 intercepted every cancel in `serve()` and always answered SHC009 + bounced to Attend.
   Now an idle cancel reaches `checkTransactionType`, which is Pro's contract. **Integrators who
   treat SHC009 as "cancel acknowledged" will see `00` instead when nothing was running.**

### A defect this change exposed, and fixed

MF919's `checkTransactionType` type-0 branch wrote `addProperty("ResponseCode", "No Session Running")`
-- `ResponseCode` twice, so the description overwrote the code and the caller got
`{"ResponseCode":"No Session Running"}` with no `ResponseDescription`. Pro has `ResponseDescription`.
The branch was unreachable before (serve() intercepted every cancel first), and change 3 above makes
it reachable, so it had to be fixed rather than shipped. Fixed to match Pro.

### Also converged on the way

`HelperCommon.oneLine` added to MF919 (delegating to `:core`'s `HelperText`, exactly as Pro does) and
`HTTPServer`'s private copy deleted. **`TmsHelper` still carries its own third copy** -- separate
cleanup, not chased here.

### Transport diff: 545 -> 246

Measured per function over the transport + in-flight set, whitespace- and blank-line-normalised
(`txdiff.py`). Not the same denominator as section 24's 469/458/396 series, which is why the starting
number differs; the before figure is this script run against the pre-change file.

**Zero diff now:** `dispatchTransportRequest`, `finishInFlight`, `timeoutJson`, `deliverToTransport`,
`errorJson`, `defaultError`, `corsResponse`, `startHttpServer`. `setResponseMessage` 1,
`checkWebSocketIncoming` 3, `submitRequest` 6 -- all comment-only except `submitRequest`'s startup
gate.

**What the 246 still is**, and none of it is the in-flight model:

| | lines | why |
|---|---|---|
| `cableConnectionReceiving` | 41 | MF919 `Dispatchers.Default` + `SystemClock.sleep` + string concat; Pro `bgScope` + `delay` + StringBuilder |
| `serve` / `readRequestBody` | 74 | MF919 keeps `BodyRead`, which distinguishes a short read (SHC001) from a missing content-length (SHC000); Pro returns null for both |
| `handleCancelWhileBusy` | 36 | MF919 tears down Activities, Pro emits `UiEvent.EndPaymentSession` to Fragments -- genuinely per app |
| `resetCommunicationPort`, `startWebSocketServer`, `onBackToRS232`, `wakeScreen`, others | 95 | predates this work, untouched |
| `submitRequest` | 6 | `ServiceHolder.appFreshLoad` vs Pro's `ecrStartupBlocking()` |

That last one is a real gap, not a style difference: Pro's guard has a 120 s age ceiling so a missed
clear cannot keep ECR closed for the life of the process; MF919's is a plain boolean. **Next
candidate**, and it is a `ServiceHolder` change, not an `HTTPServer` one.

### Verified on hardware -- MF919 .165, sharecomm debug, card parked on the reader

| # | test | result |
|---|---|---|
| T1 | cancel while idle | `00 / No Session Running`, with `ResponseDescription` present -- the typo fix |
| T2 | two sequential sales, RM1.00 then RM2.00 | both approved, **each answer to its own caller** (STAN 001904, 001905) |
| T3 | sale RM3.00, cancel 1 s later | `Different request while busy (HTTP), reject SHC000`; the sale then answered its own caller (STAN 001906) |
| T4 | **two byte-identical sale bodies, 0.4 s apart** | `Duplicate in-flight request (HTTP), attaching to pending response`; **one transaction, two identical answers** -- same STAN 001907, same RRN, same ARQC |
| T5 | garbage body | SHC001 Invalid Input |
| T6 | no content-length (chunked) | SHC000 -- MF919's `BodyRead` behaviour preserved |
| T7 | cancel while idle, after all the above | `00 / No Session Running` |

Five sale requests produced **four STANs**: the duplicate consumed none. No exception anywhere in
16,672 log lines. Both apps build; `:core` 70/70.

**Four real test transactions are sitting in batch 000676 on .165** (RM1 + RM2 + RM3 + RM4), unsettled.

### Not exercised

- **WebSocket.** `checkWebSocketIncoming` is now a single call into `dispatchTransportRequest`, which
  is byte-identical to Pro's shipping code -- but no WS frame was put through MF919. It needs the
  terminal switched to websocket mode.
- **Cable.** Same code path via `deliverToTransport -> onBackToRS232`; the bench still has no serial
  cable, as section 24 recorded.
- **Stale-claim takeover and the 300 s timeout.** Both need a transaction that hangs for five
  minutes; neither was provoked.


## 26. `appFreshLoad` given an age ceiling (2026-09-09)

Section 25's "next candidate", and the last 6 lines of `submitRequest` divergence. MF919's ECR
startup guard was a plain boolean: set true in `MainActivity.onCreate`, cleared on several paths.
`MainActivity`'s own comment said it -- *"appFreshLoad gates the ECR entry point and has no staleness
ceiling, so a flag left set here refuses every transaction for the life of the process"* -- and
compensated with belt-and-braces clears, including a `finally`. Discipline, not a guarantee.

### The shape chosen, and why it is not Pro's

Pro pairs two flags with an explicit `markStartupBegun()` next to each write. MF919 instead stamps
from the property setter:

```kotlin
@Volatile
var appFreshLoad = true
    set(value) {
        if (value) startupClaimedAt = SystemClock.elapsedRealtime()
        field = value
    }

fun ecrStartupBlocking(): Boolean {
    if (!appFreshLoad) return false
    return SystemClock.elapsedRealtime() - startupClaimedAt < STARTUP_GUARD_CEILING_MS
}
```

**Why the setter rather than Pro's mark function:** MF919 has six write sites across `MainActivity`
and a `true` default. A mark call next to each is something a future edit can forget; a setter
cannot be. `startupClaimedAt` is initialised at companion init so the `= true` default -- which
bypasses the setter, as Kotlin property initialisers do -- is stamped too.

**The two questions are now separate, deliberately.** Read `appFreshLoad` to ask *"is this a fresh
load"* (what drives the TMS download and sign-on block at `MainActivity:587`); call
`ecrStartupBlocking()` to ask *"must ECR refuse"*. Only the second gets the ceiling. Putting the
ceiling on the flag itself would have made a slow first-boot download silently skip its own
configuration step.

`STARTUP_GUARD_CEILING_MS = 120_000L`, same as Pro. **The trade:** a genuine startup that runs past
two minutes -- a slow first-boot TMS download -- will start admitting ECR requests while the app is
still initialising. That is the lesser harm; the alternative is a terminal that refuses every
transaction until someone reboots it.

`submitRequest` now differs from Pro by **two comment lines and no code**.

### Verified on .165

Force-stop, relaunch, then a `TransactionType 0` probe every 3 s (a cancel, not a sale -- same
SHC000-vs-not signal with nothing charged):

```
15:35:20.172  Request refused :: app startup still running (HTTP)
15:35:23.369  Request refused :: app startup still running (HTTP)
15:35:26.569  Request refused :: app startup still running (HTTP)
15:35:29.848  Set Response Msg :: {"ResponseCode":"00","ResponseDescription":"No Session Running"}
```

The guard arms, holds for the ~10 s startup, and opens. Transport diff 246 -> **244**.

**Not exercised: the expiry itself.** Nothing on the bench wedges the flag for two minutes, so the
`> ceiling` branch has never run. It is two lines and inspected, not tested -- if it is ever worth
proving, the way is a scratch build with the ceiling at 10 s and a deliberately thrown startup.

### Test transactions left on .165

Sections 25 and 26 together put **seven** real sales into batch 000676, unsettled: RM1, RM2, RM3, RM4
(section 25) and three at RM5 (an early startup-guard probe that used TransactionType 1 before the
probe was switched to the harmless TransactionType 0).


## 27. Decision: the retry cache stays disabled (2026-09-09)

`RETRY_CACHE_WINDOW_MS = 0L` in both apps. Raised while explaining section 25's T4 result, and
**ruled: it stays off.** The comment on the constant in both `HTTPServer`s now says so, since that is
where someone would be tempted to flip it.

**What stays off.** Section 25's duplicate handling only covers a retry that arrives *while the
original is still in flight*. Once the transaction completes and the slot is released, an identical
body runs as a fresh sale. The retry cache would extend the protection past completion by replaying
`lastCompletedResponse` for a matching body inside the window.

**Why off is the right default.** The cache cannot tell a lost-response retry from a genuine second
sale of the same amount to the same POS reference -- they are byte-identical requests. Enabling it
trades "a retry might double-charge" for "a real second sale might silently not happen and be
answered with the first one's approval code". The second failure is worse: the terminal reports an
approval for a transaction it never ran, and the merchant is short.

**What this means for integrators.** The in-flight window is the whole protection. A POS that loses
a response must **query, not retry** -- which is what SHC007 already tells it to do (see item 15).

The feature is finished code, not abandoned code: `lastCompletedBody` / `lastCompletedResponse` /
`lastCompletedAt` are still maintained by `finishInFlight`, so enabling it is a one-constant change
if the contract ever gains a request-level idempotency key. **That** is what would make it safe --
matching on an explicit key rather than on the body bytes.


## 28. Batch settlement on both terminals, and a divergence it exposed (2026-09-09)

### MF919 .165 -- settled

Section 25/26's seven test sales cleared over HTTP (`TransactionType 3`, old integration):

```
00 / Settled   count 7   amount RM25.00   sale 7 / void 0
batch 000676   STAN 001913   RRN 625215001913   MID 011110000000014 TID 40000014 (BSN_CARDZONE)
```

RM1+2+3+4 from the in-flight tests plus 3 x RM5 from the startup-guard probe. Clean log, no
exception. The settlement itself went through the refactored `serve()` -- claimed the in-flight slot,
answered its caller through the future, ~1 s round trip.

### Pro .162 -- nothing to settle

`SettlementDetail: []`, and that is correct: `files/lastsettlement.txt` shows Pro's last settlement
was **2026-09-08 17:44, batch 000672, 3 sales RM3.00** -- the previous session's three Pro
transactions. Nothing has run on Pro since; today's testing was all on .165.

### The divergence: what each app does with an EMPTY batch

Confirmed by settling .165 a second time, immediately after the first, with nothing left in the
batch:

| | empty-batch settlement |
|---|---|
| **MF919** | runs a **real settlement**: allocates STAN 001915 / RRN 625215001915, reports zero counts, closes batch 000677. `Unsettled products :: 1` -- the product is settleable even with no transactions |
| **Pro** | **no-op**: `Initiate Perform Settlement` -> `Into Settlement Action` -> straight out in 2 ms, no `SettleItem[...]` iteration, no host message, no batch roll |

Both answer `ResponseCode 00`, so **a POS cannot tell from the response whether a settlement actually
happened.** Neither app has a distinct code for it -- "No Transaction Activity" exists in MF919 only
as a printed receipt line, never on the wire.

**Why it matters for the merge:** these are two different contracts on a vendor-facing operation. An
acquirer that expects a daily settlement message even on a zero day gets one from MF919 and not from
Pro. Converging the settlement path means choosing one, and that is a question for the acquirer, not
a code decision. **Not changed.** Logged here as the open item.

### Side effect worth knowing

The comparison run consumed STAN 001915 and closed batch 000677 on .165. The terminal is now on a
fresh empty batch with two settlements recorded today (15:41:36 with 7 txns, 15:45:22 with 0).


## 29. Pro end-to-end: both integration modes, voids, settlement (2026-09-09)

Driven over HTTP against .162 with a Visa test card parked on the reader. **8 sales, 2 voids,
1 settlement, all approved.** Full logcat captured (199,098 lines) and the ISO traffic decoded.

### What ran

| | request | result |
|---|---|---|
| OLD 1-4 | `TransactionType 1`, `TransactionAmount` as **string** `"1.00"`..`"4.00"`, no flag | approved, invoices 001902-001905 |
| OLD void | `TransactionType 2`, `IsOldIntegration:"true"`, `TransactionInvoice:"001905"`, `ForceVoid:1` | approved, STAN 001906 |
| NEW 1-4 | `TransactionType 2`, `TransactionAmount` as **number** `100`..`400` cents, `PaymentChannel:"CARD"` | approved, invoices 001907-001910 |
| NEW void | `TransactionType 3`, `TransactionInvoice:"001910"`, `ForceVoid:1`, `PaymentChannel:"CARD"` | approved, STAN 001911 |
| settle | `TransactionType 4`, `PaymentChannel:"CARD"` | `00`, Visa **sale 6 / RM12.00, void 2 / RM8.00** |

Settlement arithmetic is exact: 8 sales = RM20, less the 2 voided RM4 sales = 6 sales / RM12, with
the voids reported separately. `MyDebit` shows in the scheme list in its **capitalised** form -- the
D10 fix (item 19) visible on a real settlement report.

### Integration routing proved by behaviour, not by a log line

Neither branch logs which one it took, but the responses settle it:

- **Old** accepted `TransactionType 1` as a sale with no `PaymentChannel`. In the new branch, 1 is
  *enquiry*, and a sale (2) without `PaymentChannel` is rejected -- which is exactly what the first
  new-integration attempt got back: `SHC001 Invalid Parameter - (PaymentChannel)`.
- **Old routed on the amount's JSON type alone** (string), with no `IsOldIntegration` flag -- the
  fallback signal that exists because MF919's vendors never send the flag. Confirmed working.
- The void and settlement carry no amount, so old-integration versions of those **must** send
  `IsOldIntegration:"true"` or they route new. Worth telling integrators.

### ISO layer, decoded from the wire

12 host round trips to `uat-pos.bsn.com.my:15023`, every one answered:

| MTI | proc code | count | what |
|---|---|---|---|
| `0200` -> `0210` | `000000` | 8 | sales, amounts 100/200/300/400 twice |
| `0200` -> `0210` | `020000` | 2 | voids, amount 400 both |
| `0500` -> `0510` | `920000` | 1 | settlement |
| `0800` -> `0810` | `920000` | 1 | network message after settlement |

**STAN sequence 001902 -> 001913 is unbroken** across sales, voids, settlement and the network
message: no gap, no reuse. Host round trip ~100-120 ms.

### In-flight model under a real workload

Zero anomalies across the whole run -- no duplicate, no busy rejection, no stale claim, no unrouted
response, no handler throw. Receipts uploaded to TMS (`RESP_CODE=0000`).

The one stack trace in the log is the deliberate `throw Exception()` that `newIntegrationType` uses
to bail out of a rejected request. Its catch is guarded with `if (responseMsg == null)`, so the
caller got exactly one response and the right one (`SHC001`, not the catch's `SHC001` fallback).
Noise, not a defect -- though every invalid-parameter request prints a stack trace to `System.err`.

### DEFECT: the void path writes the full PAN to the TMS-uploaded log

**`TerminaLog.txt` -- the file `TmsHelper.uploadAllTerminalLog` ships to the backend -- contains the
unmasked PAN 54 times.** Every occurrence is on a **void**. Four lines per void, reproduced on both
of today's voids and on three voids yesterday:

```
15:50:48.230 -(VoidSaleFragment)       Batch Table Record :: {"batchData":"...<PAN in the TLV>..."}
15:50:48.514 -(IsoStepsBsnCardZone)    DF02 :: 4365091500002381
15:50:48.518 -(EMVTAG)                 TLV: AddTag DF63 Data=4365091500002381
15:50:48.532 -(IsoStepsBsnCardZone)    DE2(9) :: 164365091500002381
```

**No sale leaked** -- the 8 sales produced no `DF02 ::` file line at all, and the masked form appears
710 times. So `LogRedact` works; it is the void path that escapes it.

**Not a stale build.** The installed APK is from 2026-09-09 10:28; `VoidSaleUseCase.kt`, which carries
the `LogRedact.registerCardData(cardPan, null)` call at line 73, was last edited 2026-09-08 16:39.
The running code has the registration.

**Leading explanation -- a fragment lifecycle race.** `TransactionResultFragment.onDestroyView()`
calls `LogRedact.clearCardData()`. In both voids the outgoing result screen tore down *inside* the
window between the void screen registering the PAN and its first PAN-bearing log line:

```
15:50:47.818  VoidSaleFragment onOK(arguments) :: 001905      <- lookupInvoice registers the PAN
15:50:48.075  TransactionResult OnDestroyView :: screen ended  <- clearCardData() wipes it
15:50:48.230  first unmasked line
```

Identical interleaving on the second void (59.520 / 59.778 / 59.911). Sales are immune because
`EmvUtil.readTrack2` registers at card tap, long after the previous screen is gone; a void registers
at screen entry, which is precisely when the previous screen is being destroyed.

**Not fully excluded:** that `registerCardData` never takes effect on this path at all. The two are
indistinguishable from this log -- separating them needs one instrumented run that logs `livePan`
after registration, or a void started from an idle screen with no result screen tearing down.

**Suggested fix, not applied:** make `clearCardData()` clear only what its own transaction
registered -- a generation token handed out by `registerCardData` and passed back to the clear -- so
an outgoing screen cannot wipe the incoming transaction's data. That is a `:core` `LogRedact` change
affecting both apps, so it wants a decision first.

### Terminal state

Batch 000676 settled and closed; .162 is on a fresh empty batch. Net RM12.00 of test money settled.


## 30. D11 -- the void PAN leak fixed, in both apps (2026-09-09)

Section 29's defect. The open question there -- lifecycle race, or `registerCardData` never
effective? -- is now **answered: it was the race.** The fix removed the leak on device.

### The mechanism, confirmed

`LogRedact.clearCardData()` took no argument, so *any* caller wiped *whoever's* card data was
registered. On Pro the screen that ends transaction N is torn down after transaction N+1 has already
registered:

```
47.818  VoidSaleFragment onOK :: 001905      <- the void registers its PAN
48.075  TransactionResult onDestroyView      <- clearCardData() wipes it
48.230  DF02, DF63, DE2, batchData           <- all unmasked, all uploaded to TMS
```

### The fix: registration generations

`:core` `LogRedact` now hands out a generation:

```kotlin
fun registerCardData(pan, track2): Long   // returns the generation it created
fun currentGeneration(): Long
fun clearCardData(generation: Long)       // no-op unless it still owns the data
```

The no-argument `clearCardData()` is **gone**, deliberately: it is the shape that caused the bug, and
with one caller in the tree there was no reason to keep a footgun available. Callers snapshot the
generation when their screen appears and hand it back on teardown -- taken at entry, not at exit,
because by exit the next transaction may already own the data.

### MF919 had the same leak, worse

Found while fixing Pro. MF919 **never called `registerCardData` at all** -- its only `LogRedact` use
is three explicit `track2()` calls in `IsoActivity` -- so `livePan` was permanently null and the
`AsyncLogWriter` scrub was inert for the whole app. Its `VoidSaleActivity` and `VoidOffSaleActivity`
also log `Batch Table Record` **before** extracting the PAN, so even a registration added in the
obvious place would have been too late.

Fixed: both activities now extract `cardPan`, register, and only then log the batch record;
`TransactionResultActivity` clears with the same generation gate. So MF919's void path is covered for
the first time, not merely un-raced.

### Verified on device -- Pro .162, fixed build

Sale RM7.00 (inv 001915), then a `ForceVoid` void of it. Counting only what the fixed build wrote:

| | before | after |
|---|---|---|
| unmasked PAN in `TerminaLog.txt` | 54 | **0** |
| `DF02 ::` | `4365091500002381` | `436509******2381` |
| `AddTag DF63` | `4365091500002381` | `436509******2381` |
| `DE2(9) ::` | `164365091500002381` | `16436509******2381` |
| `Batch Table Record` batchData TLV | PAN in the clear | masked |

Both apps build; `:core` **73/73** (three new tests). Pro settled afterwards -- batch 000677, void 1
/ RM7.00, sale 0 -- so the terminal is left clean.

### Tests added

`LogRedactTest` gains three, the first being the regression:

- a clear holding an old generation leaves the newer registration intact -- the exact 150 ms
  interleaving measured on Pro, as a unit test
- the owning generation still clears
- each registration gets its own generation

### Not done

**MF919's sale path still never registers.** This fix covers the void path, which is where the PAN
was measured reaching the file. MF919 sales rely on explicit `LogRedact.track2()` at three sites, the
same posture Pro's sale path has -- and Pro's 8 sales produced no `DF02 ::` file line at all, so
there is no measured leak there. Worth a deliberate pass rather than an assumption: if MF919 ever
logs a raw TLV on the sale path, nothing is armed to catch it.


## 31. MF919's sale path armed too -- and it was leaking full track 2 (2026-09-09)

Item 30 closed the void path and left MF919's sale path as an open item, on the reasoning that
Pro's sales produced no measured leak so MF919's probably did not either. **That reasoning was
wrong, and measuring it took two minutes.**

### What was actually in the file

MF919's `EmvUtil.readTrack2()` logged the **raw track 2**:

```
2026-09-09 15:34:29.984 -(com.sc.mf919.java.utils.EmvUtil) readTrack2: 5181231400000411D271020100000495
```

PAN, `D`, expiry `2710`, service code `201`, discretionary data -- the whole field, on **every card
read**, in `TerminaLog.txt`, which `TmsHelper.uploadAllTerminalLog` ships to the backend.
**120 occurrences** of the full PAN from a single day's testing.

This is worse than the void leak of item 29: that was four lines per void, this is every sale, and it
carries the expiry and service code as well as the PAN.

Pro was never exposed: its `readTrack2()` already logged `LogRedact.track2(track2)` and registered
the card data. **The divergence had been sitting in a nine-line function the whole time.**

### Fix

MF919's `readTrack2()` replaced with Pro's, verbatim -- the two are now byte-identical:

- strips the trailing `F` by reassignment rather than an early `return`, so the code below sees the
  cleaned value
- splits the PAN off at the separator and calls `LogRedact.registerCardData(pan, track2)`
- logs `LogRedact.track2(track2)` instead of the raw field

Registering here is what arms the `AsyncLogWriter` sink for MF919's **whole** sale path -- every
later line containing that PAN or track 2 is masked without anyone having to find and wrap it. Until
today MF919 never called `registerCardData` anywhere, so the sink scrub was inert app-wide and the
only protection was three explicit `track2()` calls in `IsoActivity`.

### Verified on device -- MF919 .165, fixed build

Sale RM8.00 (inv 001918), then a `ForceVoid` void of it. Counting only lines the fixed build wrote:

| | before | after |
|---|---|---|
| full PAN in `TerminaLog.txt` | 120 | **0** |
| full track 2 (PAN + expiry) | present | **0** |
| `readTrack2:` line | `5181231400000411D271020100000495` | `518123******0411[len=32]` |
| void `DF02` / `DF63` / `DE2(9)` | -- | `518123******0411` |

The masked form keeps `[len=32]`, so the log still shows whether the field was the length the parser
expected -- which is what those lines are for.

Settled afterwards: batch 000678, void 1 / RM8.00. Terminal left clean.

### Two PAN sources still unarmed -- in BOTH apps

Named here rather than guessed at, because that is the mistake item 30 made:

1. **`EmvUtil.readPan()`** reads tag `5A` directly and only falls through to `getPanFromTrack2()`
   (which registers) when `5A` is empty. A card presenting `5A` registers nothing *via this call*.
2. **The mag-stripe path** takes track 2 from `TransData.magTrack2`, never through `readTrack2()`.

Neither was observed leaking today: contactless EMV calls `readTrack2()` elsewhere in the same flow,
so registration happens anyway, and no mag-stripe transaction was run. Both apps share both gaps, so
closing them is a two-app change and wants its own pass -- with a mag-stripe swipe on the bench to
measure it, not another assumption.


## 32. The last two PAN sources armed, in both apps (2026-09-09)

Item 31 named two sources that never reached `LogRedact`. Both closed, identically in both apps.

### Gap 1 -- `EmvUtil.readPan()`, tag 5A

`readPan()` reads tag `5A` and only falls through to `getPanFromTrack2()` (which registers) when 5A
is **empty**. A card presenting 5A left the sink unarmed by this call. Now:

```java
if (pan.endsWith("F")) {
    pan = pan.substring(0, pan.length() - 1);   // was: return pan.substring(...)
}
LogRedact.registerCardData(pan, null);
return pan;
```

`null` for track 2 is deliberate -- `registerCardData` only overwrites a field it is given a usable
value for, so whatever `readTrack2()` registered survives. `readPan()` is now byte-identical across
the two apps.

### Gap 2 -- the mag-stripe swipe

A swipe never touches `readTrack2()`; it arrives as `MagCardInfoEntity` in the search-card callback.
Worse, the very next lines log the raw tracks:

```kotlin
builder.append("PAN:" + magCardInfoEntity.cardNo)
builder.append("TRACK1:${magCardInfoEntity.tk1}")   // track 1 carries the PAN too
builder.append("TRACK2:${magCardInfoEntity.tk2}")
```

Registration now goes in immediately above that builder, at **all three** callbacks --
`EmvActivity.onFindMagCard`, `EmvActivity.onSearchResult` (MF919 has two entry points) and
`EmvFragment.onFindMagCard` (Pro has one):

```kotlin
LogRedact.registerCardData(magCardInfoEntity.cardNo, magCardInfoEntity.tk2)
```

Track 1 and track 3 are covered without naming them: the sink replaces the PAN substring wherever it
appears, which is the whole reason registration beats wrapping call sites.

### Registration sites, both apps, after this pass

| source | MF919 | Pro |
|---|---|---|
| chip/contactless track 2 (`readTrack2`) | yes | yes |
| chip tag 5A (`readPan`) | **new** | **new** |
| mag-stripe swipe | **new** (x2 callbacks) | **new** |
| void, from the batch table | yes (item 30) | yes |

### Verified -- and one part that cannot be

Sale on each terminal, counting only lines the new builds wrote:

| | MF919 .165 | Pro .162 |
|---|---|---|
| full PAN in `TerminaLog.txt` | **0** | **0** |
| full track 2 | **0** | **0** |
| `readTrack2:` line | `518123******0411[len=32]` | `436509******2381[len=32]` |

**The mag-stripe path is not verified.** It needs a magstripe card physically swiped, and the bench
has contactless cards parked on the readers. The code is a three-line registration in a callback
that already had the entity in hand, but it has not been executed. **Treat mag-stripe redaction as
unverified until someone swipes a card and greps the log.**

### A note on the generation counter

`registerCardData` bumps the generation on **every** call, including a repeat read of the same card.
That is deliberate: bumping only on change would re-open item 30's race whenever two consecutive
transactions use the same card, which is the normal case on a test bench. The cost is that a
registration occurring after a result screen has snapshotted its generation makes that screen's clear
a no-op. **That direction fails safe** -- card data lingers in memory until the next registration
overwrites it, which over-masks rather than under-masks. The dangerous direction, clearing someone
else's data, is what the gate prevents.

### Found while verifying: MF919 commits a fragment off the main thread (NOT fixed)

The first MF919 sale of this pass returned `SHC000 Terminal Error - restarting` and the app went to
`CrashRecoveryActivity`:

```
Signature : ConcurrentModificationException@TransactionResultActivity.processApprovedTransaction:139
Thread    : DefaultDispatcher-worker-3 (main=false)
Transaction : type=Sale stan=001923 invoice=001923 batch=000679 ecr=http
  at androidx.activity.OnBackPressedDispatcher.updateEnabledCallbacks
  at androidx.fragment.app.BackStackRecord.commit
  at TransactionResultActivity.processApprovedTransaction(TransactionResultActivity.kt:139)
```

`TransactionResultActivity.onCreate` launches `CoroutineScope(Dispatchers.Default).launch { ... }`
(line 122) and that coroutine calls `processApprovedTransaction()`, which does
`supportFragmentManager.beginTransaction()...commit()` -- **a fragment commit on a background
thread.** It usually works and races into a `ConcurrentModificationException` when the
`OnBackPressedDispatcher` callback list is walked concurrently.

**Pre-existing, not from this pass.** Lines 122-144 are untouched by any change in this document; the
only edits to this file were item 30's generation snapshot and clear, which touch neither fragments
nor threading. The earlier crash in today's log (10:29) is a `shell-induced crash` from `am crash`,
a different signature.

**Why it matters:** the sale was authorised -- STAN 001923 went into batch 000679 and settled with
the retry for RM18.00 total -- while the POS was told `Terminal Error`. Authorised-but-reported-failed
is the worst outcome a POS can be handed, and a retry after it double-charges. `processDeclinedTransaction`
at line 143 has the identical bug.

The fix is to move the two commits to the main dispatcher. Left alone: it is unrelated to redaction
and deserves its own change.

### Terminal state

Both settled to empty. .165 batch 000679, 2 sales / RM18.00 (the crashed sale plus its retry);
.162 batch 000679, 1 sale / RM9.00.


## 33. Fragment commits moved to the main thread -- MF919 only (2026-09-09)

Item 32's crash. Fixed in **four MF919 files**; the two Pro files named in item 32 turned out not to
have the bug.

### Correction to item 32

Item 32 said Pro had the same pattern. **It does not.** Pro's `TransactionResultFragment` and
`TransactionResultQrFragment` launch a coroutine that calls *only* `processBackgroundTask()`; the
fragment commits happen in `onViewCreated`, on the main thread. MF919 wraps **both** the commits and
the background task in one `Dispatchers.Default` launch -- that is the whole difference.

The claim came from grepping "file contains a `Dispatchers.Default` launch and a `commit()`" without
opening the call site. Same failure as section 22: **a structural observation treated as content
evidence.** The Kotlin compiler caught it -- making Pro's functions `suspend` broke the non-suspend
callers -- and the change to Pro was reverted.

### What was fixed

| file | commits | dispatcher at the call site |
|---|---|---|
| `TransactionResultActivity` | 2 | `CoroutineScope(Dispatchers.Default).launch` |
| `TransactionResultQrActivity` | 2 | `CoroutineScope(Dispatchers.Default).launch` |
| `TransactionViewListActivity` | 1 | `withContext(Dispatchers.IO)` |
| `TransactionViewListQrActivity` | 1 | `withContext(Dispatchers.IO)` |

Each function became `private suspend fun … = withContext(Dispatchers.Main) { … }`. The CME is now
structurally impossible: the commit and `OnBackPressedDispatcher` both run on the main thread, so
nothing can iterate the callback list while another thread mutates it.

That the four MF919 sites compiled as `suspend` while Pro's did not is itself the proof of which
were off-main: a non-suspend caller cannot call a suspend function.

`DenominationTransactionResultActivity`, Pro's `AttendFragment` and `UnAttendFragment` also commit,
and all three were checked: main thread, untouched.

### Verified on .165

Four sales (inv 001932-001935) and a `ForceVoid` void, all approved. **No new crash** -- the last
`CrashHandler Triggered` in the file is still 16:26:00, the one from before the fix. Settled: batch
000680, 3 sales / RM3.06, 1 void / RM1.04.

### Found while verifying: a THIRD unarmed PAN path -- settlement batch upload (NOT fixed)

Checking redaction after the run turned up 8 raw PANs, all from one place:

```
16:29:00.492 -(IsoActivity)          batchUpload IsoHelper:: ...
16:29:00.505 -(IsoActivity)          batchTransList :: 2
16:29:00.558 -(IsoStepsBsnCardZone)  MTI :: 0320
16:29:00.613 -(IsoStepsBsnCardZone)  DF02 :: 5181231400000411
16:29:00.615 -(EMVTAG)               TLV: AddTag DF63 Data=5181231400000411
16:29:00.627 -(IsoStepsBsnCardZone)  DE2(9) :: 165181231400000411
```

**Settlement's batch upload** (`IsoActivity:1439`) walks `batchTransList` and forms an 0320 per
record from `transItem.batchData` -- the same batch-table PAN a void uses, and nothing registers it.
Line 1448 also logs `"Batch Upload Transaction :: $transItem"`, the whole model.

It is intermittent for a bad reason: it masks only when a card transaction happened recently enough
that its registration is still live. The 16:29 settlement leaked; a later one, run seconds after a
void had registered the same card, did not. **Unreliable masking is not masking.**

Sizing: ~6 lines per app -- pull DF02 out of `transItem.batchData` with the `EmvTag()` already
constructed at line 1449 and `registerCardData(pan, null)` at the top of the loop, before the log at
1448. Both apps have their own `IsoActivity`, so it is a two-app change. **Not done -- it is a
different subsystem from the crash this section fixed.**


## 34. Settlement batch upload armed, in both apps (2026-09-09)

Item 33's third gap, closed. The same six lines in each app's `IsoActivity`, inserted at the top of
the `batchTransList` loop -- before `"Batch Upload Transaction :: $transItem"`, which prints the
whole batch record:

```kotlin
try {
    val bPan = ByteArray(12)
    val panLen = EmvTag().getValueFrom(HexUtil.hexStringToByte(transItem.batchData), "DF02", bPan)
    if (panLen > 0) {
        LogRedact.registerCardData(HexUtil.bytesToHexString(bPan, 0, panLen).replace("F", ""), null)
    }
} catch (ex: Exception) {
    // Redaction must never be the reason a settlement fails.
    ex.printStackTrace()
}
```

Wrapped in try/catch on purpose: this runs inside the host-facing settlement recovery loop, and a
malformed batch record must not turn a logging concern into a failed settlement. The anchor was
already byte-identical in both apps, so the patch is too.

### Getting the path to actually run

A first attempt proved nothing: the settlement succeeded on the first try and **batch upload never
executed**. It is not part of a normal settlement -- it is the recovery arm, entered only when the
host answers `95` (totals mismatch):

```
Settlement Failed. Batch Upload Required
-----------------PROCESS BATCH UPLOAD [START]-------------------->
batchUpload IsoHelper:: IsoInfoModel(name=BatchUpload, mti=0320, ...)
```

Forced it the way the team already knew about: **both terminals share MID 011110000000014 / TID
40000014**, so a sale on each makes the host's totals disagree with either terminal's batch. Sale on
.162, sale on .165, then settle .165.

**And the app was force-stopped and relaunched between the sale and the settlement**, so `livePan`
started null. Without that the test is worthless -- item 33 showed this path masks by accident
whenever a card read happens to be still registered.

### Verified on both terminals

| | batch upload ran | raw PAN written |
|---|---|---|
| MF919 .165 | yes -- `Settlement Failed` -> `PROCESS BATCH UPLOAD` -> `MTI :: 0320` | **0** |
| Pro .162 | yes -- `PROCESS BATCH UPLOAD` -> `MTI :: 0320` | **0** |

```
16:46:30.856 -(IsoStepsBsnCardZone) DF02 :: 518123******0411
16:46:30.859 -(EMVTAG)              TLV: AddTag DF63 Data=518123******0411
16:46:30.877 -(IsoStepsBsnCardZone) DE2(9) :: 16518123******0411
```

### Where PAN redaction now stands

| path | MF919 | Pro | exercised on device |
|---|---|---|---|
| chip / contactless track 2 | yes | yes | yes |
| chip tag 5A | yes | yes | yes (covered by the same sale) |
| mag-stripe swipe | yes | yes | **no -- needs a physical swipe** |
| void, from the batch table | yes | yes | yes |
| settlement batch upload | yes | yes | yes, with `livePan` forced null first |

Both terminals settled to empty: .165 batch 000681, .162 batch 000680.

**The mag-stripe path remains the one unexercised branch.** Everything else has now been run with the
log checked afterwards.


## 35. Mag-stripe swipe: redaction verified, and why it answers "ZW" (2026-09-09)

A magstripe card was swiped on .165 (invoice 001950, RM0.20). Two separate findings.

### 1. The last redaction gap is now verified

This was the branch item 34 could not exercise. Every PAN-bearing line from the swipe is masked in
`TerminaLog.txt`:

```
16:50:36.658 -(CardPaymentActivity) Builder PAN:518123******0411
                                    TRACK1:B518123******0411^SALWANI  /  ^271020100000  00952000000
16:50:52.224 -(IsoActivity)         magTrack2 :: 518123******0411[len=32]
16:50:52.287 -(EMVTAG)              TLV: AddTag DF35 Data=518123******0411D271020100000952
```

**Track 1 is masked too**, which is the point of registering the value rather than wrapping call
sites: nobody enumerated track 1, and it carries the PAN. **Every path in the redaction table is now
exercised on device.**

### 2. "ZW" is deliberate, pre-existing, and in both apps

The swipe was **approved by the host** -- `respCode :: 3030` ("00"), `approvalCode :: 388970` -- and
the app then turned that into a decline and reversed it:

```
16:50:53.744  IsoActivity   respCode :: 3030            <- host approved
16:50:54.436  IsoActivity   PROCESS REVERSAL [START]
16:50:55.019  IsoSteps...   MTI :: 0400                 <- reversal sent
16:50:57.295  FragmentResult  Sale resp=(ZW)Card Declined Transaction
```

The cause is `EmvActivity.kt:285-291` (MF919) / `EmvFragment.kt:275-281` (Pro) -- **byte-identical**:

```kotlin
// Send Reversal if timeout
val respCode = Utility.HexString2ASCII(TransData.respCode)
if(respCode == "00") {
    TransData.transResult = TerminalConstants.iso.err.failed
    TransData.respCode = Utils.ASCIItoHexString("ZW")
    ...
```

`ZW` is `CardErrorDataEnum.TAG_ZW` = "Card Declined Transaction". The branch is **unconditional on
approval** -- there is no timeout test despite the comment, and no check of
`Utils.megStripEntryEnable()` or any other config. So on both apps a magstripe sale is authorised,
then immediately reversed and reported declined.

**Nothing to do with the redaction work.** The ZW code sits ~110 lines after the `registerCardData`
line added in item 32, in a branch untouched by it, and Pro carries the same code with the same
result while Pro's mag path was edited identically.

**Open question for the team, not a code change:** is "authorise then always reverse" the intended
magstripe posture? It is a normal way to refuse magstripe while still exercising the host, and the
scheme rules may require exactly that. But the comment above it says *"Send Reversal if timeout"*,
which is not what the code does, so intent cannot be read from the source. **Worth confirming before
the merge freezes this behaviour into shared code** -- and if it is intended, the comment should say
so.


## 36. Magstripe reversal guard corrected -- and carried to the other two sites (2026-09-09)

Item 35 flagged that an approved magstripe sale was being turned into `ZW` and reversed. **Gavin
fixed it** in `EmvActivity.onFindMagCard`, replacing the unconditional check with the guard the same
file already used for pre-auth reversal at line 1881:

```kotlin
// Send Reversal if timeout
if(TransData.transResult != TerminalConstants.iso.err.txnApproved &&
   TransData.transResult != TerminalConstants.iso.err.txnNotAllowed &&
   (TransData.transResult == TerminalConstants.iso.err.communicationTimeout ||
    TransData.respCode.isEmpty())) {
```

Now the reversal fires only on a genuine no-answer -- which is what the comment always claimed --
and an approved swipe stays approved. The `transResult = failed` / `respCode = ZW` /
`prevInvoice` / `prevStan` assignments are commented out; `processReversal` works from
`TransData.transactionDb`, not from `prevInvoice`/`prevStan`, so nothing on the reversal path needed
them.

### Carried to the two sites it missed

The same block existed at three places. Applied the identical fix to the other **magstripe** one:

| site | what it is | action |
|---|---|---|
| MF919 `EmvActivity:287` `onFindMagCard` | magstripe | fixed by Gavin |
| MF919 `EmvActivity:567` `onSearchResult` | magstripe, second reader callback | **fixed to match** |
| Pro `EmvFragment:277` `onFindMagCard` | magstripe, Pro's only one | **fixed to match** |
| MF919 `EmvActivity:1388` / Pro `EmvFragment:1019` | `ret == Emv_Declined` | **left alone** |

**Why the last row is not the same bug.** There the chip declined *after* the host approved, so
reversing and reporting failed is correct -- that is the one case where turning an `00` into a
reversal is right. Only the magstripe sites were converting a plain approval.

Style mirrors the fix as written, commented-out lines included, so all three magstripe sites read
identically. Worth deleting the commented block once the behaviour is confirmed on hardware.

### Status

Both apps build, `:core` 73/73, both builds installed on .165 and .162. **Not yet verified** -- it
needs another physical swipe. Expected: approved, no `PROCESS REVERSAL`, no `MTI :: 0400`, and the
result screen showing the approval rather than `(ZW)Card Declined Transaction`.


## 37. Pro's magstripe sale never worked -- found by testing it (2026-09-09)

### Audit of the magstripe rework

Gavin's changes, reviewed:

- **The reversal guard is now correct and identical in both apps** (MF919 `EmvActivity:287`, Pro
  `EmvFragment:282`), matching the pre-existing pre-auth precedent. The commented-out `ZW` lines were
  deleted rather than left in -- cleaner than the mirror this document proposed in item 36.
- **MF919's duplicate magstripe reader path was deleted** -- the whole
  `magCardReader?.searchCard(OnSearchMagCardListener)` block and its `onSearchResult(retCode,
  magCardInfoEntity)` callback, about 28 KB. MF919 now has **one** magstripe entry point
  (`onFindMagCard`), the same shape as Pro, and it is the one that actually fires on a swipe.
- `LogRedact.registerCardData(magCardInfoEntity...)` survives in both.
- The only **active** `ZW` assignments left are the `Emv_Declined` paths, which are correct.

**Residue, not fixed:** MF919 still declares `private var magCardReader: MagCardReader?` and calls
`magCardReader?.stopSearch()` at two places. Nothing assigns it any more, so both calls are null-safe
no-ops. Pro has its equivalent commented out.

### The bug the swipe exposed

Pro was swiped and **hung on "Bank Authorization / Waiting for Approval" forever**. No host request
was ever sent -- the log stops dead between `SchemeID :: 20` and the invoice allocation.

`IsoActivity.processOnlineSale`, Pro line 277:

```kotlin
//TODO MF919
val track2 = EmvUtil.readTrack2()
val track2Delimiter = track2.indexOf("D")
val cardMask = track2.substring(0, track2Delimiter)
```

A swipe has no chip session, so `readTrack2()` returns empty, `indexOf("D")` is `-1`, and
`substring(0, -1)` throws inside the enclosing `acquirerIsoModel?.let { }`. The exception is
swallowed, the flow simply stops, and the progress dialog stays up. **The `//TODO MF919` comment was
marking exactly this.**

MF919 has the finished version at its line 290, and has had all along:

```kotlin
val track2 = if (TransData.magTrack2Len > 0){
    val magTrack2str = Utils.byteArrayToAsciiString(TransData.magTrack2, 0, TransData.magTrack2Len)
    log.appendLine(logClassName, "magTrack2 :: ${LogRedact.track2(magTrack2str)}")
    magTrack2str
} else {
    EmvUtil.readTrack2()
}
```

Ported to Pro. **One site only** -- MF919 carries the mag-aware branch in `processOnlineSale` and
`processSaleCompCardPresented`, and Pro has no `processSaleCompCardPresented`. Pro's other three
`readTrack2()` calls (`processPreauth`, `processEppSale`, `processCashOutSale`) match MF919's plain
ones exactly, so they were left alone.

Nothing reached the acquirer, so there was nothing pending to clean up. Terminal cleared with a
force-stop.

**Pro's magstripe sale path had therefore never worked.** It only surfaced now because this is the
first time anyone swiped a card on Pro -- item 34 had listed mag-stripe as the one unexercised
branch, and exercising it found more than the redaction question it was meant to answer.

Built and installed on .162 (over USB, serial 93250606780014). **Awaiting a swipe to verify.**

### Verified -- Pro .162, swipe after the fix (2026-09-09 17:46)

```
17:46:49.948  IsoActivity  SchemeID :: 20
17:46:49.950  IsoActivity  magTrack2 :: 436509******2381[len=32]   <- the fix: track from TransData
17:46:49.987  IsoActivity  Invoice No :: 001950
17:46:51.427  IsoActivity  respCode :: 3030                        <- host approved
17:46:51.441  IsoActivity  approvalCode :: 066983
17:46:51.852  TransactionResultFragment  Transaction Approved...
```

| check | result |
|---|---|
| reaches the host | yes, `strPosEntryMode :: 0801` (swipe), approved in ~1.5 s |
| result screen | **Approved** -- not `(ZW)Card Declined Transaction` |
| reversal sent | **none** -- `MTI :: 0400` count 0, the guard held |
| raw PAN in `TerminaLog.txt` | **0** |
| receipt to TMS | `RESP_CODE=0000` |

RM0.66, invoice/STAN 001950. **Pro's magstripe sale works for the first time**, and the magstripe
redaction branch is now verified on Pro as well as MF919 -- the last unexercised row in item 34's
table is closed.

### Verified -- MF919 .165, swipe after the fix (2026-09-09 17:55)

```
17:55:54.829  Search Card   onFindMagCard                          <- the surviving reader path fires
17:55:54.838  CardPayment   Builder PAN:436509******2381 TRACK2:436509******2381[len=32]
17:55:58.776  IsoActivity   magTrack2 :: 436509******2381[len=32]
17:55:58.876  IsoActivity   strPosEntryMode :: 0801                <- swipe
17:56:00.250  IsoActivity   respCode :: 3030                       <- host approved
17:56:00.260  IsoActivity   approvalCode :: 528689
17:56:00.264  IsoActivity   Transaction Result :: 0
17:56:01.515  TransactionResultActivity -> FragmentReceipt         <- receipt, not the decline screen
```

| check | result |
|---|---|
| reader path | `onFindMagCard` -- the one that survived the duplicate-path deletion |
| result screen | **FragmentReceipt** (approved), not `(ZW)Card Declined Transaction` |
| reversal sent | **none** -- `MTI :: 0400` count 0 |
| raw PAN in `TerminaLog.txt` | **0** |

RM0.40, invoice 001963, batch 000684.

**Both apps' magstripe paths are now working and verified**, and the same run re-exercised two earlier
fixes without incident: the fragment commit reaching `FragmentReceipt` on the main thread (item 33),
and mag-stripe redaction (item 32). Deleting MF919's second reader path cost nothing -- the hardware
uses `onFindMagCard`.


## 38. Settlement after the magstripe tests -- and a scheme misclassification (2026-09-09)

### MF919 .165 settled clean

Batch 000684, response `3030`:

| scheme | sale count | sale total |
|---|---|---|
| Visa | 1 | 0.60 |
| Master | 1 | 0.40 |
| MyDebit | 0 | 0 |

**Zero raw PANs** across the whole window -- swipe, sale, settlement and batch upload.

(Pro was settled too but had been unplugged from USB by then, so its log is unverified here.)

### The two sales were the SAME Visa card

| invoice | amount | schemeId | card | how |
|---|---|---|---|---|
| 001963 | 0.40 | **20** | `436509******2381` | **swiped** |
| 001964 | 0.60 | 11 | `436509******2381` | tapped |

BIN `436509` is Visa. Tapped it settles as **Visa**; swiped it settles as **Master**.

### Why

`EmvActivity:207-213` (and Pro's equivalent) sets the scheme from the **service code**, never the BIN:

```kotlin
if (magCardInfoEntity.serviceCode.startsWith("2") || magCardInfoEntity.serviceCode.startsWith("6")) {
    TransData.schemeId = "20"
} else {
    TransData.schemeId = "97"
}
```

Service code `201` -> starts with `2` -> `schemeId = "20"`, and
`CardSchemeEnum.detectByCardSchemeID` maps `"20", "21", "22", "92" -> MASTERCARD`.

There **is** a BIN fallback at `IsoActivity:2279-2286`, but it only runs when the id maps to
`UNKNOWN`:

```kotlin
var cardType = CardSchemeEnum.detectByCardSchemeID(TransData.schemeId)
if(cardType == CardSchemeEnum.UNKNOWN) {
    val track2 = TransData.getFromTransactionDb("DF02", 16).replace("F", "")
    cardType = CardSchemeEnum.detect(track2)
}
```

`"20"` maps to MASTERCARD, not UNKNOWN, so the fallback never fires and the Visa swipe is filed
under Mastercard. Had the mag path produced an id that maps to UNKNOWN, the BIN check would have
classified it correctly.

### Not fixed -- this one is the acquirer's call

Two reasons to leave it:

1. **What `schemeId` a magstripe transaction should carry is defined by BSN/Cardzone**, and it goes
   on the wire (tag `3F11`, and it selects the `posEntryMode` and settlement counter row via
   `settlementRefTag`). Changing it is a wire change, not a display fix.
2. **The settlement totals per scheme are what the acquirer reconciles against.** Moving RM0.40 from
   Master to Visa in the report is only right if the acquirer expects it there.

It is present in **both apps** identically -- Pro's swipe logged `strSchemeIdnow=20` for the same
card. **Confirm the intended behaviour with the acquirer before the merge fixes it in one place.**


## 39. What `schemeId` actually is -- item 38 sharpened (2026-09-10)

Item 38 called this "a scheme misclassification" and left the question vague. Traced properly, it is
narrower and more definite than that.

### `schemeId` is brand x entry-method, and the table is in our own source

`TerminalConstants.java:154`, `isoInfo.getSchemeId(scheme, schemeType, acqCode)`, with
`schemeType` documented in the signature as `/*2=contactless, 1=contact, 3=magstripe*/`:

| brand | contactless | **magstripe** | contact |
|---|---|---|---|
| VISA | 11 | **12** | 91 |
| MASTER | 21 | **20** | 92 (and 22 for type 4) |
| PBOC | 31 | **30** | 93 |

So `20` does not mean "magstripe". It means **"Mastercard, magstripe"**. And `12` -- the value a
swiped Visa should carry -- exists in the table and is simply never produced.

### The chip path uses the table; the magstripe path does not

Chip, `EmvActivity:1079-1085`:
```kotlin
val stringAid = EmvUtil.getPbocData("4F", true)
TransData.schemeType = CardUtil.getCardTypFromAid(stringAid)          // brand, from the AID
TransData.schemeId = isoInfo.getSchemeId(TransData.schemeType, TransData.payMethod, TransData.acqCode)
```

Magstripe, `EmvActivity:207-213`:
```kotlin
if (serviceCode.startsWith("2") || serviceCode.startsWith("6")) TransData.schemeId = "20"
else                                                            TransData.schemeId = "97"
```

**The condition is not a brand test.** Position 1 of a service code is interchange
(1/2 international, 5/6 national, 7 private) -- it says nothing about Visa vs Mastercard. So every
swipe of an international or national card is labelled Mastercard-magstripe. There is no AID on a
swipe, but there is a PAN, and the BIN is never consulted.

`payMethod` is already `paymentMethod.Meg = 3`, exactly the `schemeType == 3` the table expects, so
the chip path's own call would work verbatim if given a brand.

### `CardSchemeEnum` is innocent

`detectByCardSchemeID` maps `"11","12","91" -> VISA` and `"20","21","22","92" -> MASTERCARD`, which
**matches the table exactly**. Item 38 implied the enum was the problem; it is not. It reports
faithfully what the mag path told it.

The `else` branch is the accidental proof: `"97"` maps to `UNKNOWN`, which triggers the BIN fallback
at `IsoActivity:2282-2286` and classifies correctly. **The failing branch is the one that thinks it
knows the answer.**

### Why this is not a one-line fix

`schemeId` is not display-only. It feeds three things:

1. **Tag `DA`** in the transaction buffer (`IsoActivity:324`) -- goes to the host.
2. **The POS entry mode row**: `posEntryMode` is looked up by `"${schemeTag}-${schemeId}"`, i.e.
   `visam-20` today, which returned `0801`. Changing to `12` looks up **`visam-12`**, and if that row
   is not provisioned the code falls back to `visam-<mti>` then `visam` -- silently substituting a
   different entry mode into `DF22`. **This is the real risk of the change**, and it is checkable:
   look for a `visam-12` row in `IsoBatchInfo`.
3. **Settlement grouping**, via `cardType -> typeIdentifier -> settlementRefTag` -- which is how
   RM0.40 of Visa landed in the Master bucket in item 38.

### The one question for BSN/Cardzone

Everything above is answerable from our own source **except this**: MF919 has been sending `20` for
every swipe in production. Does BSN's host and settlement reconciliation expect Visa swipes under
`12` (our table's answer), or has `20` become the de facto contract? Correcting it changes a
host-visible value and moves money between per-scheme settlement totals.

**Recommended once confirmed:** derive the brand from the PAN with `CardSchemeEnum.detect(pan)` and
call `isoInfo.getSchemeId(brand, TransData.payMethod, TransData.acqCode)` -- the same call the chip
path makes -- keeping the `"97"` fallback when the BIN matches nothing. Provision `visam-12` first.

Still not changed.

### `visam-12` exists -- and the risk in item 39 is gone (2026-09-10)

`DatabaseTables.kt` seeds `IsoBatchInfo`, and both apps carry the row:

```
Triple("posEntryMode", "visam-12", "0801")     // Visa magstripe
Triple("posEntryMode", "visam-20", "0801")     // Master magstripe
Triple("posEntryMode", "visam-30", "0801")     // PBOC magstripe
Triple("posEntryMode", "visam-91", "0051")     // Visa contact
Triple("posEntryMode", "visam-92", "0051")     // Master contact
Triple("posEntryMode", "visam-93", "0051")     // PBOC contact
Triple("posEntryMode", "visam-97", "0021")
Triple("posEntryMode", "visam-Moto", "0010")
```

**`visam-12` maps to `0801`, identical to `visam-20`.** So correcting a Visa swipe from `20` to `12`
produces the **same `DF22`**. The "silent entry-mode substitution" risk named in item 39 does not
exist.

The table is also independent confirmation of what `schemeId` means: the three **magstripe** ids
(12, 20, 30) all give `0801`, and the three **contact** ids (91, 92, 93) all give `0051`. The entry
mode is decided by the entry-method half of the id, exactly as `getSchemeId` implies. Contactless
(11, 21) has no row and falls through to `visam` -> `0071`, which is the contactless entry mode --
also consistent.

Someone provisioned `visam-12` deliberately. **The configuration for a correctly-labelled Visa swipe
has been in place all along; only the code that would produce `12` is missing.**

The device matches this seed: the live swipe looked up `visam-20` and got `0801`, exactly the seeded
value.

### What is left of the change

| effect | before | after | risk |
|---|---|---|---|
| `DF22` POS entry mode | `0801` | `0801` | **none** |
| tag `DA` / schemeId | `20` | `12` | host-visible |
| settlement bucket | Master | Visa | moves per-scheme totals |

Two host-visible effects, not three. Still needs BSN's confirmation before changing, but the
question is now only *"do you want Visa swipes reported as Visa"*, not *"will this break the entry
mode"*.

### Decision: parked pending BSN (2026-09-10)

**Left as it is.** Gavin is checking the intended behaviour with BSN/Cardzone before anything
changes. No code touched.

**When the answer comes back:**

- *"Visa swipes should be `12`"* -> in `EmvActivity:207-213` (MF919) and the matching block in Pro's
  `EmvFragment`, replace the service-code test with `CardSchemeEnum.detect(pan)` feeding
  `isoInfo.getSchemeId(brand, TransData.payMethod, TransData.acqCode)` -- the identical call the chip
  path makes at `EmvActivity:1085`. Keep `"97"` when the BIN matches nothing. No config work needed;
  `visam-12` is already seeded and gives the same `0801`.
- *"`20` is the contract, leave it"* -> then `isoInfo.getSchemeId`'s `VISA/magstripe = "12"` entry and
  the `visam-12` row are both dead, and the service-code test deserves a comment saying it is
  deliberate rather than looking like the oversight it currently resembles.

Either way it wants deciding **before** the merge, because after convergence this code exists once
and serves both apps -- and a third terminal model is coming.


## 40. WebSocket transport verified -- and a stale-response defect found (2026-09-10)

Item 34 listed WebSocket as changed-but-never-exercised. `CABLE_CONNECTION` was switched to
`WEBSOCKET_SERVER` in TMS; the terminal picked it up on restart and bound port 8080.

Tested with a dependency-free WebSocket client (`scratchpad/wsclient.py`) -- neither `websockets`
nor `websocket-client` is installed on the dev machine, and adding packages to test someone's
terminal is the wrong trade.

### The rewritten path works

**Cancel, no card, no money:**

```
[send] {"TransactionType":0}
[recv] {"TransactionType":0,"ResponseCode":"00","ResponseDescription":"No Session Running"}

10:10:47.175  WebSocketServer  POS connected :: /192.168.100.194:63930 (clients=1)
10:10:47.219  HTTPServer       Check WebSocket Incoming :: {"TransactionType":0}
10:10:47.229  HTTPServer       Set Response Msg :: ...No Session Running
```

54 ms through the whole chain rewritten in item 25 --
`checkWebSocketIncoming -> dispatchTransportRequest -> submitRequest -> handleIncomingRequest ->
setResponseMessage -> future -> deliverToTransport(WEBSOCKET) -> broadcast`.

**Full sale over WebSocket, card parked:** approved. STAN 001969, RRN 625310001969, approval 693938,
RM0.55, VISA CREDIT contactless, batch 000685, `PosReference: WS-SALE2` echoed. **Zero raw PANs.**

### DEFECT: the response queue survives client disconnects and replays to the next client

The sale response arrived **second**. The first frame was a **four-minute-old response for a
different transaction** -- an earlier WS sale that timed out at the card screen while no client was
attached:

```
[recv 1] ResponseCode:""  ResponseDescription:"Failed"  STAN:"000001"  PosReference:"WS-SALE"
[recv 2] ResponseCode:"00" ResponseDescription:"(00)Approved" STAN:"001969" PosReference:"WS-SALE2"
```

`WebSocketServer.processQueue`:

```kotlin
private suspend fun processQueue() {
    while (messageQueue.isNotEmpty() && connectedClients.isNotEmpty()) {
        broadcastMessage(messageQueue.poll())
    }
}
```

`messageQueue` is a **static** `ConcurrentLinkedQueue`, and the drain is gated on
`connectedClients.isNotEmpty()`. With no client attached nothing is dequeued, so every undelivered
response accumulates for the life of the process and is broadcast to whichever client connects next.

**The harm:** a POS that drops its connection mid-transaction and reconnects receives a stale
`Failed` for a transaction the terminal may have approved, ahead of its own response. Same class as
the ZW bug in item 35 -- *terminal approved, POS told failed* -- and a POS that reads the first frame
as its answer will act on it.

**What limits it:** the response echoes `PosReference`, so a POS that correlates can discard the
stale frame. That is a mitigation available to the integrator, not a property of the terminal.

**Pre-existing, not from item 25.** The queue and its drain are untouched by this programme; the old
`setResponseMessage` routed to the same `WebSocketServer.receiveResponseMessage`. It surfaced now
only because this is the first time anyone connected twice to the WebSocket listener.

**Not fixed.** Options, cheapest first: drop the backlog on `onOpen` (a new POS session should never
inherit one); or timestamp entries and discard beyond a TTL; or key the queue by `PosReference` and
deliver only what the connected client asked for. The first matches how the HTTP path already
behaves -- item 25's `setResponseMessage` discards a response with no waiting caller and logs
"Unrouted response discarded". **The WebSocket queue is the one transport that does not do that.**

### All six WebSocket cases run (2026-09-10)

Gavin's case list, run against MF919 .165. Captured as a reusable skill at
`.claude/skills/terminal-function-test/` rather than as one-off commands.

| # | case | result |
|---|---|---|
| 1 | Normal sale | PASS — approved 3.5 s, STAN 001970 |
| 2 | Drop the socket mid-transaction, reconnect | PASS — **response survived a 14 s disconnect** |
| 3 | Void with bypass | PASS — void STAN 001971 against invoice 001970 |
| 4 | Cancel **before** the card is read | PASS — `SHC009` to the canceller, `Failed` to the sale's caller |
| 5 | Cancel **after** the card is read | PASS — `SHC000`, sale completed untouched |
| 6 | Settlement | PASS — sale 4 / RM4.65, void 1 / RM1.10, batch 000685 |

Zero raw PANs throughout.

**Cases 4 and 5 are the same request with opposite correct answers**, decided by
`appRunningProcess` inside `handleCancelWhileBusy`. That guard is what stops a cancel tearing down
a transaction already at the host, and item 25 preserved it through the in-flight rewrite. It is
now covered by a repeatable test rather than by reading the code.

**Case 2 reframes the item 40 defect.** The response was produced at 10:30:16 with no client
attached and delivered 400 ms after the reconnect at 10:30:30 — so the queue is not simply wrong,
it is deliberate store-and-forward with **no expiry and no addressing**:

- own delayed response survives a reconnect — the behaviour a POS depends on
- someone else's stale response is replayed — the defect

Any fix must keep the first. That rules out the cheapest option floated in item 40 (flush the
backlog on `onOpen`), because a POS legitimately reconnects to collect its own result. A TTL, or
delivering only frames whose `PosReference` matches, keeps both properties. **Item 40's suggested
fix is superseded by this.**

**Case 6 also covered the batch-upload recovery arm** — a `95` mismatch drove four `0320` uploads,
then the `0500`, then an `0800`, all `3030`. Worth knowing: `SettlementActivity` logs
`User Cancel :: back pressed, leaving settlement screen` on the **normal** exit to the home screen
after a successful settlement. It is not an interruption, and reading it as one wastes time.


## 41. Cable transport verified -- the last untested path (2026-09-10)

Items 24 and 25 left cable as changed-but-never-exercised, and item 34's table had it as the
remaining gap. `CABLE_CONNECTION` switched to `USB` in TMS, picked up on restart.

**Setup, for the record:** in USB mode the terminal presents its own serial gadget and the PC sees
it as a COM port once the app has opened it — `COM7 | USB Serial Device | SER=98211000000816`,
matching the adb serial. **115200 8N1**, from `startServeCable`'s `"115200,N,8,1"`.

Neither direction is framed. Inbound, `cableConnectionReceiving` ends a message on a read that
returns nothing, so a request is "the bytes, then a quiet gap". Outbound, `onBackToRS232` writes
raw JSON with no terminator. The driver (`scripts/cabletest.py`) reads by brace depth accordingly.

### Results -- MF919 .165

| # | case | result |
|---|---|---|
| 1 | Normal sale | PASS — approved 4.2 s, STAN 001983, appr 206702 |
| 2 | Void with bypass | PASS — void STAN 001984 against invoice 001983 |
| 3 | Cancel **before** the card is read | PASS — `SHC009` to the canceller, `Failed` to the sale's caller |
| 4 | Cancel **after** the card is read | PASS — `SHC000`, sale completed (STAN 001985) |
| 5 | Settlement | PASS — batch 000686, sale 1 / RM2.20, void 1 / RM2.10 |

Zero raw PANs, zero unexpected reversals.

The log confirms these went over the cable rather than a leftover socket:

```
Final Message :: {"TransactionType":1,...}
Different request while busy (CABLE), reject SHC000
onBackToRS232 Send Status :: 0
```

`(CABLE)` is `Origin.CABLE` inside the in-flight model — so the cable path exercised the same
`submitRequest` / `dispatchTransportRequest` / `deliverToTransport` machinery item 25 introduced,
and the `appRunningProcess` cancel guard behaved identically to WebSocket.

### Every transport now exercised

| transport | status |
|---|---|
| HTTP | verified repeatedly (items 25, 29, 32, 34) |
| WebSocket | verified, 6 cases (item 40) |
| **Cable (USB)** | **verified, 5 cases (here)** |
| Cable (RS232) | **still not run** — different branch, needs an adapter |
| Cable on Pro | **still not run** |

RS232 remains the one branch with no runtime evidence: it uses `usbSerialPort.read`/`.write`
rather than `serialPortDriver`, and item 24 ported it into Pro without ever executing it.

### Captured as a skill

`.claude/skills/terminal-function-test/` — SKILL.md plus `scripts/{wstest,cabletest}.py` and
`references/{websocket,cable,http}.md`. WebSocket and cable are complete with recipes, gotchas and
results; HTTP is still a stub. Two things the skill records that cost time to learn: the cancel
delay *is* the test (2 s rejects, 4 s arrives too late), and a settlement response is
array-wrapped on MF919 — a display filter that assumed an object made a passing settlement look
like a failure.


## 42. Pro: both transports, both integrations (2026-09-10)

MF919 speaks only its own protocol, so one pass covers it. Pro supports both, and they use
different type numbers — a pass on old proves nothing about new. So every case was run twice.

### 22 cases, all pass

| # | case | cable old | cable new | WS old | WS new |
|---|---|---|---|---|---|
| 1 | Normal sale | PASS 001988 | PASS 001993 | PASS 001999 | PASS 002005 |
| 2 | Drop mid-transaction, reconnect | n/a | n/a | PASS +40 ms | PASS +40 ms |
| 3 | Void with bypass | PASS 001989 | PASS 001994 | PASS 002000 | PASS 002006 |
| 4 | Cancel **before** card read | PASS SHC009 | PASS SHC009 | PASS SHC009 | PASS SHC009 |
| 5 | Cancel **after** card read | PASS SHC000 | PASS SHC000 | PASS SHC000 | PASS SHC000 |
| 6 | Settlement | PASS 000687 | PASS 000688 | PASS 000689 | PASS 000690 |

Zero raw PANs, zero unexpected reversals, in either transport or either integration. Origin tags
in the log confirm the traffic went where intended: 6 `(CABLE)`, 8 `(WEBSOCKET)`.

**The in-flight model behaves identically across all four combinations** — same `SHC000` when the
card is already being read, same `SHC009` when it is not, same store-and-forward on a WebSocket
reconnect. That is the strongest evidence yet that item 25's rewrite did not change per-transport
behaviour.

### The routing rule, confirmed by use

`IsOldIntegration` is read **by value**, with the amount's JSON type as the fallback: string
selects old, number-of-cents selects new. Void, cancel and settlement carry no amount, so the
old-integration versions of those **must** send `IsOldIntegration:"true"` — otherwise they route
new and are rejected for a missing `PaymentChannel`. This matches `helpers.IntegrationMode`
exactly (item 20 and the memory note), now demonstrated rather than read.

New integration is asymmetric on the wire: a request sends `460` cents, the response echoes
`"4.60"` as a string. New in, old out. Worth stating in the integrator docs.

### A per-app difference: the cancelled sale's own caller

Both apps answer the *canceller* `SHC009`. They differ on what the cancelled transaction's caller
receives:

| | response |
|---|---|
| **Pro** | `SHC005` `(SHC005)User Cancel the Transaction` |
| **MF919** | `{"ResponseCode":"","ResponseDescription":"Failed",...}`, STAN `000001` |

**An empty `ResponseCode` is not in the vendor contract**, and STAN `000001` is the reset default
rather than a real trace number. Pro names the reason. Recommend MF919 adopt Pro's `SHC005` here —
it is a response-shape change on a path a POS sees whenever an operator cancels, so it wants the
same treatment as item 15's SHC007: agree it, then change it. **Not changed.**

### Transport coverage now

| transport | MF919 | Pro (old) | Pro (new) |
|---|---|---|---|
| HTTP | verified | verified (item 29) | verified (item 29) |
| WebSocket | verified (item 40) | **verified** | **verified** |
| Cable USB | verified (item 41) | **verified** | **verified** |
| Cable RS232 | not run | not run | not run |

RS232 is the only remaining branch with no runtime evidence anywhere.

### Deferred: RS232 and HTTP formalisation (2026-09-10)

Both parked deliberately, for different reasons.

**RS232** — the only ECR code path in either app with **no runtime evidence at all**. Different
branch from USB (`usbSerialPort.read`/`.write` at `dev/ttyUSB0`), ported into Pro on 2026-09-09
and never executed. Needs a USB-to-serial adapter. Recorded in the skill so nobody reads "cable is
tested" as covering it.

**HTTP** — the opposite case: the best-covered transport in practice, driven ad hoc through every
change in this programme on both apps and both integrations. Writing it up is tidying, not
coverage. Notes left in `references/http.md` as a starting point.


## 43. Merge resumed: dead code out, `IsoComm` into `:core` (2026-09-10)

**Duplication 2,308 -> 1,816 lines, 33 -> 30 files.** Three separate pieces.

### 1. `Connection.java` was dead in both apps

26 lines, a connectivity checker, never instantiated. It survived only because `IsoComm` declared
`protected Connection mConnection;` — a field that is never assigned or read. Removed the field,
then the class, from both apps.

**A grep said it was dead before the field was found.** The pattern required an `=`, so a bare
declaration slipped through and the build broke on the delete. The compiler caught it in seconds,
but the lesson is the same one as section 22: a narrow grep is not proof of absence.

### 2. `HelperLogFileName` alias retired

A deprecated `typealias` to `enums.EnumLogFileName`, which has lived in `:core` since Phase 1b —
kept so old call sites would compile. **42 files** were still going through it. Rewritten to
`EnumLogFileName` and both alias files deleted.

One call site used the **fully qualified** `com.sc.mf919pro.kotlin.helper_common.HelperLogFileName`,
so a word-boundary rename left a package prefix pointing at a class that no longer existed. Caught
by the compiler, fixed. Worth remembering for the next alias retirement.

### 3. `IsoComm` moved to `:core` — 451 lines, the host socket layer

Every transaction crosses it, so it is well covered by any card test.

**Converged first** (22 diff lines, all trivial): Pro's explicit imports over MF919's wildcards,
Pro's `SendToHost[...] (SSL :: <bool>)` log line, and **MF919's** `helperLog.appendLine("Fall Back
Plan ???")` over Pro's `System.out.println` — the uploaded log beats stdout when diagnosing from
the field.

**Three app dependencies had to go:**

| was | now | why |
|---|---|---|
| `Utils.debugLogPrint(TAG, x)` | `Timber.tag(TAG).d(x)` | the app method is only a Timber call; same route as item on `TerminalConstants` |
| `Utils.DelayMili(ms)` | `utils.Util.DelayMili(ms)` | already in `:core` |
| `Utils.atoi(s)` | `utils.ByteOps.atoi(s)` | **new in `:core`**, see below |
| `ServiceHolder` (SSL only) | `iso.CurrentCertStore` | new seam, see below |

**`ByteOps.atoi`** is not `Integer.parseInt`. It strips `.` and `,` first and answers 0 for
anything unparseable instead of throwing — and `iPort = atoi(port1)` runs on every host
connection, so a throw there would break a transaction that today merely fails to connect. The
original used Apache `NumberUtils.toInt(value, 0)`, which `:core` does not depend on, so it was
reimplemented and **pinned with four unit tests** rather than assumed equivalent. `:core` 73 -> 77
tests.

**`HostCertStore` / `CurrentCertStore`** is a new seam in `iso`, following the established pattern
(`CrashHost`, `TransactionStore`, `MdbHost`). Two methods: `openCertificate(certId)` and
`keystoreDir()`. It exists because the certificate is a raw **resource** — `:core` has no `res/`
and does not gain one (item 18) — and the keystore path comes from each app's own `ServiceHolder`.

**Deliberately a seam and not a signature change.** The alternative was passing a `Context` into
`sendToHostWithSSL`, which is tidier — but **the SSL branch is the one thing here that cannot be
tested on this bench**: BSN UAT is plain TCP (`SSL :: false` in every log today). A seam keeps that
untestable path byte-for-byte what it was; a signature change would have rewritten code with no way
to prove it still works. `CurrentCertStore` throws when unregistered rather than failing soft, so
the first SSL transaction after a wiring mistake says so plainly.

A commented-out first attempt at the SSL setup was removed on the way — it referenced
`ServiceHolder`, which is unreachable from `:core`, and would have been a dangling puzzle.

### Verified on hardware

Pro .162, sale over WebSocket: **approved, STAN 002012, appr 935773**. The log shows the moved
class doing the work:

```
12:05:36.002 -(IsoComm) Connected to IP : 202.165.24.57:15023
12:05:36.141 -(IsoComm) TIMING write=0.341231ms flush=0.004846ms waitResp=129.913923ms
12:05:36.143 -(IsoComm) Received Msg Length: 88
```

Both apps build; `:core` 77/77.

### Still per-app

**`EmvUtil.java`** (274 lines) is the next obvious move — 0 UI imports, 0 `R.` refs, and after
today's redaction work only **10 diff lines**, of which 8 are import order, a `//PROD` comment and
`public` vs `protected`. The blocker is one byte:

```java
MF919: StringUtils.createArrayList("DF81180160", "DF81190108", "DF811B01B0")
Pro:   StringUtils.createArrayList("DF81180160", "DF81190108", "DF811B0130")
```

`DF811B` is EMV kernel configuration and the values differ in bit 8 (`0xB0` vs `0x30`). I am not
going to guess what that bit means from the byte alone — it needs the MoreFun kernel spec, and
MF919 is the app that has had the recent certification work. **A certification-affecting value is
not a merge cleanup.** Until it is resolved one way, `EmvUtil` stays split.


## 44. `DF811B` ruled, `EmvUtil` converged (2026-09-10)

**Ruling: Pro takes MF919's value.** `DF811B0130` -> `DF811B01B0`. MF919 is the app carrying the
recent certification work, so its kernel configuration is the one to keep.

### Now functionally identical

With that settled, the remaining `EmvUtil` differences were cosmetic and are gone: two `//PROD`
trailing comments, and `getPanFromTrack2`'s visibility.

**The visibility went the other way from the first attempt.** Converging MF919 *down* to Pro's
`protected` broke the build — `EmvActivity:1044` calls it from another package. So both are now
`public`: the wider visibility is the one an app actually needs, and Pro was simply narrower
because nothing in Pro happened to call it. A reminder that "take the stricter one" is not
automatically right.

`EmvUtil` now differs by **one line of import order**, which disappears when the single `:core`
copy is written.

### Tested on Pro — a kernel config change earns a card

Contactless sale after the change: **approved, STAN 002014, appr 770524.** The EMV outcome matches
a pre-change Pro sale on the same card exactly:

| | TVR | CVM | AID | SchemeID |
|---|---|---|---|---|
| before (2026-09-09) | `0000000000` | `1F0302` | `A0000000031010` | `11` |
| after | `0000000000` | `1F0302` | `A0000000031010` | `11` |

**What that does and does not show.** It shows the Visa contactless path produces an identical
kernel outcome under the new value. It does **not** cover Mastercard, offline PIN, or any path this
one card and one transaction does not touch — `DF811B` is per-kernel configuration, and only one
kernel was exercised. Worth a wider sweep before this reaches production, but the change is now
evidenced rather than assumed.

### The move itself is scoped, not done

`EmvUtil` can go to `:core` — the MoreFun SDK is already a `:core` dependency (`ysdk` jar, and
`mdb/` already imports `com.morefun`), so its `com.morefun.yapi.emv.*` imports are not a blocker.
What it needs, measured rather than guessed:

| dependency | live uses | resolution |
|---|---|---|
| `DeviceHelper.getEmvHandler()` | 4, all `readEmvData(...)` | new seam method; `EmvHandler` is a yapi type `:core` can already see |
| `ServiceHolder` + `DbModelTerminalConfig` | 1 pair — reads the `"OptIn"` flag | one seam method `isOptIn()` |
| `TransData` | 1 line — `acqCode` and `schemeType` | `acqCode` is already in `CurrentTxn`; **`schemeType` needs adding**, same as `stan`/`invoiceNo` were |
| `Utils.convertLong`, `getActualAmount` | 3 | into `:core` utils, as `atoi` was |
| `Utils.debugLogPrint` | — | Timber, as in `IsoComm` |

So: one new seam (`EmvHost`: `emvHandler()` + `isOptIn()`), one extra member on an existing seam,
and two small utility moves. Every chip and contactless sale exercises the result, so it is
testable on this bench — unlike `IsoComm`'s SSL branch.


## 45. `EmvUtil` moved to `:core` (2026-09-10)

274 lines, the EMV kernel adapter. **Duplication 1,816 -> 1,542 lines, 30 -> 29 files.**

Landed in `core/src/main/java/emv/` alongside `EmvTag`. The MoreFun SDK is already a `:core`
dependency (`ysdk` jar; `mdb/` imports `com.morefun` too), so its `com.morefun.yapi.emv.*` imports
needed nothing.

### Five app dependencies, resolved five ways

| was | now | why that way |
|---|---|---|
| `DeviceHelper.getEmvHandler()` x4 | `emv.CurrentEmvHost.emvHandler()` | **new seam** — `:core` can name `EmvHandler` but has no business binding the device service |
| `ServiceHolder` + `DbModelTerminalConfig` (the `OptIn` flag) | `CurrentEmvHost.isOptIn()` | same seam; models stay per app by ruling |
| `TransData.INSTANCE.getAcqCode()/getSchemeType()` | `iso.CurrentTxn` | the seam already existed; **`schemeType` added** to it |
| `Utils.convertLong` x2 | `utils.ByteOps.convertLong` | **new in `:core`**, next to `atoi` |
| `Utils.getActualAmount` | `utils.AmountFormat.getActualAmount` | **already in `:core`** and already equivalent |
| `Utils.debugLogPrint` | Timber | the app method is only a Timber call |

`CurrentEmvHost.emvHandler()` **throws** when unregistered — every chip and contactless read goes
through it, and an unwired seam should not present as a card that mysteriously will not read.
`isOptIn()` is the exception and answers `false`: a missing config flag has a sensible default and
should not stop a transaction.

### `convertLong` is not `atoi`, and the tests say so

Both answer 0 rather than throwing, but `atoi` strips `.` and `,` first and `convertLong` does
not — EmvUtil's callers strip their own (`amount.replaceAll("\.", "")`) before calling, so
stripping again inside would double-handle an already-clean value. Pinned in
`ByteOpsNumbersTest`, including the contrast case. `:core` 77 -> 79 tests.

### Two compiler catches worth recording

**`schemeType` needed `override` in both apps.** Adding a member to `TransactionData` makes every
implementer's matching property an override — obvious in hindsight, invisible until the build ran.

**28 explicit imports were repointed, and two files had none.** `BaseActivity.kt` and
`VoidSaleFragment.kt` reached `EmvUtil` without importing it. A rename script that only rewrites
existing imports leaves those broken; they needed the import adding, not changing.

`CurrentTxnTest` also gained a `schemeType` case. `schemeTag` (acquirer routing) and `schemeType`
(card brand) differ by one letter and sit adjacent in the interface — exactly the pair that test
exists to stop being crossed.

### Verified on Pro — the log names the moved class

Sale after the move: **approved, STAN 002016, appr 266270.** EMV outcome unchanged, and the log
tag is now the `:core` package:

```
13:52:42.439 -(emv.EmvUtil) readTrack2: 436509******2381[len=32]
```

| | value |
|---|---|
| TVR | `0000000000` |
| CVM | `1F0302` |
| AID | `A0000000031010` |
| label / entry | VISA CREDIT / Contactless |

Two things in that one line: `-(emv.EmvUtil)` proves the `:core` class did the read, and the
masked PAN proves item 31's redaction registration still fires from there. Both apps build,
`:core` 79/79.

### What is left in the shared buckets

| file | lines | diff | why it stays |
|---|---|---|---|
| `Tms.java` | 274 | 25 | UI import + 2 `R.` refs |
| `ProductListRepo.kt` | 133 | 9 | repo, per app by ruling |
| `GenerateQr.kt` | 100 | 10 | UI import |
| `DbModelMerchantConfig` / `DbModelTerminalConfig` | 186 | 6 | models, per app by ruling |
| `ParameterValueEditor.java` | 33 | 2 | UI import |
| `SaleModelNew.kt` | 23 | 2 | model |

**Everything remaining in the near-identical bucket is blocked by a ruling or by UI coupling, not
by drift.** The next real gains are in the drifted set — `Utils.java` (1,584 lines, 239 diff) and
`TmsHelper.kt` (1,070, 246) are the two largest with a plausible path.


## 46. `Utils` measured: it cannot move, but a 547-line slice can (2026-09-10)

### Why the file cannot move

| | MF919 | Pro |
|---|---|---|
| lines | 1,868 | 1,635 |
| `public static` methods | 107 | 82 |

- **2 UI imports** (`AppCompatActivity`, `Gravity`), **1 `R.` ref**, and printer types
  (`FontFamily`, `MulPrintStrEntity`) — all barred from `:core` by item 18.
- **25 methods exist only in MF919, 0 only in Pro.** MF919 is a strict superset, consistent with
  it carrying work not yet ported.
- **4 shared methods have genuinely different bodies.**

So `Utils` stays per app. What moves is a second slice, the way `ByteOps` was the first.

### The slice, measured

Of 82 shared method names:

| | count | lines |
|---|---|---|
| already delegating to `:core` | 10 | — |
| **real duplicate, `:core`-safe** | **51** | **547** |
| real duplicate, blocked | 17 | 336 |
| bodies differ | 4 | — |

The 17 blocked are almost all blocked by one thing: `ServiceHolder` for a file path
(`readFromFile`, `writeToFile`, `deleteFiles`, `getIPAddress`, `playSound`, …). A small path seam
would unblock most of them — worth costing separately.

### Two corrections to my own analysis, both caught before acting

**First pass said 61 movable. It was 51.** Ten methods already have one-line delegating bodies
from the `ByteOps` slice (`return utils.ByteOps.strlen(data);`), and I had counted them as
duplicates.

**A "does core match the app?" check reported 13 methods DIFFERING. That was the tool, not the
code.** The regex matched call sites as well as declarations, so it was comparing a one-line
delegation against the real core implementation. Hand-checking `bcd2bin`, `strlen` and
`set_ushort` showed all three already delegate correctly.

Recording this because the next person will reach for the same shortcut: **a regex cannot tell a
declaration from an invocation in Java, and for these methods the difference inverts the answer.**

### How the 547 lines split, and why that matters

Roughly 15 of the 51 already have an equivalent in `:core` (`bin2bcd`, `hex2bcd`, `ASCIItoByte`,
`arrayCopy`, `toByte`, `hideCardDetails`, `getActualAmount`, `zeroPadding`, `DelayMili`,
`paddingWith`, …). Those need **deleting and delegating**, not moving — no new `:core` code.
The other ~36 are genuinely new to `:core`.

**Recommended sequencing, and the reason for it:** do the ~36 new ones first, because moving
identical code to a new home carries no ambiguity. Then the ~15 delegations — but write a test
against real inputs for each *before* delegating. Several are byte-level primitives every ISO
message depends on (`bcd2Int`, `memcmp`, `sscanf`, `Byte2ASCII`), and a delegation to a
subtly-different core version would not fail a build or a smoke test. It would produce a
malformed host message. That is not a risk to take on a textual diff, and the tooling above has
already been wrong twice on exactly this question.

Natural homes for the 36: `ByteOps` for the byte/BCD/mem group, `StringUtils` for masking and
padding, and a new file for the file-system helpers.

**Not started** — the sequencing above is a real decision about how much verification to buy.


## 47. Utils slice, tranche 1: byte primitives into `ByteOps` (2026-09-10)

Nine names / twelve overloads moved: `AsciiToByteArray`, `Byte2ASCII`, `bcd2Int`, `bcd2hex`,
`memcmp` (x3), `sscanf` (x2), `strcmp`, `findCharWithLoc`, `String2ArrayString`. Both apps now
delegate. `ByteOps` 28 -> 40 public methods; `Utils` 1,868 -> 1,771 (MF919) and 1,635 -> 1,538
(Pro), so **~97 lines of duplicated logic gone from each app.**

The file-level duplication metric is unchanged at 1,542 because `Utils` remains in the *drifted*
bucket either way — the duplication removed is inside a file that stays split. Worth knowing that
the headline number understates progress on files like this one.

### The scripted delegation corrupted three methods, and that is the story

The script iterated a match list while mutating the string, so offsets shifted and later
replacements landed on the wrong methods with the wrong argument names:

| method | became |
|---|---|
| `arrayFill` | `return utils.ByteOps.memcmp(data1, data1Offset, ...)` |
| `atoi` | `return utils.ByteOps.sscanf(dataIn, dataInOffset, ...)` |
| `memcmp(byte[], String, int)` | called with the *four*-argument overload's list |

**Git could not be used to revert** — the repository has the pre-existing ownership problem and
changing that is out of scope by instruction — so all three were repaired by hand.

Two of them were recoverable because they were destined to delegate anyway (`ByteOps` already had
`arrayFill`, and `atoi` was added earlier today). The third was a compile error. **A same-arity
slip between two overloads would have compiled silently**, which is the whole argument for having
done this with tests.

Afterwards, every `ByteOps` delegation in both apps was audited mechanically — the invariant being
`name(ownParams)` calling `ByteOps.name(ownParams)`, nothing else: **54 correct, 0 wrong.**

### The tests found two things reading the code had not

`ByteOpsPrimitivesTest`, 14 cases. Two of my initial expectations were simply wrong, and the test
corrected me rather than the reverse:

- **`Byte2ASCII` does not stop at NUL.** It appends every byte as a char, so a fixed-size
  NUL-padded buffer comes back with `U+0000` characters in it. That is exactly the mechanism
  behind the old "short read becomes a bogus SHC001" bug — callers must slice to the real length.
  Now documented in a test instead of in a war story.
- **`String2ArrayString` splits on newlines, and throws on input that has none.** With no `\n` the
  index array is empty and the `j == 0` branch still dereferences `loc[0]`. Every caller today
  passes multi-line text so it has never fired. **Pinned as an expected
  `ArrayIndexOutOfBoundsException`** so the next caller meets it as a red test rather than a
  crash on a terminal.

A raw NUL byte also ended up embedded in the test source by a shell heredoc and was replaced with
an explicit `Char(0)` construction — the same hazard that once wrote a literal `0x01` into
`SettlementFragment`. The file is verified free of control characters.

### Verified

`:core` 79 -> **93 tests, 0 failures**. Both apps build. Sale on Pro over WebSocket: **approved,
STAN 002018, appr 101021** — the byte primitives are on the ISO forming path, so this exercises
all twelve overloads.

### Remaining in the slice

| tranche | methods | target |
|---|---|---|
| 2 | ~12 text / masking / padding | `StringUtils` |
| 3 | ~8 file helpers | new `utils/FileOps` |
| 4 | ~10 terminal misc (`CVMAnalysis`, `getPinBlock`, `getPayMeythod`, …) | new home |
| 5 | 12 that `:core` already has — test then delegate | existing |

**Do the remaining tranches by hand or with a script that rewrites whole files, not by regex
splicing at computed offsets.** That is the lesson, and it is cheap to follow.


## 48. Utils slice, tranche 2: text shaping and masking into `StringUtils` (2026-09-10)

Eleven methods moved, both apps delegating: `blankSpace`, `spaceBtwNoChar`, `symbolString`,
`maskString`, `mask_pan`, `removeCarNumChar`, `removeWhiteSpace`, `DateFormat`, `TimeFormat`,
`maskIp`, `getTxnType`. `StringUtils` 60 -> 160 lines; `Utils` 1,771 -> 1,684 (MF919) and
1,538 -> 1,452 (Pro), so **another ~87 lines out of each app.**

Only one body needed touching: `getTxnType` logged through the app's `Utils.debugLogPrint`, which
became a direct Timber call. The rest moved verbatim.

Delegation audit re-run over both files: **78 correct, 0 wrong** (54 from tranche 1 plus the 22
new ones). The script now applies its edits **back to front**, which makes tranche 1's
offset-shifting corruption structurally impossible rather than merely unlikely.

### Two dead methods deleted, and one that was not dead

`hashCardNum` and `hashDataWithClearText` had **zero callers** anywhere in either app, and
`:core` already owns the real implementation of the latter in `crypto/DataHash`. Both deleted.

`hashData` I also deleted — **and that was wrong.** The compiler caught it:

```
Utils.java:574: error: cannot find symbol
        return (hashData(value, "MD5"));
```

The caller is `getChecksum`, in the same file. My caller search excluded `activity/Utils.java`
to filter out the declaration, and so it excluded the only call site as well. **Same shape as the
`Connection.java` mistake earlier in this programme: a search whose filter removed the answer.**
The rule that would have caught both: search with no exclusions first, then explain each hit.

`hashData` is restored verbatim, imports and all.

### Finding: `hashData` is a stub, and the OTA updater depends on it

The digest inside `hashData` is commented out and the method returns the string `"123"`:

```java
//byte[] messageDigest = md.digest(HexUtil.hexStringToByte(input));
//return HexUtil.bytesToHexString(messageDigest);
return "123";
```

Its caller `getChecksum` is used by `downloadApk`, which compares a freshly downloaded APK's
checksum against `ApkCheckSum.txt` and installs only when they **differ**. With the checksum
constant, the first update writes `"123"` and **every later download compares equal and is
deleted instead of installed** — the updater silently stops updating after one round.

`downloadApk` has **no callers outside `Utils` in either app**, so this is dormant: TMS handles
updates now. Restored unchanged rather than repaired, because turning the stub into a real MD5
would change how a dormant installer decides to install, and that is a decision to take
deliberately and not as a side effect of a file move. Left as an open item with a comment on the
method pointing here.

`hashData` in Pro also appears at 16 call sites in the `CounterSign` password class — **that whole
class is inside a commented-out block**, so those are not call sites. Worth stating because a
grep count alone reads as "17 live callers".

### The tests

`StringUtilsTextTest`, 14 cases, all green first run. Three pin behaviour that a reader would
otherwise call a bug and "fix":

- **`blankSpace(n)` returns `n-1` spaces.** The loop is `for (loop = 1; loop < i)`. Every caller
  has been compensating for this since the beginning, so correcting it would shift a column on
  every receipt line that uses it.
- **`mask_pan`, `DateFormat` and `TimeFormat` all throw on short input** — unguarded `substring`.
  `DateFormat`/`TimeFormat` are fed fixed-width ISO fields, so short input means the field was
  missing, which is when a crash is least welcome. Pinned as expected exceptions.
- **`maskIp` does not validate.** It returns `"xxx.xxx.xxx." + <everything after the last dot>`,
  so `192.168.100.165:8888` comes back as `xxx.xxx.xxx.165:8888` and a bare hostname passes
  through nearly intact. It is a log-tidying helper, not a redaction guarantee — do not reach for
  it where `LogRedact` is wanted.

`mask_pan` also turns out to have **zero callers**. It moved anyway (it belongs with the other
masking helpers), but its 20-wide zero padding is *not* a verified host field width, and the test
comment says so rather than inventing a contract for it.

### Verified

`:core` 93 -> **107 tests, 0 failures**. Both apps build (`app-mf919:assembleSharecommDebug`,
`app-mf919pro:assembleSharecommDebug`).

### Slice progress

| tranche | scope | state |
|---|---|---|
| 1 | 9 names / 12 overloads, byte + BCD + C-string -> `ByteOps` | done, §47 |
| 2 | 11 text / masking -> `StringUtils` | **done** |
| 3 | ~8 file helpers -> new `utils/FileOps` | next |
| 4 | terminal misc: `CVMAnalysis`, `getPinBlock`, `getPayMeythod`, `getICCUMobile`, `getPublicIP` | after |
| 5 | 12 that `:core` already has — write a test against real inputs, then delegate | last |

Tranche 3 is where the `ServiceHolder` blockers bite: 16 methods are blocked and most of them are
blocked only for a file path. **A path seam in `:core` would unblock most of them at once** —
worth doing before tranche 3 rather than moving eight file helpers and leaving the other sixteen
stranded behind the same wall.


## 49. Utils slice, tranche 3: the `AppFiles` seam and `FileOps` (2026-09-10)

The file helpers were the largest blocked group in the slice — nine methods, all blocked on
nothing but `ServiceHolder`, i.e. on a `Context`. Rather than move eight of them and leave the
rest stranded behind the same wall, this tranche put the seam in first.

`Utils` is now **1,404 lines (MF919)** and **1,172 (Pro)**, down from 1,684 / 1,452 at the end of
tranche 2 — **another ~280 lines out of each app**, and 1,868 / 1,635 when the slice started.

### First: five dead stubs deleted, not moved

Every one of these had **zero callers** in either app *and* a body whose real work was commented
out. Moving them to `:core` would only have made dead landmines easier to find and call:

| method | what it actually did |
|---|---|
| `createFolder` | whole body commented out, `return true` — callers believe a folder exists |
| `readDataBaseInByte` | reads the file into a buffer, never assigns `value`, **always returns `""`** |
| `readDataBaseInByteFromPath` | same |
| `writeDataBaseInByte` | **deletes the `.db` and its journal, then writes nothing** — a truncate |
| `writeFileInByte` | deletes the file, then writes nothing |

`writeDataBaseInByte` is the one to note: it was one call site away from wiping a database and
reporting `true`. −104 lines from each app.

Caller searches this time ran over `app-mf919`, `app-mf919pro` and `core` with **no path
exclusions** — see §48 for why that rule now exists.

### The seam

`utils/AppFiles` (71 lines) exposes exactly five things: `filesDir()`, `databasesDir()`,
`openInput()`, `openOutputAppend()`, `openAsset()`. `CurrentFiles` holds it and **throws** when
unregistered, like `CurrentCertStore` — failing soft would mean config reads quietly returning
nothing, which surfaces days later as a terminal with no TID rather than as the wiring mistake it
is.

The stream methods are on the interface rather than derived from `filesDir()` deliberately. The
app implementation still calls `Context.openFileInput` / `openFileOutput(MODE_APPEND)`, so file
mode and creation semantics are unchanged by the move — `FileOutputStream(File(dir, name), true)`
would have been *nearly* the same thing, and "nearly" is not what you want under a config file
that holds the TID.

Implementations: `Mf919AppFiles` and `ProAppFiles`, registered in each `MF919.onCreate`
**immediately after `setContext`**, before anything in `onCreate` can read a file.

### `FileOps`, and two shapes worth knowing

Nine methods moved (`checkFiles`, `fileSizeInKb`, `readFromFile`, `readFromFilePath`,
`writeToFile`, `write2File`, `deleteFiles`, `readFromAssetFile`, `cpAssetFile`) plus the private
`ChangeInFile`, which went with its only caller. 248 lines. Both apps delegate; **96 delegations
audited across both `Utils` files, 0 wrong.**

Two behaviours are load-bearing and now pinned by tests:

- **A missing file returns a one-element array holding `null`,** not an empty array. Callers do
  `value[0]` and compare against null. "Tidying" this to an empty array would turn every
  missing-config check into an `ArrayIndexOutOfBoundsException` at startup.
- **`cpAssetFile` decides "already in step" on line *count*, not content.** So a changed *value*
  in a bundled asset never re-seeds — which is exactly why a provisioned TID survives an app
  update. Documented rather than fixed: changing it would overwrite provisioned config on the next
  update.

Also recorded: `readFromAssetFile` gates on `checkFiles`, which looks in the **internal files
dir, not in assets**. Both callers happen to check the same thing first, so it works — but reused
on its own it silently returns nothing. Its name is a trap.

`cpAssetFile` has **five callers in MF919's `MainActivity` and none in Pro.** Pro seeds its config
some other way. Not chased here, but it means this code path is only live on one of the two
fleets, which is worth knowing before anyone "cleans up" the MF919 calls.

### Verified

`:core` 107 → **119 tests, 0 failures** (`FileOpsTest`, 12 cases against a real temp directory and
a fake asset bundle — these helpers were untestable before the seam, which is most of what it
bought). Both apps build.

**On hardware** (MF919 debug on the bench terminal, `product:vnd_tb8766p1_bsp_1g`): all five
`cpAssetFile` calls ran through `:core` at startup —

```
D/FileOps: ChangeInFile: false
D/FileOps: Copy Asset File: termInfo.txt      (then merchantInfo.txt, isoengine.ini, tms.txt, tmsUrl.ini)
```

— and all five files landed with real content and the expected `-rw-rw----` mode, i.e. the same
mode `openFileOutput` produced before the move. That run exercises `changeInFile`, `checkFiles`,
`readFromFile`, `readFromAssetFile`, `openAsset` and `writeToFile` end to end. Pro launched clean
on the same terminal with no unregistered-seam error.

`write2File` and `deleteFiles` are covered by unit tests and by the `writeToFile` hardware run,
but their **live** call sites are the settlement receipt files, so they want one settlement on a
terminal to be called fully verified.

### Slice progress

| tranche | scope | state |
|---|---|---|
| 1 | 9 names / 12 overloads → `ByteOps` | done, §47 |
| 2 | 11 text / masking → `StringUtils` | done, §48 |
| 3 | `AppFiles` seam + 9 file helpers → `FileOps`; 5 dead stubs deleted | **done** |
| 4 | terminal misc: `CVMAnalysis`, `getPinBlock`, `getPayMeythod`, `getICCUMobile`, `getPublicIP` | next |
| 5 | 12 that `:core` already has — test against real inputs, then delegate | last |

Still blocked and not file-related, so out of this slice: `getIPAddress`, `playSound`,
`checkBatteryStatus` (device concerns, want their own seam or to stay per app), `makeLineText`
(printer `FontFamily` + `Gravity`, stays per app under the UI-free ruling), `checkForUpdates`
(`R.` plus the dormant OTA path from §48), and `convertLong` (Apache `NumberUtils` — a tranche 5
delegate-to-`ByteOps` item).


## 50. Utils slice, tranche 4: receipt text, the install queue, the public-IP lookup (2026-09-10)

`Utils` is now **1,252 lines (MF919)** and **1,020 (Pro)** — down from 1,868 / 1,635 when the
slice started, so **roughly a third of each file is gone** and what left is shared rather than
duplicated.

### Moved

| method | new home | why there |
|---|---|---|
| `CVMAnalysis` | `utils/ReceiptText` | payment-domain receipt wording, not a string utility |
| `getPayMeythod` | `utils/ReceiptText` | same |
| `getInstallApk` | `utils/FileOps` | reads `installApk.txt`; unblocked by the tranche 3 seam |
| `removeInstallApk` | `utils/FileOps` | same |
| `getPublicIP` → `PublicIp.get()` | `helpers/PublicIp` | network, not files or text |

`ReceiptText` gets its own file rather than joining `StringUtils` because these two are not
general helpers: they encode what the acquirer's receipt rules require the customer copy to say
about PIN and signature. `getPayMeythod` keeps its spelling — renaming it would touch call sites
in both apps for no behavioural gain, and the misspelling is now at least in one place.

**106 delegations audited across both `Utils` files, 0 wrong** — `ByteOps` 54, `StringUtils` 24,
`FileOps` 22, `ReceiptText` 4, `PublicIp` 2. The one rename (`getPublicIP` → `get`) is declared to
the audit script rather than special-cased by hand.

### Deleted: two more dead methods, one of them a broken PIN block builder

`getICCUMobile` — zero callers, assembles an ICC data blob for a field nothing sends.

`getPinBlock` — zero callers, 47 lines, duplicated in both apps, and **wrong in two ways** in the
format 1 and 3 branches:

```java
while (pin.length() == 16) {              // should be < 16: this runs at most once
    int n = rand.nextInt(16);
    pin = pin.concat(Integer.toHexString(n - 1));   // n == 0 appends "ffffffff"
}
```

The loop condition means a short PIN block never gets padded to 16, and `n - 1` on a zero draw
yields `-1`, whose hex form is eight `f` characters rather than one nibble. Formats 0 and 2 look
right; 1 and 3 could not have produced a valid PIN block.

**Deleted rather than moved.** Putting a broken PIN-block builder into shared `:core` would make
it easier to find and reach for, and if terminal-side PIN blocks are ever needed the code should
be written against the ISO 9564 formats rather than resurrected from this. The bugs are recorded
here so the knowledge outlives the code. **Say the word and it comes back** — it is a deletion, not
a rewrite, so restoring it is one edit.

### `getPublicIP` moved with its hazard intact, deliberately

It starts a thread to fetch `https://myexternalip.com/raw`, then **busy-waits on the calling
thread** in 100 ms steps with **no timeout on the HTTP call**. A host that accepts the connection
and never answers parks the caller indefinitely. Both live callers (`Tms`, `UploadTMS`, for the
`TERMINAL_IP` header) are on a TMS upload thread rather than the main thread, so it presents as a
stalled upload rather than an ANR.

Left as it was on purpose. A real fix means a timeout plus a cached value, which changes what the
TMS header carries when the lookup fails — a contract question for the TMS side, not something to
decide inside a file move. **Open item.**

Two things did change, neither observable to a caller: the wait flag is cleared in a `finally`
instead of separately in the success and failure paths, and the reader is now closed, which the
original never did.

### Verified

`:core` 119 → **133 tests, 0 failures** (`ReceiptTextTest` 11, plus 4 more in `FileOpsTest` for
the install queue). Both apps build.

**On hardware, both branches of the asset seeding now covered** — and on the right units this
time:

- **Pro unit** (`product:vnd_tb8766p1_bsp_1g`, which also carries the MF919 dev build): a fresh
  install with no config, so all five `cpAssetFile` calls took the **seed** branch and wrote
  `termInfo.txt`, `merchantInfo.txt`, `isoengine.ini`, `tms.txt`, `tmsUrl.ini` with real content
  and `-rw-rw----` — the same mode `openFileOutput` produced before the move.
- **MF919 unit** (`product:sl8541e_1h10wifi5g_32b_Natv`), already provisioned: the same five calls
  took the **skip** branch. `ChangeInFile: true`, every config file left untouched with its
  2026-09-09 timestamp, and `lastsettlement.txt` from earlier the same day intact. `getInstallApk`
  ran and returned null (`No pending APK install :: normal startup`).

Only two `ChangeInFile` lines appear in logcat for five calls, which looked like three missing
calls until the reason showed up two lines away:

```
I/chatty ( 5012): uid=10275(com.sc.mf919.dev) identical 3 lines
```

Android's logcat dedup collapsed the three repeated identical lines. **Worth remembering when
counting occurrences of an identical log line as evidence** — five calls, two printed, three
deduped. A distinct message per call site would have made the count readable; not worth changing
these for that alone, but it is the reason to prefer distinct wording in new logging.

`write2File` and `deleteFiles` still want one settlement on a terminal for their live call sites,
as noted in §49.

### Slice progress

| tranche | scope | state |
|---|---|---|
| 1 | 9 names / 12 overloads → `ByteOps` | done, §47 |
| 2 | 11 text / masking → `StringUtils` | done, §48 |
| 3 | `AppFiles` seam + 9 file helpers → `FileOps`; 5 dead stubs deleted | done, §49 |
| 4 | `ReceiptText`, install queue, `PublicIp`; 2 more dead deleted | **done** |
| 5 | 12 that `:core` already has — test against real inputs, then delegate | next, and last |

Tranche 5 is the only one left in the slice: `ASCIItoByte`, `ASCIItoHexString`, `DelayMili`,
`arrayCopy`, `arrayFill`, `bin2bcd`, `convertLong`, `getActualAmount`, `hex2bcd`,
`hideCardDetails`, `toByte`, `zeroPadding`. Each has an existing `:core` equivalent, so the risk
is the opposite of the earlier tranches — not "does the move break it" but **"are the two
implementations actually the same"**. A test against real inputs goes in before each delegation,
not after.

What stays per app after that: `getIPAddress`, `playSound`, `checkBatteryStatus` (device
concerns), `makeLineText` (printer `FontFamily` + `Gravity`, per the UI-free ruling),
`checkForUpdates` and `hashData` (the dormant OTA path from §48), and the logging wrappers
`debugLogPrint` / `printLog` / `printErrorLog` / `isDebugMsgEnable`.


## 51. Utils slice, tranche 5: the twelve `:core` already had (2026-09-10)

The last tranche, and the only one where nothing moves. Each of these already existed in `:core`
while both apps kept a private copy alongside — so the risk is inverted: not "does moving break
it" but **"were the two implementations ever actually the same".**

Fourteen overloads now delegate:

| method | target | relationship to the app copy |
|---|---|---|
| `ASCIItoByte`, `ASCIItoHexString` | `ByteOps` | byte-identical |
| `arrayCopy`, `bin2bcd` | `ByteOps` | byte-identical |
| `hex2bcd` (byte / int / long) | `ByteOps` | byte-identical |
| `toByte` | `ByteOps` | byte-identical |
| `convertLong` | `ByteOps` | **different implementation** |
| `DelayMili` | `Util` | differs only in error logging |
| `hideCardDetails` (×2) | `Util` | different `symbolString` |
| `zeroPadding` | `AmountFormat` | while-loop vs `padStart` |
| `getActualAmount` | `AmountFormat` | reimplemented in Kotlin |

"Byte-identical" here means the two bodies match once comments and whitespace are stripped —
checked mechanically, not by eye.

### The four that were not identical

Tests went in **before** the delegation, per the sequencing agreed in §46, and all four turned out
equivalent:

- **`convertLong`** — the app called Apache `NumberUtils.toLong(value, 0)`; `:core` does try/catch
  `Long.parseLong` returning `0L`. `NumberUtils.toLong(String, long)` is documented as exactly
  that, and the test covers the cases where a difference would show: `null`, `""`, `"abc"`,
  `"12.5"`, `" 12 "` (whitespace is *not* trimmed by either), `"+12"` (accepted by both), and
  overflow at `"99999999999999999999"`. All zero except the last two. **Delegating also drops the
  Apache commons-lang import from both apps.**
- **`zeroPadding`** — a `while` loop guarded by `if (length < len)` against `padStart(len, '0')`.
  Same on the case that matters: **neither truncates**, so an over-length STAN or amount passes
  through unchanged rather than being silently cut.
- **`getActualAmount`** — the Kotlin port keeps the deliberate oddity that `"0"` returns a bare
  `"0"` and not `"0.00"`, which both receipts and the MDB price log rely on. Also pinned: `"5"` is
  five **cents**, because short values are zero-padded to three digits first.
- **`hideCardDetails`** — the app used its own `symbolString`, `:core` uses the vendor library's.
  Equivalent for every input this can receive: the guard is `length > 11`, so the repeat counts
  (`length - 10`, `length - 4`) are never negative, which is the only place the two could have
  diverged.

`DelayMili` loses one log line: the app version called `printErrorLog` before
`printStackTrace` on an interrupted sleep, `:core` only does the latter. The sleep itself is
identical. Accepted rather than papered over.

Two tests also pin traps that a reader would otherwise trip over:

- **`toByte` does not accept lowercase.** The lookup string is `"0123456789ABCDEF"`, so `'a'`
  returns **-1**, the same as `'x'`. Anything parsing lowercase hex through it silently gets -1.
- **`arrayCopy` returns the offset just past what it wrote** — that return value is the whole point
  of the wrapper, because ISO field packing chains one call into the next.

### Where the slice ended up

**134 delegations audited across both `Utils` files, 0 wrong** — `ByteOps` 72, `StringUtils` 24,
`FileOps` 22, `Util` 6, `AmountFormat` 4, `ReceiptText` 4, `PublicIp` 2.

| | start of slice | now |
|---|---|---|
| MF919 `Utils.java` | 1,868 | **1,174** |
| Pro `Utils.java` | 1,635 | **942** |

**About 37% out of one and 42% out of the other**, and what remains is either genuinely per-app or
a thin delegation. `:core` gained `ByteOps` (+12 names), `StringUtils` (+11), `FileOps` (new, 279
lines), `AppFiles` (new seam, 71), `ReceiptText` (new, 78), `PublicIp` (new, 50).

`:core` tests: **70 at the start of the day → 149, 0 failures.**

### Verified

Both apps build. MF919 relaunched clean on the MF919 unit
(`product:sl8541e_1h10wifi5g_32b_Natv`) with no crash and no unregistered-seam error.

**Not yet exercised on hardware:** these twelve sit on the ISO forming path (`arrayCopy`,
`bin2bcd`, `hex2bcd`, `ASCIItoHexString`, `zeroPadding`) and the receipt path
(`getActualAmount`, `hideCardDetails`), and a bare app launch touches none of them. **A sale is
what verifies this tranche** — the tests are strong on equivalence but the value of a live
approval here is that it proves the whole ISO message still forms byte-correctly with twelve
primitives now resolving through `:core`.

### The slice is complete

| tranche | scope | state |
|---|---|---|
| 1 | 9 names / 12 overloads → `ByteOps` | §47 |
| 2 | 11 text / masking → `StringUtils` | §48 |
| 3 | `AppFiles` seam + 9 file helpers → `FileOps`; 5 dead stubs deleted | §49 |
| 4 | `ReceiptText`, install queue, `PublicIp`; 2 more dead deleted | §50 |
| 5 | 14 overloads delegated to what `:core` already had | **done** |

What deliberately stays per app: `getIPAddress`, `playSound`, `checkBatteryStatus` (device
concerns — a device seam would be a separate decision), `makeLineText` (printer `FontFamily` +
`Gravity`, per the UI-free ruling), `checkForUpdates` and `hashData` (the dormant OTA path, §48),
and the logging wrappers `debugLogPrint` / `printLog` / `printErrorLog` / `isDebugMsgEnable`.

**Next large drifted candidate is `TmsHelper.kt`** — 1,070 lines with a 246-line diff between the
two apps.

### Tranche 5 verified on hardware

Sale over HTTP on the MF919 unit (`product:sl8541e_1h10wifi5g_32b_Natv`), card parked on the
reader:

```
{"ResponseCode":"00","ResponseDescription":"(00)Approved","TransactionAmount":"1.17",
 "TransactionSTN":"002022","TransactionRRN":"625315002022","TransactionApprovalCode":"274779",
 "TransactionBatchNo":"000691","TransactionApplicationLabel":"VISA CREDIT",
 "TransactionCardNo":"436509******2381","TransactionEntryType":"Contactless",
 "TransactionCVM":"1F0302","PosReference":"T5-SALE"}
```

The 0200 formed and the host answered 0210 with `00`. That covers what the unit tests could not:

- **field 4** `000000000117` and **field 11** `002022` -- amount conversion and `zeroPadding`,
  both now resolving through `AmountFormat`
- **`arrayCopy`, `bin2bcd`, `hex2bcd`, `ASCIItoHexString`** -- the whole message packs
  byte-correctly with these coming from `ByteOps`
- **`hideCardDetails`** -- `436509******2381`, first six and last four, now via `Util`
- **`ReceiptText.CVMAnalysis`** on `1F0302`: `charAt(1)` is `'F'`, so the default branch, "NO PIN
  REQUIRED / NO SIGNATURE REQUIRED" -- correct for a no-CVM contactless tap

Also confirmed incidentally: the `checkTransactionType` type-0 fix from earlier in this programme
now answers `{"ResponseCode":"00","ResponseDescription":"No Session Running"}` on hardware, rather
than the duplicated `ResponseCode` it used to send.

**PAN redaction held.** The full PAN `4365091500002381` appears **zero** times in
`TerminaLog.txt`, the file uploaded to TMS -- even though the app logs the raw tag-57 blob to
logcat from `CardPaymentActivity.onlineProc`. That is exactly the split the D11 work was for:
logcat carries it on a debug build, the uploaded file does not.


## 52. `CARD_HASHED` holds nine clear PAN digits -- allowed, closed (2026-09-10)

Found while checking the tranche 5 sale for PAN leaks. Raised, then **closed by Gavin the same
day: nine digits is allowed.** Recorded so it is neither re-raised nor "fixed".

```kotlin
TransData.hashedPan = cardPan.substring(0, 9)
```

Seventeen call sites across the two apps. For the test card that yields `436509150`, and it
travels as `CARD_HASHED` into the `ReceiptUpload` table, into `TerminaLog.txt`, and out to TMS.
`CARD_MASKED` in the same record is the usual `436509******2381`.

**Do not change this to a real digest or a shorter mask** without checking the TMS side -- the
field is consumed downstream, and the nine digits are by design.

Two facts worth keeping, since both would otherwise be rediscovered as bugs:

1. **Hashing was once intended here and never wired.** `Utils.hashCardNum` -- `hashData(input,
   "MD5")` -- existed in both apps with **zero callers**, and `hashData` itself returns the literal
   `"123"` with its digest commented out (item 48). That is why the field is named as though it
   were digested. `hashCardNum` was deleted today as dead code, which is correct on its own terms.
2. **`LogRedact` structurally cannot cover it.** The scrubber replaces the registered PAN string;
   `436509150` is a *derived substring*, so it never matches. Anyone auditing PAN redaction will
   see these nine digits in the log and should not read it as a redactor failure -- the same
   representation gap as `HelperHttp`'s body redaction versus a parsed model in item 53.

## 53. `TmsHelper`: a key-material leak in Pro, and 253 lines of drift that was mostly logging (2026-09-10)

`TmsHelper` was the largest remaining drifted file — 1,208 lines in MF919, 1,213 in Pro, 253
differing lines. It is **not** a move-to-`:core` candidate: it is per-app orchestration over
per-app DB models, which stay per app by ruling. So the work was convergence and defect transfer
in both directions, plus lifting out what is genuinely shared.

### Measuring it properly changed what the job was

A file-level diff of 253 lines says nothing about what can move. Comparing function by function,
then again with every logging statement stripped, gave the number that mattered:

| | functions | lines |
|---|---|---|
| identical | 4 | 55 |
| differ **only** by the logging convention | 10 | ~700 |
| real divergence | 3 | **34 diff lines** |
| one side only | 2 (`foodLinkVerificationRequest`, `paperRollRequest`) | 55 |

So ~93% of the "drift" was one app having a newer logging convention. MF919 carries
`[START]`/`[END]` boundary lines with `RESP_CODE`/`RESP_DESC`, `oneLine()` on response bodies, and
exceptions routed to `TerminaLogException`; Pro carried the older `"TMS Helper - <thing>"` headers,
raw `toString()`, and a single unconditional `logToFile(TerminaLog)`.

**Two of my measurement passes were wrong before this one was right.** The first brace-matched from
the signature line, which runs away on Kotlin expression-bodied functions — `private fun
oneLine(v: String): String =` has no brace, so it swallowed the next 48 lines and reported four
functions as one-sided when all four existed in both. The second stripped only the *first* line of
a log call, leaving continuations like `"RESP_DESC=${apiResp.RESP_DESC}")` counted as real
divergence. The third splits at signature boundaries and balances parens from each `log.` call.
**Worth stating because the first two answers were plausible and would have sent this work in the
wrong direction.**

### The finding: Pro wrote the terminal's key set into a log that is uploaded to TMS

```kotlin
log.appendLine(className, "InjectionKeyHandler Response -> ", injectionKeyRes.toString())
```

`InjectionKeyResponseModel` and everything under it are Kotlin **data classes**, so `toString()`
prints every property:

- top level: `TMK`, `TAK`, `TMKId`
- `KEY.BSN_KEY[]`: `TLE`, `MEK`, `PIN`, `KSN`, `PIN_KSN`
- `KEY.BSN_CARDZONE_KEY`: `TMK_Key_Left`, `TMK_Key_Right`
- `KEY.GOBIZ_KEY` / `PAYDEE_KEY` / `PAYEX_GOBIZ_KEY` / `FINEXUS_KEY`: `TMK`, `TAK`, `TMKId`

That went to `TerminaLog.txt` — the file `uploadAllTerminalLog` walks and ships to TMS. Pro's
exception path logged `ex.toString()`, and `InjectionKeyHandler` throws `IOException(resp)`
carrying the raw response body, so **the failure path leaked the same material**.

MF919 had already closed this. Ported to Pro verbatim: the success line now logs
`SEQ_NO / RESP_CODE / RESP_DESC / TMKId / MC_VER / acq` plus the literal `[key material omitted]`,
and the exception line logs the exception class and `extractRespCode(ex.message)` with
`[response body omitted]`. `extractRespCode` came across with it.

**Verified live on MF919**, which runs the identical code path:

```
InjectionKeyHandler Response -> SEQ_NO=000158 RESP_CODE=0000 RESP_DESC=SUCCESS
                                TMKId=null MC_VER=20260910113447 acq=BSN_CARDZONE
                                [key material omitted]
TMS InjectionKey [END] :: RESP_CODE=0000 acq=BSN_CARDZONE
```

A real BSN_CARDZONE key injection, nothing sensitive in the log. **Pro itself still wants the same
check on its own terminal** — the code is identical and it builds, but it has not been run.

I audited every other response-logging site for the same class of problem: `TerminalPINResponseModel`
carries only `SEQ_NO / RESP_CODE / RESP_DESC / RESULT` (no PIN), and neither `TerminalConfig` nor
`MerchantConfig` carries key material. **`getInjectionKey` was the only one.**

### Going the other way: MF919 gained Pro's stale-staging sweep

`createTemporaryFile` writes `<name>_COPY.txt` into the same `Logs` directory that
`uploadAllTerminalLog` iterates with `walkTopDown()`, and deletes it only after a successful
upload. A process death mid-upload orphans it — and on the next run
`EnumLogFileName.valueOf("TerminaLog_COPY")` throws, so the walk classifies the orphan as a
*backup* file and uploads it. Pro already swept these on entry; MF919 did not. Ported.

Still open on both, and not fixed here: the walk creates `_COPY` files **into the directory it is
walking**, so a freshly created copy can be visited later in the same walk. The sweep only handles
the cross-run orphan.

### Lifted into `:core`

| what | new home | note |
|---|---|---|
| `checkIsConnectedWifi` | `helpers/HelperNetwork.isConnectedWifi` | ~200 call sites, so each app keeps a delegating `TmsHelper.checkIsConnectedWifi` |
| `generateEncodedPIN` + `generateRandomDigits` | `crypto/TerminalPin` | key passed in as a parameter — each app resolves it through its own app-local `Helper` |
| `createTemporaryFile` | `utils/FileOps` | takes explicit `File` args, needed no seam |
| `oneLine` (MF919's private copy) | already existed as `helpers/HelperText.oneLine` | it was a **third** copy |

`HelperText.oneLine` returns `"<empty>"` where MF919's private copy returned `""`. Kept the
`:core` behaviour: an empty response line now says so rather than looking like a truncated log.

`TerminalPin` splits `buildPaddedPin` out from the encryption so the layout can be tested. The
padding is
`%02d(frontLen) %02d(rearLen) <frontLen digits> <pin> <rearLen digits>`, and the **server strips it
using those two prefixes**, so it is a contract, not an implementation detail. Six tests pin it,
including the roughly-one-in-a-hundred case where both random lengths are 0 and the prefixes must
still be emitted. `encodePin` itself is not unit-tested — it reaches `android.util.Base64`, which
`returnDefaultValues` stubs, so encrypting would prove nothing.

While there: `HelperCommon.generateEncodedPIN` is a **fourth** copy of the same routine, with
**zero callers**, calling its own `AESencryptV2`. I checked whether that was a crypto mismatch
waiting to be triggered — it is not: `AESencryptV2` and `crypto.Encryption.AESencrypt` are the same
cipher, key derivation and Base64 flags. Dead duplicate, not a bug. Left alone as out of scope for
this file; `HelperCommon` is its own job.

### What must NOT converge, and why

Three functions carry real divergence, all of it downstream of per-app DB models:

- **`getMerchantConfiguration`** (14 lines) — Pro stores `SUPPORT` (serialised to JSON) and
  `IS_FOODLINK`. FoodLink is Pro-only, as are `foodLinkVerificationRequest` and
  `paperRollRequest`.
- **`getTerminalConfiguration`** (8 lines) — Pro stores `RECEIPT_MERCHANT_INFO_SIZE` and
  `RECEIPT_TXN_INFO_SIZE`. Worth knowing: **the TMS response model in `:core` already carries both
  fields and TMS already sends them to MF919** — today's terminal-config response on the MF919 unit
  shows `RECEIPT_MERCHANT_INFO_SIZE=null, RECEIPT_TXN_INFO_SIZE=null`. MF919 has nowhere to put
  them, so it drops them. That is a per-app schema gap, not a protocol one.
- **`checkSettlementSummary`** (12 lines) — MF919's tag list has 7 entries including
  `refundTxnTotal`/`refundTxnCount`; Pro's has 5. MF919 also de-duplicates here via
  `SettlementSummaryRepo.checkDuplicate`/`deleteDuplicate`. I first read that as "Pro has the
  duplicate bug" — **it does not**: Pro has `checkDuplicateData` and calls it from
  `SettleOptionFragment`, i.e. the same concern handled at a different point in the flow. Two
  designs, both present.

### One divergence that is not a model difference, and probably should be settled

`FORCE_LOCK_HOME == 1` does different things on the two fleets:

- **Pro** → `MfHelper.lockStatusBarAndNavigation(true)`, which sets `DISABLE_HOME` **and**
  `DISABLE_STATUS_BAR` through `DeviceHelper.getDeviceService().setProperties()`.
- **MF919** → `HelperCommon.bottomActionBarEvent(mContext, "1")`, a `com.morefun.homekey`
  broadcast to `com.morefun.MFFramework`.

So a terminal TMS has put in "locked" mode still lets the operator pull down the status bar on
MF919 but not on Pro — and MF919's version depends on a vendor launcher being installed and
listening, with no way to know whether it took. Pro's uses the documented device service. **Not
changed** — which lockdown is intended is a product call, not a refactoring one. **Open item.**

### Verified

`:core` 149 → **155 tests, 0 failures**. Both apps build. Brace balance checked on both files
after the copies.

On the MF919 unit, the whole TMS sequence ran with the new convention:

```
TMS Ping [START] ... TMS Ping [END] :: system datetime set to 20260910154549
TMS DeviceInfo [START] ... [END] :: RESP_CODE=0000 RESP_DESC=SUCCESS
TMS TerminalConfig [START] ... [END] :: RESP_CODE=0000 RESP_DESC=SUCCESS MC_VER=20260910113447
TMS MerchantConfig [START] ... [END] :: RESP_CODE=0000
TMS InjectionKey [START] ... [END] :: RESP_CODE=0000 acq=BSN_CARDZONE
TMS WriteLog [START] :: action={"Activity":"SignOn","RespCode":"3030"} ... [END] :: RESP_CODE=0000
```

**Not yet exercised:** `uploadAllTerminalLog` (its scheduler had not fired, so the new sweep and
the `HelperNetwork` gate are read-verified only), `TerminalPin.encodePin` on a real PIN check, and
**anything on Pro** — Pro was not the connected terminal for this work.

### Where the file stands

| | before | after |
|---|---|---|
| MF919 `TmsHelper.kt` | 1,208 | 1,169 |
| Pro `TmsHelper.kt` | 1,213 | 1,224 |
| differing functions | 16 | **3** |
| real diff lines | ~253 | **34** |

Pro grew because it took on MF919's richer logging. The number that matters is the last row: what
is left is three functions whose divergence is a per-app model, and it is now visible instead of
buried in 253 lines of log-format noise.

### Pro verified on hardware — and a correction to the scope above

Pro was not installed on the bench unit (`product:vnd_tb8766p1_bsp_1g`), so the fixed build went
on fresh. A fresh install parks on Android's battery-optimization prompt before any app code that
matters runs; once allowed, the full bootstrap ran, and TMS asked for a key:

```
DeviceInfoHandler Response -> DeviceInfoResp(SEQ_NO=000001, RESP_CODE=0000,
    TASK_NAME=[MerchantConfigUpdate, TerminalConfigUpdate, InjectKeyUpdate, ThirdPartyAppUpdate])
TMS InjectionKey [START]
InjectionKeyHandler Response -> SEQ_NO=000004 RESP_CODE=0000 RESP_DESC=SUCCESS
                                TMKId=null MC_VER=20260910113447 acq=BSN_CARDZONE
                                [key material omitted]
TMS InjectionKey [END] :: RESP_CODE=0000 acq=BSN_CARDZONE
```

Proof rather than eyeballing — the pre-fix code would have left a Kotlin data-class `toString()`
fingerprint in the log, so those are what to count in `TerminaLog.txt`:

| searched for | count |
|---|---|
| `InjectionKeyResponseModel(` | **0** |
| `InjectionKeyModelKeyModel(` | **0** |
| `BsnCardZoneInjectionKeyModel(` | **0** |
| `key material omitted` | 2 |

And every occurrence of a key field in the whole log, values included:

```
TMK_Key_Left":  "<redacted:32 chars>"
TMK_Key_Right": "<redacted:32 chars>"
```

**The correction.** Those two lines come from `helpers.HelperHttp`, which logs every TMS response
body — and it already redacts key values at that choke point:

```kotlin
private val KEY_FIELD = Regex(
    """("(?:[A-Z_]*(?:KEY|TMK|TPK|TAK|TWK|WAK|WEK|MEK|TLE|IPEK|PIN)[A-Z_]*)"\s*:\s*)"([^"]*)"""",
    RegexOption.IGNORE_CASE)
```

So the raw-body path was **never** leaking, on either app. The exposure was narrower than I wrote
above: `injectionKeyRes.toString()` stringifies the **parsed model**, not the JSON body, so it
went around `HelperHttp`'s redaction entirely. That is the hole, it was real, and it is now closed
on both fleets — but the claim "Pro wrote the terminal's key set into the log" should read "Pro had
one log line that bypassed the redaction every other TMS log line goes through".

Worth keeping in mind as a pattern: **a choke point only protects the representation it sees.**
Redacting the wire format does nothing for a parsed object, a database row, or a receipt — the same
lesson as `CARD_HASHED` in §52, where `LogRedact` could not match a derived substring.

`KSN` is deliberately absent from that regex and that is correct — a DUKPT key serial number is a
counter transmitted in the clear in field 62, not secret material. `PIN_KSN` still matches on
`PIN`.

Pro's `[START]`/`[END]` convention, `oneLine()` and `extractRespCode` are all confirmed working on
hardware by the sequence above. Still unexercised on Pro: `uploadAllTerminalLog` (scheduler had
not fired) and `TerminalPin.encodePin` (no PIN check performed).

### The `_COPY` sweep verified on Pro, with a planted orphan

The scheduler route could not be forced — `cmd jobscheduler run -f` dispatches WorkManager's
`SystemJobService` but no work was due, so nothing ran. The Settings route is better anyway: it
calls `uploadAllTerminalLog(log, ctx, isUploadAll = true)`, and **only that flag reaches the
`_COPY` staging path** (with `false`, enum-named log files are skipped before
`createTemporaryFile` is ever called). Settings needed no password on this build.

An orphan was planted first, since a sweep with nothing to sweep proves nothing:

```
run-as com.sc.mf919pro.dev sh -c 'echo TEST-ORPHAN-STAGING-COPY-DO-NOT-KEEP > .../Logs/TerminaLog_COPY.txt'
```

Result:

```
TMS UploadLog [START] :: uploadAll=true
Removing stale staging copy: TerminaLog_COPY.txt
Uploading Log -> TerminaLog.txt          Upload Log Success
Uploading Log -> session.marker           Upload Log Success
Uploading Log -> TerminaDbException.txt   Upload Log Success
TMS UploadLog [END] :: all files uploaded
```

The orphan was **removed before the walk and never uploaded** — three files went to TMS, all
legitimate — and it is gone from the directory afterwards. That is the exact failure the sweep
exists to prevent, reproduced and prevented.

One residual worry from §53 did **not** materialise here: `TerminaLog.txt` is staged as
`TerminaLog_COPY.txt` *into the directory being walked*, but the walk never visited the new copy,
so `walkTopDown()` effectively worked off a snapshot of the listing. Implementation-dependent
rather than guaranteed, so it stays a note, not a closed item.

`TerminaLogException.txt` does not exist on this Pro terminal — no TMS call failed — so Pro's new
exception sink is still unexercised.

### Non-issue, recorded so it is not re-flagged: the shared `DEV_SN`

Both terminals report `DEV_SN=98213199990004` to TMS, and on the Pro unit that matches neither
`ro.serialno` (93250606780014) nor `ro.boot.serialno` (0123456789ABCDEF, the adb serial). I raised
it because TMS keys terminal config, sequence numbers and injection keys by `DEV_SN`, so two units
sharing one would interleave their records.

**It is a hardcoded serial used for testing** -- confirmed by Gavin, 2026-09-10. Deliberate, not a
provisioning fault. Written down here because the same discrepancy will look alarming to the next
person who greps a terminal log, and because it means **`DEV_SN` cannot be used to tell the two
bench terminals apart** -- identify them by `product` (see the terminal-function-test skill), never
by the serial the app reports.

## 54. `FORCE_LOCK_HOME`: MF919 moved onto Pro's kiosk mechanism (2026-09-10)

The two fleets locked the terminal differently, and only one of them actually locked the status
bar:

| | mechanism | scope | failure visible? |
|---|---|---|---|
| MF919 (old) | `Intent("com.morefun.homekey")` broadcast to `com.morefun.MFFramework` | home key only | **no** -- `sendBroadcast` succeeds whether or not anything listens |
| Pro | `DISABLE_HOME` + `DISABLE_STATUS_BAR` via `DeviceHelper.getDeviceService().setProperties()` | home key **and** status bar | return code discarded, `RemoteException` swallowed into a bare `// TODO` |

So a terminal TMS had put in locked mode still let the operator pull down the status bar on MF919
-- and if the vendor launcher was not installed or not listening, MF919's lock did nothing at all
and nothing recorded that.

**The port was smaller than it looked.** MF919's `MfHelper` *already had*
`lockStatusBarAndNavigation`, byte-identical to Pro's. It simply was not being called. The work
was rewiring call sites, not writing an implementation.

### What changed

- **Both apps**: `lockStatusBarAndNavigation` now checks the `setProperties` return code and logs
  the outcome. Changed in **both** so the files stay converged -- fixing only MF919 would reopen
  the drift item 53 had just closed. `ret == 0` is success, following
  `DeviceHelper.enableAutoStartOnBoot`, which was already doing this in the same codebase.
- **MF919: 28 call sites switched** across 12 files -- `AboutActivity`, `AttendActivity`,
  `AttendActivityOxpay`, `AttendDenominationActivity`, `EmvActivity`, `MainActivity`,
  `QrScanActivity`, `TerminalConfigActivity`, `UnattendActivity`, `BaseActivity`, `TmsHelper`,
  `TransactionTransmitter`. This is MF919's kiosk mechanism throughout, not just the TMS branch.
- **Deleted** `HelperCommon.bottomActionBarEvent` (Pro never had it) and `BaseActivity`'s private
  `startProgressDialog`/`stopProgressDialog` -- **dead code**: the private overload had no callers,
  only its own delegation to the public `ActivityBase` one, so the two lock calls inside it never
  ran. They were the only reason `BaseActivity` was in the switch list at all.

`disableKey` (`com.morefun.disablekey`) is untouched: still live at three sites in
`AboutActivity`, and Pro has no equivalent.

The switch could not be a blind textual swap -- the old call takes `(Context, "1"/"0")`, the new
one takes `(Boolean)`. The script handled each known form explicitly and **reported** anything it
did not recognise rather than guessing; it flagged two sites in `TransactionTransmitter.java`
where the argument was `getApplicationContext()` (parentheses defeated the pattern) and those were
done by hand.

### Verified on the MF919 terminal

| branch | keys | result |
|---|---|---|
| unlock | `ENABLE_HOME` + `ENABLE_STATUS_BAR` | `Kiosk unlock :: home + status bar applied`, `ret == 0` |
| lock | `DISABLE_HOME` + `DISABLE_STATUS_BAR` | `Kiosk lock :: home + status bar applied`, `ret == 0` |

The lock branch needed `FORCE_LOCK_HOME = 1`, reached through **More -> ADMIN -> Terminal
Configuration -> ENABLE DOCKER MODE** (that label is `@id/forceLockHome`). Reverted to `OFF`
afterwards.

Worth knowing, because it cost a scroll hunt: **that screen has no Save button.**
`customOnBackPress()` calls `save()` on a background thread, so backing out of the screen *is* the
commit -- a user who flips a switch and presses back has already saved.

### A retraction

I first reported that the lock worked behaviourally, on the grounds that a top-edge swipe did not
open the notification shade. **That was worthless evidence.** The A/B caught it: the shade did not
open when *unlocked* either, because this ROM names the window `StatusBar` and I had grepped for
`NotificationShade`, the newer AOSP name that never appears here. The check returned `0` in both
states.

What is verified is the **return code on both key pairs** -- the ROM accepting the property rather
than rejecting it, which was the specific risk (a ROM taking one pair and not the other). That is
also strictly more than MF919 could report before today, when the mechanism was a broadcast with
no result at all.

The right instrument for a behavioural check, for whoever wants it later, is `dumpsys statusbar`
-> `mDisabled1` / `mDisabled2` (both `0x0` when unlocked), **not** the window list.

Both apps build; MF919 relaunched clean on the terminal with `ret == 0`.

## 55. The legacy DB layer deleted from both apps -- 4,930 lines (2026-09-10)

The census said `DataAdapter.java` and `Database.java` were the two largest "drifted" files in the
programme, at 2,397 and 2,285 diff lines. **Neither was drifted.** Pro had completed the
`DbHandler` + repo migration and cut both files down to a 136-line residue; MF919 still carried the
whole original. The diff was measuring one side of a finished migration against the other.

| file | MF919 | Pro |
|---|---|---|
| `java/activity/DataAdapter.java` | 2,486 | 99 |
| `java/activity/Database.java` | 2,310 | 35 |

### The residue was dead too

Pro's remaining 136 lines carried a file comment saying they served "only the SystemTrace database
used by Utils (invoice/stan counters)". That comment was **out of date**: the `CounterSign` classes
in `Utils` that used `Database.DatabaseAccess` sit inside `/* ... */` blocks in **both** apps, so
the residue had no live caller on either side.

I did not trust my own arithmetic on that -- counting `/*` against `*/` is fooled by string
literals -- so the four files were backed up to the scratchpad and deleted outright, letting the
compiler answer the question. **Both apps build.** That is the whole proof: 4,930 lines, gone.

### Deleted with it

`PasswordDb.java` (81 lines) had **zero references anywhere** -- not one, including in its own app.
Pro deleted it long ago.

`ViewTableContain.java` (240 lines), a raw table viewer, was reachable, so its whole reach chain
went:

```
activity_vendor.xml tile (android:onClick="start2EditTable")
  -> VendorOption.start2EditTable(View)
    -> VendorOption.dbSelectorDialog(String[])
      -> ViewTableContain
```

**The layout tile had to go with it.** `android:onClick` is resolved reflectively at tap time, so
leaving the button behind would have converted a compile-time deletion into a runtime crash for
whoever next opened the vendor menu -- the kind of breakage a green build does not catch.

### Verified on the MF919 terminal

App launches, full TMS bootstrap runs (DeviceInfo, Ping, InjectionKey, WriteLog), no
`ClassNotFoundException` or `NoClassDefFoundError`, and a sale approved:

```
ResponseCode 00 (00)Approved   amount 1.23
STAN 002034   Invoice 002034   Batch 000693   appr 440028
```

STAN and invoice both advanced (002031 -> 002034 across the session), which is the point worth
checking: those counters were the *stated* reason the legacy layer was being kept, and they work
without it because they come from the repo layer now.

### Where the two apps stand

| | files | lines |
|---|---|---|
| `app-mf919` | 206 | 60,166 |
| `app-mf919pro` | 174 | 43,332 |

Shared file paths: **81** (20 identical, 61 drifted), down from 83.

Of the 20 identical files, most are excluded by standing rulings rather than pending work: 11 are
`DbModel*` classes (models stay per app), and `CrashRecoveryActivity` is an Activity (`:core` stays
UI-free). What is genuinely movable there is small -- the three `datastore/` files, ~34 lines.

**Next candidates, now that the false positives are cleared:**

| file | MF919 / Pro | note |
|---|---|---|
| `kotlin/helper_common/HTTPServer.kt` | 1,838 / 2,333 | Pro carries the dual old+new integration MF919 lacks; a real feature gap, not drift |
| `kotlin/activity/MainActivity.kt` | 721 / 682 | 1,313 diff lines -- more than either file, i.e. near-total restructuring. UI, stays per app |
| `kotlin/helper_common/iso/IsoActivity.kt` | 2,999 / 2,847 | the big ISO orchestrator, already heavily worked |
| `kotlin/helper_common/WebSocketClient.kt` | 310 / 307 | near-identical size, 277 diff lines -- worth a look, non-UI, transport-shaped |
| `java/device/DeviceHelper.java` | 686 / 576 | vendor device layer, per app by nature |

`WebSocketClient.kt` is the most interesting of these: same size on both sides, non-UI, and a
transport rather than a screen -- so unlike the others it is not excluded by a ruling before the
work starts.

## 56. `WebSocketClient.kt`: 277 diff lines to zero (2026-09-10)

The census flagged this as the best remaining candidate -- 310 vs 307 lines but 277 diff lines,
non-UI, a transport rather than a screen. The diff turned out to be mostly noise hiding one real
capability gap.

| | |
|---|---|
| Pro's dead `/*class WebSocketClient ...*/` draft under `//TODO BRYAN` | **124 lines** |
| import churn (Pro used a `kotlinx.coroutines.*` wildcard, plus two no-op same-package imports) | ~15 |
| real code difference | ~113 |

Comments stripped, MF919 had **254** code lines and Pro **151** -- essentially all of it MF919
having things Pro lacked.

### What Pro was missing

1. **Connection logging with failure throttling.** `logWs()` writes connect / disconnect / error
   to `HelperLog`, errors to `TerminaLogException`; `logConnectFailure()` writes only the first
   failure of a run and then every 20th. Pro had none of it, so a Pro terminal whose WS server was
   unreachable retried every 3 s **silently** -- nothing in the uploaded log said so. The
   first-then-every-20th shape matters: a block per failure is a ~20-lines-a-minute disk loop.
2. **JSON parse and command dispatch**, including unwrapping double-encoded JSON (a string
   primitive that contains JSON -- a real thing some servers send), then dispatching
   `UpdatePrice -> DenominationListRepo.truncateTable`. Pro did `_messageFlow.tryEmit(message)`
   and nothing else.

Pro's own `MainActivity` already carried a note about this: *"MF919 does this in the same place
(MainActivity fresh load) and on the websocket UpdatePrice command; the port dropped both."* Pro
had restored the fresh-load half and not the websocket half. Now both.

### Duplicate suppression: fixed rather than copied

MF919 dropped duplicate messages inside a 3 s window. Three things were wrong with it, and all
three are now fixed **in both apps**:

- **The log line said nothing.** It printed
  `println("Received :: duplicate ${Utils.DateTimeFormat(TransData.transDateAsci)}")` -- the
  *transaction date*, which has nothing to do with the message. A reader could not tell what was
  dropped, or that anything had been. It now logs through `logWs` with a bounded form of the
  actual message, so the drop lands in `TerminaLog` where it can be correlated.
- **The state was a data race.** A plain `var temp`, written on the socket thread and cleared from
  an IO coroutine. Now `@Volatile`, and `private` (nothing outside the file read it).
- **PONG was deduped.** The keep-alive was checked *after* the dedup, so it entered the dedup
  state. Harmless today at 45 s intervals, but interval-dependent for no reason. PONG is now
  checked first: never deduped, never emitted.

A silent drop is indistinguishable from a message that never arrived when you are reading a
terminal log after the fact. That is the whole argument for logging it.

### `EnumWebsocket` consolidated

It already existed in `:core` (`enums/EnumWebsocket.kt`) and Pro used it; MF919 still had an
app-local copy at `com.sc.mf919.kotlin.data_enum`. Four imports repointed, the duplicate deleted.
`TerminalDMDispense` was in MF919's copy but not core's, so it was added rather than dropped -- it
documents a command the server can send. Its commented-out handler went, because it called
`HelperCommon.manualDispenseToken`, which exists nowhere in either app.

> **Corrected 2026-09-11.** I described that handler as "rot, not planned work". The *action* was
> right -- a body calling a method that does not exist cannot stay -- but the reasoning was wrong:
> production sends `TerminalDMDispense`, addressed to the terminal, with a real payload. It is an
> unimplemented feature, not a dead idea. See item 57.

### Result

**The two files are now byte-identical apart from the package name** -- 326 lines each, **0 diff
lines**, down from 277.

Getting there needed one last thing worth recording: MF919's file ended **without a trailing
newline** and Pro's with one. My first check rstripped both ends and reported 0 while the census
still counted the file as drifted. The census was right and my check was too forgiving. MF919's
copy now ends with a newline, and `WebSocketClient.kt` has moved from the drifted bucket to the
identical one:

```
shared file paths: 81   (identical 21, drifted 60)
total duplicated lines in identical files: 541 -> 867
```

That is the point of driving a file to zero rather than to "close enough": future drift now shows
as a one-line diff instead of hiding among reordered imports -- which is exactly how a 124-line
dead draft sat in this file unnoticed.

### Verified

Both apps build; `:core` 155 tests, 0 failures. MF919 relaunched clean on the terminal, no crash.

**The new logging is not exercised yet.** This terminal's TMS config has `WEBSOCKET=0`, so
`TmsHelper` calls `disconnect()` and the new line correctly stays silent -- it is guarded on
`client != null`, and there was nothing to tear down. Connect and failure logging need
`WEBSOCKET=1` from TMS. Pointing it at an unreachable host would also exercise the throttle, which
is the half most worth seeing.

`WebSocketMessageListener` (~26 lines) is identical in both and depends only on
`CoroutineScope`/`Job` plus the app-local `WebSocketClientSingleton`. A `:core` move would need the
flow passed in rather than reached for -- small, and now that the whole file is identical the
singleton itself is a candidate too, behind seams for `Helper`, `ServiceHolder` and
`DenominationListRepo`.

## 57. What live WebSocket traffic showed, and one wrong call of mine (2026-09-11)

With `WEBSOCKET=1` and a live server, real pushes arrived. The dispatch ported to Pro in item 56
works:

```
Received :: {"Checksum":"134e…","Command":"UpdatePrice","RequestRef":"1789094540413",
             "TerminalSN":"98211000000815"}
jsonCommand :: UpdatePrice        <- took the handled branch
```

`TerminalDMDispense` took the `else`, which is what proves the `when` actually discriminates
rather than everything falling through.

### Finding 1: `TerminalDMDispense` is live production traffic with no handler

```
Received :: {"Command":"TerminalDMDispense",
             "Data":"{…\"DeviceSerialNo\":\"98211000000815\",\"Description\":\"1\",\"Amount\":1.00,…}",
             "TerminalSN":"98211000000815"}
```

A real dispense instruction -- amount, package GUIDs -- **addressed to this terminal** by both
`TerminalSN` and the payload's own `DeviceSerialNo`. Neither app has a handler, so it is dropped.

**This corrects item 56.** I removed MF919's commented-out handler and called it "rot, not planned
work". Removing it was right -- it called `HelperCommon.manualDispenseToken`, which exists in
neither app, so it could not have stayed. But it is not rot: the server sends this command today.
It is an unimplemented feature, and the enum comment now says so instead of implying dormancy.

**Before anyone implements it:** nothing in `onMessage` checks that a message is addressed to this
terminal. That is fine for `UpdatePrice`, whose worst case is a spurious denomination re-fetch. It
is not fine for a command that dispenses goods. The `TerminalSN` check belongs in place *first*.

### Finding 2: the duplicate suppression can almost never fire

Every push carries a unique `RequestRef` and `Checksum`, and the suppression added in item 56 keys
on the **whole message string**. Two functionally identical price updates are therefore never
byte-identical, so only a literal retransmission of the same frame is ever caught.

Not wrong, and worth keeping -- but much narrower than it looks. Keying on `RequestRef` would be
the real version, if the protocol guarantees it is unique per logical request. Left as it is
rather than guessed at.

### Fixed here

The `else` branch printed `"Invalid Command"` to logcat only. So an unhandled **live** command left
no trace in `TerminaLog` -- the log that is actually uploaded -- which is precisely why nobody
learned from the field that the server sends something the terminal drops. It now goes through
`logWs`, and the wording no longer calls the server's command invalid when the gap is on our side:

```kotlin
else -> {
    logWs("Unhandled command :: $jsonCommand")
}
```

Both apps, identically; the two files remain byte-identical apart from the package name.

### A retraction: the SN mismatch I reported was my error, not the terminal's

I first reported that the terminal was acting on pushes addressed to a *different* terminal --
connected as `sn=98213199990006` (staging) while messages named `TerminalSN 98211000000815` -- and
proposed an addressing bug.

**Wrong.** I read the `Connect requested` line out of `TerminaLog.txt`, which **persists across
reinstalls**, and paired a previous run's staging connection with the current run's messages. The
live logcat buffer for the running process shows:

```
connected as              wss://terminalws.share-commerce.com?sn=98211000000815
TerminalSN in the pushes  98211000000815
DEV_SN sent to TMS        98211000000815
DeviceSerialNo in payload 98211000000815
```

Every identifier matches; routing is correct. (`98211000000816` is the adb/USB serial, a different
identifier, and a `00229821100000` in my first grep was noise from the pattern sliding across
`"`-escaped JSON.)

Gavin caught it -- "i think you are getting wrong log". **The rule this earns: never pair a
timestamp-free line from a persisted app log with events from the live buffer.** Take both from the
same source, or check the process id on each. The same trap is available in every log this
programme reads.

Worth noting separately: the package running during this test was `com.sc.mf919` -- the **release**
variant against production TMS -- not the `.uat` stag build installed earlier. So the merged code
has now also been exercised as a release build against prod.

## 58. `UploadTMS`: a second receipt pipeline that could not run (2026-09-14)

The census ranked this 144 diff lines on 169 vs 25 raw lines -- the `DataAdapter` shape again, one
side of a finished migration measured against the other. It turned out better than that: **MF919
had already migrated the entry point and left the old machinery standing behind it.**

```java
public void addReceipt(String body) {
    //receipt.add(body);
    //saveReceipt();
    ReceiptUploadRepo.Companion.insertToDb(ServiceHolder.Companion.getContext(), body);
    AppServices.Companion.receiptUploadToTms(ServiceHolder.Companion.getContext());
}
```

Byte-identical to Pro's. And `addReceipt` is the **only** method any caller uses -- 12 call sites
across both apps, all of one shape:

```
8 x UploadTMS.getInstance().addReceipt(body)
4 x UploadTMS.getInstance().addReceipt(jsonObject.toString())
```

So everything the commented-out lines used to reach was unreachable: `uploadReceipt()` is public
with **zero callers**, and `initConfig`, `saveReceipt`, `uploadReceipt_ext`, the `receipt` list and
the `receipt.txt` file are only reachable from it or the constructor. A whole second
receipt-upload pipeline -- file-backed queue, HMAC checksum, its own `HttpConnection` POST and a
busy-wait on `connection.isActive()` -- that no longer had a way to start.

Both files rewritten identically, **169 -> 33 lines** (Pro 25 -> 33; it gains only the class
comment). `UploadTMS.java` moves into the identical bucket: **22 identical / 59 drifted**,
duplicated lines in identical files 913 -> 946.

### Worth knowing before anyone revives it

- The dead `uploadReceipt_ext` contained `if (!refId.equals(refId))` -- a variable compared with
  itself, so the REF_ID check it was meant to perform never ran. Deleted with the rest; recorded
  because it is the kind of thing that looks deliberate in a diff.
- It was also one of two callers of `Utils.getPublicIP()`, the no-timeout busy-wait from item 50.
  **One caller left per app** (`Tms.java`), which narrows that open item.
- The old path wrote `receipt.txt` into internal storage. Nothing reads it now, so any file left on
  a terminal from before the repo migration is orphaned -- harmless, but it will never be drained
  or deleted by the app.

### Verified

Both apps build; `:core` 155 tests, 0 failures. Callers untouched -- the public surface is
unchanged, which is why 12 call sites needed no edit.

**Verified on hardware** (Pro unit, `product:vnd_tb8766p1_bsp_1g`, debug build; install identity
confirmed by `pm path` + md5 against the local APK). Sale over ECR, then the receipt followed the
surviving path end to end:

```
00 (00)Approved  1.27  STAN 002045  Batch 000695  appr 571237  PosReference UPLOADTMS-TEST

10:15:06.170  DbModelReceiptUpload(SEQ_NO=000005 ... IsSend=false)   <- addReceipt -> insertToDb
10:15:06.553  Worker SUCCESS ... TmsReceiptUploadScheduler            <- AppServices kicked it
10:15:07.920  value: DbModelReceiptUpload(... RESP_CODE=00, RRN=625710002045, APPR_CODE=571237)
10:15:08.039  ----------> (POST) .../tms/Receipt/ReceiptUpload
10:15:08.219  <---------- (Response) 186ms
10:15:08.230  Api Result: ReceiptUploadResponseModel(SEQ_NO=000005, RESP_CODE=0000)
```

The 110 deleted lines were genuinely a second, unreachable pipeline -- removing them disturbed
nothing.

Worth recording because it explains the two inserts in the log and why the file-backed queue was
redundant: the row is written at `IsSend=false` with `RESP_CODE=-` **before** the host answers,
then updated with RRN, approval code and `RESP_CODE=00` before upload. A power cut mid-transaction
therefore leaves a receipt row that is still sent later. The repo path already provides the
durability the `receipt.txt` queue was built for.

## 59. `WebSocketServer` and a fourth dead PIN encoder (2026-09-14)

### A correction first, because I reported the direction backwards

When I proposed this item I said *"Pro's ECR `messageQueue` has no size bound; MF919 trims at 20"*.
**It is the reverse.** Pro has `MAX_QUEUED_MESSAGES`, `@Volatile`, `activeInstance` and the
`Job`/`isActive` lifecycle; MF919 had none of them.

The mistake: I read a `unified_diff(mf919, pro)` and treated the `+` lines as MF919's. In that
diff `+` means *present in Pro*. Same family as the stale-log error in item 57 -- a tool used
without checking which side of it I was reading. **The merge therefore runs Pro -> MF919**, and it
was MF919's queue that could grow without limit.

### What MF919 gained

`WebSocketServer.kt` replaced wholesale with Pro's (139 -> 177 lines), so the pair is now
byte-identical bar the package name. MF919 was missing:

| | why it matters |
|---|---|
| `MAX_QUEUED_MESSAGES = 20` | the ECR response queue grew forever while no POS was connected |
| `@Volatile socketConnected` | `HTTPServer.startWebSocketServer` spin-reads it from another thread; a plain `var` gives no visibility guarantee |
| `onError` clears the flag **only** for `conn == null` | MF919 cleared it on *any* per-connection error, so one client's failure made HTTPServer believe the listener had died and restart it |
| `connections` (the library's thread-safe collection) | MF919 kept its own `mutableSet`, mutated from java-websocket's callback threads unsynchronised |
| per-client `try/catch` in broadcast | one dropped client aborted delivery to every other client |
| `processingJob` + `while (isActive)` | MF919 used `while (true)` and started a fresh loop on every `onStart` |
| `isReuseAddr`, `flushQueue()` on connect, immediate flush on receive | faster restarts; a client that reconnects gets what it missed |

One change outside the file was **required**, not cosmetic: MF919's restart path called
`webSocketServer?.stop()`. Pro calls `stopServer()`, which cancels the processing coroutine. With
the ported `processingJob` in place but `stop()` still at the call site, every retry would leak a
coroutine -- the exact leak the port closes. Now `stopServer()`.

### The fourth copy of the PIN encoder

`HelperCommon.generateEncodedPIN` deleted from both apps, with its private `generateRandomDigits`.
**Zero callers in either app** -- every live call goes to `TmsHelper.generateEncodedPIN`, which
delegates to `crypto.TerminalPin.encodePin` (item 51). That makes this the fourth copy of the same
padding routine and the only one nothing called. -30 lines from each app.

`AESencryptV2` **stays** in both. It still has one live caller in MF919
(`SettingsActivity`, `eTmkId`), and although it becomes caller-less in Pro, deleting it there alone
would re-open drift in a file that is otherwise converging. It is functionally identical to
`crypto.Encryption.AESencrypt` -- same cipher, key derivation and Base64 flags, checked in item 53
-- so the tidy end state is to point `SettingsActivity` at core's copy and drop it from both. That
touches a live crypto call site, so it is a separate decision, not a side effect of this one.

### Where that leaves the census

```
shared file paths: 81   (identical 23, drifted 58)
duplicated lines in identical files: 946 -> 1,123
```

`WebSocketServer.kt` joins `WebSocketClient.kt` and `UploadTMS.java` in the identical bucket.
`HelperCommon.kt` still carries **101 real diff lines** -- the dead PIN encoder was a small part
of it.

### Verified

Both apps build; `:core` 155 tests, 0 failures; `WebSocketServer.kt` diff is **0 lines**.

Installed on the MF919 unit (`product:sl8541e_1h10wifi5g_32b_Natv`), hash-checked against the
local APK, launches clean with no crash.

With `CABLE_CONNECTION = WEBSOCKET_SERVER` set on TMS, the listener runs -- and the log wording is
Pro's `wsLog`, so the ported file is demonstrably what is executing:

```
WebSocket server DOWN :: restarting listener on 8080
Server started successfully, listening for POS connections
WebSocket server restarted :: connected=true
WebSocket server already connected :: no restart      <- @Volatile flag read across threads
```

**Approved sale over the WebSocket transport**, 3.9 s from send to response:

```
Client connected :: /127.0.0.1:35081
Message received :: {"TransactionType":1,"TransactionAmount":"1.33","PosReference":"WS-SALE"}
-> {"ResponseCode":"00","ResponseDescription":"(00)Approved","TransactionSTN":"002050",
    "TransactionApprovalCode":"579747","PosReference":"WS-SALE"}
```

That one transaction also carried item 58's receipt through the collapsed `UploadTMS`
(`SEQ_NO=000221` -> `POST .../tms/Receipt/ReceiptUpload` -> `Worker result SUCCESS`), so both of
the day's changes are verified on the same sale.

**Resilience confirmed separately:** a sale started over WS, the client dropped at 1 s
(`code=1006 remote=true`), reconnected at 50 s, and the response still reached it. A POS that
vanishes mid-transaction and comes back is still answered.

### Two paths NOT proven, stated plainly

- **The 20-message cap never fired.** It guards against more than twenty *undelivered* responses
  accumulating, which needs a stalled consumer; that could not be manufactured here.
- **`flushQueue()` on connect did not fire either.** Read the timings from the drop test: the
  response was broadcast at `12:02:01`, **eleven seconds after** the reconnect at `12:01:50`. So
  the sale concluded with the client already back, and delivery came from the immediate flush in
  `receiveResponseMessage` -- not from the connect-time flush. The operational property (answer
  survives a reconnect) holds; the specific line I was aiming at is still untested.

Worth recording from the same run: the failed card-less sale returned `ResponseCode: ""` again --
the known MF919 defect where Pro returns `SHC005` -- this time over the WS transport rather than
HTTP, which confirms it is in the response builder and not in one transport. And STAN/batch had
reset to `000001`, because a fresh `.dev` install starts its counters over; not a defect, but it
will mislead anyone reading those numbers.

## 60. `HelperCommon`: one fix attempted and REVERTED, the rest legitimately per-app (2026-09-14)

> **REVERTED 2026-09-14, same day.** The `sdkPrint` port described below was backed out. It is
> **not** in the code. Read this entry for why the change looked right and why it was wrong; the
> reasoning is the part worth keeping. The dead `generateEncodedPIN` removal (item 59) is
> unaffected and stands.
>
> Reverted by hand: git is unusable in this repository (ownership guard, out of scope to change),
> so the body was restored from text captured before the edit and verified by line count plus a
> grep confirming `PRINT_TIMEOUT_SECONDS`, `@Synchronized`, `CountDownLatch` and `TimeUnit` are all
> gone.

101 raw diff lines, but comparing function by function tells a different story: **16 of the 20
shared functions are already identical** (135 lines). Only one carried a real difference.

### `sdkPrint` -- MF919's prints could overlap

MF919's version handed the job to the printer and returned immediately. Pro's waits on a
`CountDownLatch` released by the printer's own `onPrintResult`, is `@Synchronized`, and gives up
after `PRINT_TIMEOUT_SECONDS = 30` so a printer fault cannot hang the calling thread forever.

Without it, two consecutive receipts can be dispatched while the first is still feeding the
printer, and print data is dropped. Ported.

> **Corrected 2026-09-14.** I wrote "a multi-product settlement is the obvious case". That is true
> **in Pro**, where settlement fragments are three of `sdkPrint`'s ten callers. It is **not** true
> in MF919, where `sdkPrint` has two callers -- both transaction-receipt fragments -- and
> settlement prints through `ActivityBase` instead. I carried Pro's rationale across without
> checking it applied on this side. See item 61.

**Not a copy, and this is the trap worth recording.** The two apps use the same method *name* for
different things:

| | `getPrinter()` | `getMultipleAppPrinter()` |
|---|---|---|
| MF919 | returns `MultipleAppPrinter` | does not exist |
| Pro | returns `Printer` (**a different type**) | returns `MultipleAppPrinter` |

So MF919's `getPrinter()` is Pro's `getMultipleAppPrinter()`, and Pro's `getPrinter()` is something
else entirely. Copying that line verbatim in either direction compiles or fails by luck rather
than by meaning. Substituted deliberately, and the reason is in a comment at the call site.

**Checked before shipping it, because the semantics changed:** `sdkPrint` now blocks, and Pro's own
doc says it must not be called from the UI thread. MF919's two call sites are
`FragmentReceipt.printInfo` and `FragmentReceiptQr.printInfo` -- both `suspend fun ... =
withContext(Dispatchers.Default)`, launched from `CoroutineScope(Dispatchers.Default)`. No
main-thread caller, so no ANR risk.

### What remains, and why it should stay

Real drift 101 -> **90 lines**, and essentially all of it is content that belongs to one app:

| | lines | why it stays |
|---|---|---|
| `getHomeScreenIntent` (MF919) | 12 | routes to `AttendActivity` / `AttendDenominationActivity` / `UnattendActivity` -- MF919's own screens |
| `disableKey` (MF919) | 7 | `com.morefun.disablekey` broadcast, live in `AboutActivity`; Pro has no equivalent |
| `formatTTSAmount` (Pro) | 28 | text-to-speech amount announcement, a Pro feature |
| `sdkPrint` accessor line | 1 | the naming difference above |

The remaining count is inflated by difflib aligning the shared blocks around those insertions --
the shared functions are in the same relative order in both files, so there is nothing to reorder.

**`HelperCommon` is done.** What is left is per-app by nature, not drift waiting to be merged.

### Verified

Both apps build; `:core` 155 tests, 0 failures. `AESencryptV2` still stands in both (one live
caller in MF919's `SettingsActivity`), as recorded in item 59.

**Not verified on hardware:** the new blocking `sdkPrint` has not printed a receipt. The behaviour
that changed is timing on a physical printer, so it wants a real receipt print -- ideally a
multi-product settlement, which is the case the synchronisation exists for.

## 61. Three findings from printing a settlement (2026-09-14)

### 1. The settlement worked, and re-confirmed item 49

```
00 Settled   1.33   SettlementCount=1   Batch 000695   STAN 002053   RRN 625712002053
Print Settlement Record [START] -> MultipeleAppPrinterImpl: printStr -> initPrinter -> startPrint -> [END]
FileOps: Delete Files : (lastsettlement.txt) true
FileOps: Write Files : (lastsettlement.txt)
```

The `FileOps` pair is item 49's `write2File` at its live call site, on hardware again.

### 2. The print did NOT go through the code changed in item 60

No `onPrintResult`, no `sdkPrint` timeout line. `SettlementActivity` prints via `ActivityBase`,
not `HelperCommon.sdkPrint`. **So the item 60 port is still unexercised**, and my justification for
it was imported from Pro without checking it held here -- corrected in place above.

Reaching `sdkPrint` on MF919 needs a receipt printed from `FragmentReceipt` -- the on-screen
receipt after a transaction -- not a settlement.

### 3. The real finding: MF919 has four print paths, one of them guarded

| path | overlap guard |
|---|---|
| `HelperCommon.sdkPrint` | **yes**, since item 60 |
| `ActivityBase.kt:170` | no |
| `ActivityBase.java:271` | no |
| `PrintsActivity.kt:221` | no |

Pro consolidated onto `sdkPrint` -- ten callers including all three settlement fragments. MF919
never did, so the fix landed on two receipt fragments while the settlement receipt, the one most
likely to print several jobs back to back, still goes out unguarded.

Consolidating MF919's paths onto `sdkPrint` is the follow-up, and it is larger than the port that
revealed it.

### A testing trap worth remembering: two packages, one port

The first settlement attempt produced no response and no print. The reason was not the code:

```
START u0 {cmp=com.sc.mf919/.kotlin.activity.SettlementActivity} from uid 10096, pid 2137
Background activity start ... result:START_ABORTED
```

`com.sc.mf919` (release) and `com.sc.mf919.dev` (the build under test) are both installed and both
bind ECR port 8888. **The release build had it**, so the request went to the wrong app entirely.
Force-stopping the release package fixed it.

**Any ECR test on a terminal carrying both packages is measuring whichever app won the port.**
Check the pid in the log against `pidof` for the package under test before trusting a result --
the same class of mistake as reading a stale `TerminaLog.txt` in item 57.

## 62. Why the `sdkPrint` fix went back (2026-09-14)

The guard was correct code applied to the wrong app. Kept as a record because the trap is easy to
walk into again.

**What the port assumed:** that MF919's `sdkPrint` callers are off the main thread, as Pro's are.
True for the two that exist -- `FragmentReceipt.printInfo` and `FragmentReceiptQr.printInfo`, both
`suspend fun ... = withContext(Dispatchers.Default)`. So the change was safe **as applied**.

**What made it pointless:** the value of a blocking guard is only realised once the other print
paths use it. MF919 has four:

| path | callers |
|---|---|
| `HelperCommon.sdkPrint` | 2, both already off-thread |
| `ActivityBase.kt:170` (`print`) | 6, **all on the main thread** |
| `ActivityBase.java:271` | Java base class |
| `PrintsActivity.kt:221` | inline |

And the six that would have to move are reached straight from click handlers:

```kotlin
fun print_detail_btn_ok(view: View?) {   // android:onClick -> main thread
    printReceipt()                        // -> printInfo() -> print(list), all synchronous
}
```

Consolidating them onto a blocking `sdkPrint` would put **a 30-second block on the UI thread of
every settlement and receipt reprint** -- an ANR whenever the printer is slow or out of paper,
which is strictly worse than the overlap it prevents. Doing it properly means first wrapping each
`printInfo()` in `CoroutineScope(Dispatchers.Default).launch { }`, exactly as
`FragmentReceipt.printReceipt` already does, then switching the call -- six payment-receipt flows,
each wanting hardware verification.

That is a real piece of work, not a tidy-up, and it was not what "consolidate the print paths"
was understood to mean. With the consolidation not happening, a guard on the one path whose
callers never needed it is dead weight that also misleads -- so `sdkPrint` went back to the shape
its callers were written for.

**Pro is unaffected and keeps its version**: there `sdkPrint` has ten callers including all three
settlement fragments, all already off-thread. The consolidation Pro completed is exactly what makes
the guard worth having.

### Left open

MF919's four print paths are unconsolidated and three are unguarded, including the settlement
receipt -- the one most likely to print several jobs back to back. The fix is understood and
written down; what it needs is a decision to move six UI flows off the main thread, and a printer
to verify each on.

## 63. Phase close, first pass -- FIGURES SUPERSEDED BY ITEM 67

> Items 64-66 (`HTTPServer` stages 1-3, the typed body read) landed after this was written, so the
> counts below are stale. The reasoning stands; for the final state see item 67.

The structural merge is complete. What remains in the two apps is either ruled per-app or one
open product question -- not drift waiting to be resolved. This closes the phase.

### Where the code ended up

| | files | lines |
|---|---|---|
| `:core` | 128 | 20,906 |
| `app-mf919` | 205 | 60,093 |
| `app-mf919pro` | 174 | 43,375 |

Shared file paths: **81 -- 23 identical, 58 drifted.** Duplicated lines in identical files: 1,123.

### The 58 drifted files, classified

| bucket | files | diff lines | merge work? |
|---|---|---|---|
| UI (activities / fragments) | 12 | 2,148 | **no** -- `:core` stays UI-free |
| models / repos / data_enum | 26 | 503 | **no** -- repos and models stay per app |
| device / vendor | 2 | 373 | **no** -- per app by nature |
| everything else | 16 | 2,716 | mostly no -- see below |

`HTTPServer.kt` alone is **1,659 of that last 2,716**, and it is the old-vs-new integration
difference, not drift. `ServiceHolder` (378) is the app's own config holder, per app by the same
ruling. Strip those and the genuinely mergeable surface left is about **15 small files and ~1,000
diff lines** -- schedulers, `Helper`, `AppBus`, `CounterGuard`, `GenerateQr`, `CoroutineTask`.

**That is why this phase ends here.** Converging `AppBus` (11 diff lines) buys nothing; the
remaining value is in the open defects, not in more file-by-file convergence.

### The one strategic question left

**`HTTPServer.kt`.** Pro carries both the old and the new integration; MF919 carries only its own.
A new-only model is expected. If MF919 eventually takes Pro's dual handling, that is a real merge
of ~1,700 lines. If not, the two stay apart permanently and the file leaves this list. Not a
refactoring decision.

### Open defects, carried forward

| item | where |
|---|---|
| `TerminalDMDispense` sent by production, no handler (logs and drops) | 57, 61 |
| `getPublicIP` busy-waits with no HTTP timeout; one caller per app | 50, 58 |
| MF919's print paths unconsolidated -- 12 callers on the UI thread, unguarded | 61, 62 |
| MF919 returns an empty `ResponseCode` on a failed sale where Pro returns `SHC005` | 15, 57 |
| WebSocket duplicate suppression keys on the whole message, so unique `RequestRef` defeats it | 57 |
| Empty-batch settlement divergence | earlier |
| Magstripe `schemeId` 12 vs 20 | pending BSN |
| `String2ArrayString` throws with no newline (pinned by test) | 47 |
| `hashData` returns a literal; dormant OTA updater depends on it | 48 |
| `cpAssetFile` compares line counts, not content | 49 |

Deferred by decision: RS232 testing, HTTP reference formalisation, the `TerminalSN` filter (it
needs a per-device serial, which is a post-merge change).

### What this programme actually bought

Beyond the line counts, the file-by-file work surfaced defects that were invisible from a diff:
a key-material leak into an uploaded log (53), an ECR queue that grew without bound (59), a
receipt pipeline that could not run (58), 4,930 lines of dead DB layer (55), a log-throttle that
never throttled (57), and a kiosk lock that silently did nothing (54). Several were found only
because a change forced someone to read code nobody had read in years.

### Method notes worth keeping

Four mistakes in this programme shared one shape -- **trusting a measurement without checking what
it measured**:

- a caller search whose filter excluded the answer (48, 55)
- a diff read without checking which side `+` meant (59)
- a stale `TerminaLog.txt` line paired with live events (57)
- a rationale carried from Pro without checking it applied to MF919 (60, 62)

And two environment traps: two packages can hold the same ECR port, so a test may measure the
wrong app (61); and logcat drops repeated identical lines, so occurrence counts mislead (50).

## 64. `HTTPServer` stages 1 and 2 (2026-09-14)

Measured per function first, because the file-level 1,659 diff lines say nothing useful.

| | count | |
|---|---|---|
| identical functions | 14 -> **16** | |
| differing | 14 -> **13** | |
| Pro-only | 4 -> **3** | `newIntegrationType` (590), `validateTransactionAmount`, `sendInvalidParameterResponse` |
| MF919-only | 3 | `cableHealthCheck`, `getSpecificProduct`, `rs232` |

**787 of the original 1,016 diff lines were one function.** Pro had split
`checkTransactionType` into a 24-line router plus `oldIntegrationType` (669) and
`newIntegrationType` (590); MF919 still had the 773-line monolith. So the earlier framing --
"MF919 must take Pro's dual integration" -- was **wrong**. MF919 does not need
`newIntegrationType`. What it was missing is the split.

### Stage 1: the small functions

Unlike `TmsHelper` (item 53), this is **not** mostly logging: of 229 diff lines across 13
functions, **213 are real code**. Applied only where Pro is clearly better and nothing
transport- or UI-architecture-specific is touched:

- **`startServeCable`** -- `when (connMethod.uppercase())`. A lowercase `CABLE_CONNECTION` from
  TMS previously matched **no branch at all**, silently leaving the cable transport unstarted.
- **`concatenateAndValidateLast`** -- the split regex is now compiled once instead of on every
  cable message.
- **`wakeScreen`** -- was `acquire(100)` followed immediately by `release()`, which barely woke the
  screen; now a 3 s timed hold, and the wake-lock tag is no longer `"ScreenLock:Test"`.

### Deliberately NOT applied, with reasons

- **`readRequestBody`** -- **MF919 is ahead here.** It returns a typed
  `BodyRead.Ok / .Short / .NoContentLength`, so a caller can tell a short read from a missing
  content-length; Pro collapses all three to `null`. That distinction is the fix for the old
  "short read becomes a bogus SHC001" bug. Pro's IOException catch and short-read log are worth
  taking **into** MF919's shape later -- not by replacing it.
- **`handleCancelWhileBusy`** -- **architectural, not drift.** MF919 drives Activities directly
  (`stopSearch`, `endEMV`, `abortSession`, navigate to Attend/Unattend); Pro emits
  `AppBus.emitWhenSubscribed(UiEvent.EndPaymentSession(true))` for its fragments to observe. Both
  correct for their own UI model. This is the per-app UI boundary showing up inside `HTTPServer`.
- **`resetCommunicationPort`, `onBackToRS232`, `cableConnectionReceiving`** -- serial transport.
  Pro is ahead (an explicit RS232 branch, a `reconnect` guard, `uppercase()`), but verifying any
  of it needs cable hardware, and RS232 is deferred by earlier decision.

### Stage 2: the split

MF919's 838-line body is now `oldIntegrationType`, with `checkTransactionType` a thin entry point.
**Pure rename, no logic change.**

MF919 deliberately gets **no branch**: it speaks only its own protocol, and a
`newIntegrationType` arm it can never take would be indirection pretending to be symmetry. The
shape now matches Pro's, so the branch has an obvious home if MF919 ever needs one.

**What this bought** -- for the first time the two old-integration handlers can be compared
directly:

```
mf919 oldIntegrationType : 773 lines
pro   oldIntegrationType : 669 lines
similarity 54%   diff 660 lines
```

That is the stage 3 surface, now isolated and named the same on both sides.

### Verified

Both apps build; `:core` 155 tests, 0 failures. On the MF919 terminal, a sale **through the split
entry point**: `00 (00)Approved`, 1.41, STAN 002058, batch 000696, appr 918754.

The release package was force-stopped first so the build under test owned port 8888 -- see the
trap in item 61.

### Stage 3, not started

660 diff lines inside `oldIntegrationType`, the sale/void/settle path for both fleets. It wants
going through case by case -- MF919-only feature, Pro improvement to take, or legitimate fleet
difference -- in small pieces, with the transport test cases run after each. A plausible outcome
is that much of it is legitimate divergence and the split alone was the win.

## 65. Stage 3: the 660 lines classified, and why most of them stay (2026-09-14)

Stage 3 was "go through the divergence case by case". Done -- and the conclusion is that most of
it should not be merged. Classifying 660 changed lines across 55 hunks:

| what the changed lines are | lines | |
|---|---|---|
| product lookup / sale-model construction | 157 | real divergence, see below |
| brace / `try{` / `catch (e` vs `catch (_` formatting | ~130 | noise |
| UI navigation: `Intent` + `startActivity` vs `AppBus` + `FragmentNavigation` | 105 | **architectural, per app** |
| response building | 65 | equivalent in effect |
| parameter validation / toasts | 60 | see below |
| config checks, `when` labels, declarations | ~35 | mostly formatting |

**My first guess was wrong and the measurement corrected it.** I expected navigation to dominate
at 70-80%; it is 16%. Worth recording because the instinct to classify by eye was exactly what
this programme has been burned by.

### Not merged, with reasons

- **UI navigation (105).** MF919 builds an `Intent` and calls `startActivity`; Pro emits
  `AppBus.emitWhenSubscribed(UiEvent.FragmentNavigation(...))` for its fragments. Both correct for
  their own UI model -- the per-app boundary, appearing inside `HTTPServer` at every transaction
  type. Same finding as `handleCancelWhileBusy` in item 64, now shown to run through the whole
  handler.
- **Product lookup / sale model (157).** MF919 builds a `SalesModel` / `SaleModelNew` and caches it
  in `ServiceHolder.saleModelCache`; Pro resolves via `ProductListRepo.getSinglev2(...) ?: throw`.
  Different product-resolution designs feeding different UI models. A product decision, not a
  refactor.
- **Formatting (~165).** Not worth a diff in the transaction path.
- **`resultObject`.** MF919 aliases the incoming `requestJson`; Pro takes `deepCopy()`. Pro's is
  defensively better, but MF919's `jsonObject` is parsed per request and never read after the call,
  so the alias is harmless **today** and porting it would change nothing observable.

### Merged: the wake-screen guard -- which caught a regression I had just introduced

Item 64 changed `wakeScreen` from `acquire(100)` + immediate `release()` to a 3 s hold, because
the old form barely woke the screen. **That was only safe with a guard MF919 did not have.**

MF919 called `wakeScreen()` from `serve()` -- on **every HTTP request**, before the transaction
type is even parsed. With a 100 ms acquire-and-release that was close to a no-op; with a 3 s hold
it would light the screen on every POS status poll and cancel.

Pro calls it inside the handler, guarded:

```kotlin
if (txnType != 0 && txnType != 13) {   // not cancel, not status
    wakeScreen()
}
```

MF919 now does the same: the unconditional call is out of `serve()`, and the guarded call sits
after `txnType` is known. **The 3 s hold and the guard are one change; taking either alone is
wrong** -- which is the second time in two days that a port was safe in isolation and wrong in
context (see item 62).

### Verified

Both apps build; `:core` 155 tests, 0 failures. On the MF919 terminal: a cancel (`type 0`) answers
`No Session Running`, and a sale approves -- `00 (00)Approved`, 1.47, STAN 002060, appr 753505 --
so the guard did not break the transaction path.

**The wake distinction is verified**, by sampling `dumpsys power` either side of each request with
the terminal asleep:

| | `Display Power` |
|---|---|
| before | `OFF` |
| cancel (type 0) | `OFF` -- unchanged |
| sale (type 1) | `OFF -> ON` within 1 s |

The sale approved: 1.53, STAN 002061, appr 249250.

**My first attempt at this check was worthless and is worth recording.** I grepped logcat for
`WakeLock|PowerManagerService` and read 0 occurrences as "correctly did not wake" -- but this ROM
logs no wake-lock activity at all, so it reads 0 either way. That is the same mistake as the
notification-shade grep in item 54: **an absence of log lines is not evidence unless you have first
shown the instrument can produce them.** `dumpsys power` is the instrument that can.

### Verdict on stage 3

**Do not continue it as a convergence exercise.** The split in item 64 was the win: the two
handlers are now named alike and directly comparable, so future drift is visible. What remains is
formatting, two per-app architectures, and one product decision.

One optional follow-up, not done: MF919 repeats the inline "Invalid Parameter" block **37 times**
(~250 lines); Pro factored it into `sendInvalidParameterResponse`. Extracting it in MF919 would cut
real duplication -- but **Pro's helper builds a fresh `JsonObject` and throws, where MF919's inline
form mutates the request echo and returns**, so a verbatim port would change what an SHC001
response contains. That is an ECR contract change and needs agreement before anyone attempts it.

## 66. Pro gains the typed body-read -- and a bug in both apps surfaces (2026-09-14)

MF919 was ahead here: `readRequestBody` returned a typed
`BodyRead.Ok / .Short / .NoContentLength`, where Pro returned `String?` and could not tell a
truncated body from a malformed header. Ported to Pro.

**Option 2 of three, chosen deliberately.** MF919 answers `errorJson(0)` (SHC000 System Busy) for
a missing content-length; Pro answered `errorJson(1)` (SHC001 Invalid Input). Only the *type* was
ported -- **all three failure paths in Pro still answer `errorJson(1)`, exactly as before.**
Calling a malformed header "System Busy" is questionable, and adopting it across the fleet for
symmetry rather than for a reason is how the `sdkPrint` mistake happened (item 62). The wire
contract does not move; only the log improves.

### The bug the test found, in both apps

First version on hardware:

```
short read (Content-Length: 120, 21 bytes sent) -> "Missing/Invalid content-length :: 120"
```

Wrong, and misleading in the worst direction: the header was **fine**, the transport died
mid-body. Cause was a `SocketTimeoutException` from `BufferedInputStream.fill`, which both apps
mapped to `NoContentLength` -- Pro inside `readRequestBody` (my port), MF919 at the caller.

An IO failure mid-read **is** a short read, and `off`/`len` are in scope to say so. Both apps now
return `BodyRead.Short(off, len)`. MF919's catch moved from the caller into `readRequestBody`,
because the caller has neither number.

Verified on the Pro terminal, raw HTTP:

| request | response | log |
|---|---|---|
| `Content-Length: 120`, 21 bytes sent | `SHC001 Invalid Input` | `Short Read :: 21/120` |
| no `Content-Length` header | `SHC001 Invalid Input` | `Missing/Invalid content-length :: null` |

Same answer to the POS in both cases; the log now names the actual fault.

**Worth noting how this was found.** The port looked right, compiled, and returned the correct
response codes -- the wire test passed. Only reading the *log line* showed the fault, and only
because the failure was driven deliberately with a raw socket rather than assumed to work. A
response-code check alone would have shipped it.

## 67. Merge phase closed

Final state. Everything below is measured, and every behavioural change in this phase was
exercised on a terminal.

### Where the code sits

| | files | lines |
|---|---|---|
| `:core` | 128 | 20,906 |
| `app-mf919` | 205 | 60,116 |
| `app-mf919pro` | 174 | 43,405 |

81 shared file paths: **23 identical, 58 drifted.** 1,123 duplicated lines in identical files.

### The 58 drifted files are not a backlog

| bucket | files | diff lines | mergeable? |
|---|---|---|---|
| UI -- activities / fragments | 12 | 2,148 | no, `:core` stays UI-free |
| models / repos / data_enum | 26 | 503 | no, per-app by ruling |
| device / vendor | 2 | 373 | no, per app by nature |
| everything else | 16 | 2,768 | mostly no |

`HTTPServer.kt` dominates that last bucket, and after item 64 its remaining divergence is known:
~165 formatting, 105 per-app UI navigation, 157 product-model resolution. `ServiceHolder` (378) is
the app's own config holder.

**There is no meaningful convergence work left.** What remains is ruled per-app, formatting, or
one product decision.

### The one product decision outstanding

**How each app resolves the product / sale model** (157 lines inside `oldIntegrationType`). MF919
builds a `SalesModel` and caches it in `ServiceHolder.saleModelCache`; Pro resolves through
`ProductListRepo.getSinglev2(...)`. Two designs feeding two UI models. Someone has to decide
whether the fleets converge on one -- it is not a refactoring question.

### Verified at close

All four variants build -- `sharecommDebug` and `sharecommStag` for both apps. `:core` **155
tests, 0 failures**.

### What this phase changed

`:core` extracted and grown to 128 files; the Utils slice completed in five tranches (`Utils.java`
1,868 -> 1,174 and 1,635 -> 942); the legacy DB layer deleted (4,930 lines); `WebSocketClient`,
`WebSocketServer` and `UploadTMS` driven to byte-identical; `TmsHelper` from ~253 diff lines to 34;
`HTTPServer`'s monolith split so the two handlers are finally comparable.

### Defects found and fixed on the way

Most were invisible from a diff and surfaced only because a change forced someone to read code
nobody had opened in years:

- key material written into an uploaded log (53)
- an ECR response queue with no size bound (59)
- a log throttle that never throttled, ~19 writes/minute (57)
- a receipt pipeline that could not run (58)
- a kiosk lock that silently did nothing (54)
- 4,930 lines of dead DB layer, one of which truncated a database and returned true (55)
- a lowercase TMS config value that matched no branch, leaving the cable transport unstarted (64)
- a truncated request body reported as a header fault (66)

### Open items, carried forward

`TerminalDMDispense` sent by production with no handler; `getPublicIP`'s missing HTTP timeout;
MF919's four print paths with 12 main-thread callers unguarded; MF919's empty `ResponseCode` on a
failed sale; websocket duplicate suppression defeated by unique `RequestRef`; empty-batch
settlement divergence; magstripe `schemeId` pending BSN; `String2ArrayString` throwing with no
newline; `hashData` returning a literal under a dormant OTA path; `cpAssetFile` comparing line
counts.

Deferred by decision: RS232 testing, HTTP reference formalisation, and the `TerminalSN` filter --
which needs a per-device serial and therefore belongs after the merge phase.

### Method notes, earned the hard way

Five mistakes in this programme shared one shape -- **trusting a measurement without checking what
it measured**:

- a caller search whose filter excluded the answer (48, 55)
- a diff read without checking which side `+` meant (59)
- a stale `TerminaLog.txt` line paired with live events (57)
- a rationale carried from Pro without checking it applied to MF919 (60, 62)
- an absence of log lines read as evidence, from an instrument that never produces them (54, 65)

Two environment traps: two packages can hold the same ECR port, so a test may measure the wrong
app (61); and logcat drops repeated identical lines, so occurrence counts mislead (50).

And one that cost three attempts in a single afternoon: **shell heredocs silently eat backslashes**
-- regex and `\n` literals must be built with `chr(92)` or edited by line position, never pasted.
