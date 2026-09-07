# Phase 0 — import baseline

What was imported, from where, and why. Recorded so the choice is auditable later.

## Imported commits

| module | source repo | branch | commit | date |
|---|---|---|---|---|
| `app-mf919` | `ShareCommerce.Terminal` | `deploy/v2.2.26` | `f4827d0f` | 2026-09-01 |
| `app-mf919pro` | `ShareCommerce.Terminal_MF919Pro` | `origin/dev/GavinDev` | `2f8a7cc3` | 2026-08-28 |

Imported with `git subtree add`, so both projects' full histories are preserved in
this repo (1251 commits at Phase 0). The source repos were read-only throughout and
nothing was committed in either.

## Why these branches

The user chose "OLD deploy + PRO dev tip" deliberately, matching each project's risk
profile: MF919 is the shipping fleet with live POS-vendor integrations, so it starts
from a known-good release point; MF919 Pro is younger and moving faster, so it starts
from the current tip.

Two corrections were applied during the import, both raised by parallel review:

1. **Pro was initially imported one commit short.** The local `dev/GavinDev` was
   behind `origin/dev/GavinDev` by `2f8a7cc handle TPA flag for EPP`. The subtree was
   rolled back and re-added from the origin ref, so the true tip is what landed.
   (`handle TPA flag for EPP` is one of the 14 duplicated 2026 commit subjects — it
   now appears twice in this repo's log, as `2f8a7cc3` on the Pro side and `edfe9944`
   on the MF919 side. A concrete illustration of what the merge exists to stop.)

2. **`develop/uiEnhance` is 4 commits ahead of the imported OLD baseline and was
   deliberately not taken.** It is a testing-only branch (confirmed by the user,
   2026-09-03) — experimental work, not a production line:

   ```
   7d41920d  tts service
   df93e690  take aux code from mf919 pro
   472707ba  keypad ui lag
   dd543804  Dynamic UI for attend and alert dialog selection
   ```

   So there is **no carry-across debt** here and nothing for Phase 1 to reconcile.
   `deploy/v2.2.26` is the correct MF919 production baseline. Noted only so that
   anyone comparing this repo against the MF919 working tree understands why those
   four commits are absent, and does not "helpfully" merge them in.

   If any of that experimental work is later promoted to a production branch, it
   arrives here the normal way — through `deploy/*` — not by cherry-picking from
   `uiEnhance`.

## Environment

- JDK 21 (`C:\Program Files\Android\Android Studio1\jbr`), which `JAVA_HOME` already points at.
- Android SDK at `C:\Users\gavin\AppData\Local\Android\Sdk`, platforms 33 + 35, build-tools 35.0.0.
- `local.properties` is gitignored and must be created locally with `sdk.dir`.

## Verified at the end of Phase 0

- `:core:assembleDebug` — succeeds.
- `:app-mf919:assembleSharecommDebug` — succeeds, APK `com.sc.mf919.dev`, 12 MB.
- `:app-mf919pro:assembleSharecommDebug` — succeeds, APK `com.sc.mf919pro.dev`, 32 MB.
- Vendor contracts, read back out of the built APKs rather than the source manifests:
  - `com.sc.mf919/com.sc.mf919.java.activity.TransactionReceiver` — `exported=true`,
    intent-filter `ACTION_SEND` + `category.DEFAULT` + `image/*` + `text/plain`.
  - `com.sc.mf919pro/com.sc.mf919pro.kotlin.activity.TransactionReceiver` —
    `exported=true`, `launchMode=singleTask`, no intent-filter.
- `versionName` unchanged in both apps (`2.2.26` / `1.0.04`).

**Not verified:** nothing was run on hardware — no device was attached to adb during
Phase 0. The exit criterion "a real transaction driven on hardware" is outstanding
and should be met before Phase 1 begins.
