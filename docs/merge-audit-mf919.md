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
| I1 | **CI builds 1 of 10 client flavors** (`sharecomm` only). Nine flavors have their own `src/` source sets -- oxpay, bsn, glypay, rm, baguspos, paydibs, payex, rnd -- and none of them is ever compiled by CI. Flavor source sets are exactly where per-client bugs hide, so this is the weakest point in the pipeline. | high |
| I2 | **`special` flavor is declared in both apps with no `src/special/` directory.** Open since the original audit. Either give it sources or delete the flavor. | low |
| I3 | **B4 variant filter not done** -- 10 flavors x 3 build types x 2 apps = **60 variants**. | medium |
| I4 | **No lint or static-analysis step** in CI. | medium |
| I5 | **JDK drift** -- CI pins 17, local builds run on Android Studio's bundled JDK 21, `compileOptions` targets Java 11. All three satisfy AGP 8.13, but the combination is untested. | low |

I1 is the highest-value fix: a flavor matrix, or at minimum one additional flavor.

### Local builds were reporting FAILED on success (fixed 2026-09-07)

Gradle's experimental HTML problems report writes to a single file in the **root** project's build
dir. Any process holding it open makes every build exit non-zero with
`AccessDeniedException: build/reports/problems/problems-report.html`, long after all real work has
succeeded -- and Android Studio's daemon holds it. So a CLI build run with the IDE open reported
`FAILED` on a green build, which on payment code is worse than no signal: it hides genuine
failures. Disabled via `org.gradle.problems.report=false` in `gradle.properties`, with the
reasoning recorded there.

CI was never affected -- it runs no IDE - so the pipeline has been honest throughout.

## 15. SHC007: recommendation (open, 2026-09-07)

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

**Recommended, in order:**

1. **Now -- documentation only, no code change.** State in the vendor integration guide that
   `SHC007` with description `Terminal Response Timeout` means *query before retry*. Zero risk,
   zero contract change, and it resolves the ambiguity for any integrator who reads the guide.
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
