# ShareCommerce Terminal — MF919 + MF919 Pro

Unified repository for the two Android payment-terminal apps.

| module | applicationId | minSdk | UI |
|---|---|---|---|
| `:core` | — (library) | 24 | none |
| `:app-mf919` | `com.sc.mf919` | 24 | Activities |
| `:app-mf919pro` | `com.sc.mf919pro` | 29 | Fragments + Navigation |

Both apps keep their own bundle id, their own `TransactionReceiver` (Java on MF919,
Kotlin on Pro) and their own Attend UI. Shared logic moves into `:core` in phases.

See [docs/merge-audit-mf919.md](docs/merge-audit-mf919.md) for the full audit,
the risk register and the phase plan.

## Build

    ./gradlew :app-mf919:assembleSharecommDebug
    ./gradlew :app-mf919pro:assembleSharecommDebug

Requires JDK 17+ and `local.properties` with `sdk.dir`.
