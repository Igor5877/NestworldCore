# AE2-DEMAIN-001 — remove the production requirement `pinmod ae2 → MAIN` (owner decision 2026-11-09)

Goal: stop using the server MAIN thread as AE2's global mutex. Shared AE2 state may stay serialized, but in an AE2
execution domain, not on MAIN. NOT a goal: making all of AE2 concurrent. No direct unpin before the gates below.

Runs in parallel with Track A (CORE-AUTOSPLIT-FIX-001: Stage 1 shadow → calibration → Stage 2 enforce → Stage 3
regret merge → autoSplit=true); the tracks only coordinate on physical CPU for stress tests (heavy tests at 0 live
players). Reopens the frozen WP3-B and WP4 (AE2-COMPAT-ARCHITECTURE §6).

| step | content |
|---|---|
| B0 | baseline on a production-like workload: AE2 MAIN time, AE2 BE tick time, storage ops/tick, crafting CPU time, MBCalculator time, import/export/interface activity, callbacks, transit ops, MAIN MSPT contribution |
| B1 | MBCalculator gate — reproduction: invalid H run1 (`MB_MODIFICATION concurrent 1259`, ISE "modification already in-progress", BE init failures). MBCalculator no longer relies on MAIN for serialization. First safe form: one serialized AE2_SHARED / MULTIBLOCK execution lane off MAIN; every shared multiblock structural operation goes through it. No ThreadLocal of the global flag to hide the race |
| B2 | AE2_SHARED never mutates the world, calls neighbour callbacks, mutates foreign BEs, or waits for MAIN / REGION; world mutation = intent → BlockEntityExecutionDomain → revalidate → execute |
| B3 | after verification: keyed domains (per grid / per cluster / striped) so independent networks do not serialize each other; correctness first |
| B4 | WP3-B REOPENED: region-local AE2 machines tick in their REGION; shared-storage operations go through the AE2 grid domain; never pull a whole machine to MAIN for one storage op |
| B5 | asynchronous transit: REGION host → request → AE2 grid domain transaction → result → host applies; no synchronous wait; ≥ 1 tick latency allowed if gameplay semantics hold |
| B6 | transaction safety: operationId, host generation, grid generation, item/fluid identity, requested / committed amount, terminal result, dedup; no duplicate extract/insert, no loss, no stale result to a replacement host or new grid generation |
| B7 | WP4 REOPENED: by profile — most expensive MAIN path → targeted migration → fixture → performance measurement |
| B8 | pin decomposition: AE2_ALL_MAIN → only unresolved shared types MAIN → only legacy exceptions → no AE2 MAIN pin; pinned AE2 BE count decreases monotonically |
| B9 | per removed pin/path: off-domain 0, duplicate 0, item loss 0, MB concurrent 0, S1/S2/S3 0, lifecycle / topology / accounting clean, no watchdog, MAIN time not increased; performance before / after |
| B10 | final no-pin soak (30 min, production-like copy): crafting / storage / import-export / interfaces / power-channel / cluster teardown-rebuild / unload-reload / split-merge / several independent networks; MB concurrent 0, ISE 0, loss/dup 0, detectors 0, crash 0, watchdog 0 |
| B11 | `AE2-NO-MAIN-PIN-PRODUCTION-VERIFIED` → production `pinmod ae2` removed |

Status 2026-11-09: B0 + B1 source audit started (audit → design → STOP for review).

B0/B1 audit: `AE2-DEMAIN-001-B0-B1-audit.md` (STOP for review, decisions D1–D9). Immediate live action 2026-11-09
from it: MEGA crafting unit / storage are AE2 `CraftingBlockEntity` types registered under `megacells:` (CONFIRMED by
javap of megacells-forge-2.4.6 MEGABlockEntities) and `megacells` was NOT pinned on live → same MBCalculator race class
as invalid H run1; `/nestworld pinmod megacells` applied on live (0 players, persisted; standing pin permission).
