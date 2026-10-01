# Mod API distribution compatibility fixtures (PKG-002)

Reproducible evidence for the distribution model in
`docs/contracts/PKG-002-distribution-model.md`: one consumer jar, one API namespace, exactly one
FML provider at runtime; stock Forge and NestWorld differ only in the execution backend.

## Layout

| Path | What |
|---|---|
| `consumer-src/` | the single consumer source tree (mod id `nwinvprobe`), shared by both forms below |
| `compileonly-consumer/` | **form A**: `compileOnly` API; the provider is external (stock Forge) or built in (NestWorld) |
| `jarjar-consumer/` | **form B**: same, plus the provider MOD as jar-in-jar (never the API classes) |
| `common.gradle` | everything the two forms share (mappings, API dependency, metadata) |
| `broken-provider/` | negative controls generated with ASM **from the published provider** |
| `run-matrix.sh` | installs a stock Forge and a NestWorld server and runs the matrix |
| `rcon.py` | minimal RCON client used by the runner |

## Matrix (`run-matrix.sh`)

| Runtime | Consumer | Provider | Expected |
|---|---|---|---|
| Stock Forge | compileOnly | external | PASS |
| Stock Forge | compileOnly | missing | clean FML refusal (missing `nestworld_modapi`) |
| Stock Forge | jarJar | nested | PASS |
| NestWorld | compileOnly | built-in | PASS |
| NestWorld | jarJar | built-in + nested | PASS (FML unifies the copies) |
| NestWorld | compileOnly | unsupported newer (1.0.1) | FML refusal (Gate 1, Core's verified range) |
| NestWorld | compileOnly | supported version, `setSlot` removed (S5) | ABI gate refusal (Gate 2) |
| NestWorld | compileOnly | supported version, abstract method added | ABI gate refusal |
| NestWorld | compileOnly | supported version, registry class deleted | ABI gate refusal |
| NestWorld | compileOnly | 9.0.0, `setSlot` removed (E7) | FML refusal (Gate 1 first) |

**PASS** = the server reaches `Done` and `/nwprobe smoke -` reports `RESULT SMOKE: PASS` — the
provider is resolved, a real `setVelocity` moves an armor stand, and the `setSlot` path links. Every
row also requires **zero** `NoSuchMethodError` / `AbstractMethodError` / `NoClassDefFoundError`.
Refusal rows must refuse before the server reaches `Done`.

## Running

```bash
# from the repo root
./gradlew :forge:installerJar                       # NestWorld installer
FORGE_INSTALLER=/path/to/forge-1.20.1-47.4.20-installer.jar \
NESTWORLD_INSTALLER=projects/forge/build/libs/forge-1.20.1-<version>-installer.jar \
tests/modapi-compat/run-matrix.sh                   # builds + publishes, then runs the matrix
```
`SKIP_BUILD=1` reuses existing builds; `WORK=<dir>` chooses where the two servers are installed
(default `/tmp/modapi-compat-work`). The exit code is the number of mismatching rows. Use the exact
installer version the build log reports (the version changes with every commit).

## Deeper, bot-driven scenarios

The consumer also contains the API-1B.7 stock-Forge fixtures (`snapshot`, `respawn`,
`crossboundary`, `stale`, `logout`), driven by `/nwprobe <scenario> <player>` with a connected
player and an external driver that performs the `ACTION_REQUIRED` steps (`kill`, `respawn`,
`relogin`). They need a bot client (e.g. mineflayer with manual respawn) and are not run by
`run-matrix.sh`; procedure and results: `docs/contracts/api-1b7-findings.md` (stock-Forge gate).
The NestWorld-side equivalents live in Core: `/nestworld testmutate invdet <scenario> <player>`.
