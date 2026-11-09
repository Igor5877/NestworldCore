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

## Owner decisions 2026-11-09 — B1 (D1–D9)

| | decision |
|---|---|
| D1 lane | APPROVED: non-blocking serialization token, no AE2 thread. Free → MBCalculator runs now in the current host domain; busy → register request, caller returns; on release the next target is enqueued into THAT target's current BE execution domain, revalidated, calculated there. MAIN is not the lane executor. The token decides who may touch MBCalculator's global state, not which thread does world access |
| D2 cross-domain multiblock | APPROVED as transitional: position-pin such members to MAIN + counter `crossDomainMultiblockPinned`; not final (cluster execution domain later) |
| D3 queue | NO unbounded FIFO: per multiblock / target max `1 active + 1 dirty rerun` (`dirtyGeneration`, one fresh calculation after the active one) |
| D4 D4a | D4a stays; its MBCalculator part takes the same B1 token; exactly-once untouched; nested acquisition of another token forbidden; repeated same operationId defined, never deadlock |
| D5 addons | one shared semantic entrypoint if they really go through MBCalculator; per-addon fixture proving the wrapper intercepts its calculation path |
| D6 megacells | KEEP pinned until the selective-unpin gate after B1 |
| D7 unpin order | selective only: verified crafting / multiblock types → transit / WP3-B → other machine paths → global ae2 pin last |
| D8 acceptance | negative control + positive no-pin run: MB concurrent 0, ISE 0, silent-skip 0, init failures 0, dup / loss 0, queue outstanding 0 at quiescence; ticket latency p50/p95/p99/max; dirty-rerun / coalesced counts. Instrument BEFORE B1: concurrent entry, already-in-progress ISE, silent skipped recalculation (MBCalculator:84), foreign-finally reset anomaly, recalculation requested / completed; after B1 `requested = completed + coalesced + stale` |
| D9 B0 | APPROVED now; diagnostic / debug, no noticeable hot-path overhead; fix soak harness `gridcensus report → start` |

Production prerequisite (current versions): **AE2 and every MBCalculator / CraftingBlockEntity-based addon MUST be
pinned until B1 selective-unpin verification.** Check: `compat/nestworld-compat-ae2/tools/mbcalc-pin-audit.sh <server>`
(exit 1 on any unpinned user). Live 2026-11-09: the audit found `ae2omnicells` (OmniCraftingBlockEntity extends
CraftingBlockEntity) and `mae2` (DENSE_ACCELERATOR = BlockEntityType<CraftingBlockEntity>) unpinned besides megacells →
all three pinned on live (0 players, persisted); audit now exit 0.
