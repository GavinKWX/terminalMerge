# Building

Three modules: `:core` (shared Android library), `:app-mf919` (`com.sc.mf919`),
`:app-mf919pro` (`com.sc.mf919pro`).

Toolchain: AGP 8.13.0, Gradle 8.13, Kotlin 1.9.24, Java 11 target, JDK 17+ to run
Gradle. `JAVA_HOME` must point at a JDK 17 or newer — Android Studio's bundled JBR
works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"
```

## Common commands

Both debug APKs (the usual one):

```bash
./gradlew :app-mf919:assembleSharecommDebug :app-mf919pro:assembleSharecommDebug
```

One app, one flavor:

```bash
./gradlew :app-mf919pro:assembleSharecommDebug
```

Everything, all flavors — slow, use before a release:

```bash
./gradlew assembleDebug
```

Clean rebuild (forces `processResources`, so aapt2 actually runs):

```bash
./gradlew clean :app-mf919:assembleSharecommDebug :app-mf919pro:assembleSharecommDebug
```

Stop stale daemons:

```bash
./gradlew --stop
```

## Outputs

```
app-mf919/build/outputs/apk/<flavor>/<buildType>/app-mf919-<flavor>-<buildType>.apk
app-mf919pro/build/outputs/apk/<flavor>/<buildType>/app-mf919pro-<flavor>-<buildType>.apk
```

## Flavors and build types

Flavor dimension `client`, 10 flavors: `sharecomm`, `special`, `rm`, `glypay`,
`baguspos`, `paydibs`, `payex`, `oxpay`, `rnd`, `bsn`.

Build types: `debug` (`.dev` suffix), `stag` (`.uat` suffix), `release` (no suffix).
So `:app-mf919:assembleSharecommDebug` installs as `com.sc.mf919.dev` and coexists
with a production `com.sc.mf919` on the same device.

A variant filter keeps the matrix to what is actually shipped — see
`settings.gradle.kts`.

## Installing to a terminal

```bash
adb install -r app-mf919/build/outputs/apk/sharecomm/debug/app-mf919-sharecomm-debug.apk
```

Because debug carries the `.dev` applicationId suffix, this never overwrites a
production install.

## Signing

All three build types -- debug, stag and release -- are signed with
`keystore/debug.keystore`. **This is intentional. Do not repoint release at a
separate release keystore or a CI-injected one.**

Why it is deliberate:

- Distribution is via **TMS, not the Play Store**, so Play's signing requirements
  never apply.
- A **manufacturer signing layer** is applied before the APK is uploaded to TMS.
  That is the signature the fleet actually trusts; the one applied here is not.
- Android only allows an in-place update from an APK signed with the **same** key.
  Switching release onto a different keystore would break app updates on every
  terminal already in the field.

A security review will flag this -- it is the standard Android debug certificate
(`CN=Android Debug`), the keystore is committed, and the password is the default
`android`. That finding is understood and accepted for this distribution model.
Confirmed 2026-09-04. Tracked as audit item B3, closed as by-design.

## Troubleshooting

### AAPT2 "Daemon startup failed" / `CreateProcess error=740`

```
AAPT2 ... Daemon startup failed
Please check if you installed the Windows Universal C Runtime.
...
Caused by: java.io.IOException: CreateProcess error=740, The requested operation
requires elevation
```

Ignore the C runtime advice — it is a generic fallback message. Error 740 means
Windows refused to launch `aapt2.exe` from that process.

Observed cause on Windows: **Android Studio running elevated.** If
`studio64.exe` has a `RUNASADMIN` compatibility flag, Studio's Gradle builds can
fail this way while the identical build succeeds from a normal shell.

Check:

```powershell
$k='HKCU:\Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers'
Get-Item $k | ForEach-Object { $_.GetValueNames() | ForEach-Object { "$_ => $((Get-Item $k).GetValue($_))" } }
```

Fix: close Studio, then remove the flag —

```powershell
$k='HKCU:\Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers'
'C:\Program Files\Android\Android Studio\bin\studio64.exe' |
  ForEach-Object { Remove-ItemProperty -Path $k -Name $_ -ErrorAction SilentlyContinue }
```

Reopen Studio; no UAC prompt means it worked. Unticking the Compatibility
checkbox on a *shortcut* does not clear this — it has to be the `.exe`.

Workaround if you would rather leave Studio elevated: build from a normal
terminal with the commands above. Editing, indexing and Gradle sync still work in
Studio; only its Build/Run actions go through the failing path.

### Other

`Unable to strip ... libdatastore_shared_counter.so` during
`stripDebugSymbols` is a harmless warning from the DataStore native library; the
`.so` is packaged unstripped.
