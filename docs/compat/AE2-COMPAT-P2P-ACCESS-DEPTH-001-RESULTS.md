# P2P-ACCESS-DEPTH-001 (AE2-COMPAT-P2P-001 H2) — implementation and evidence

**Status: CLOSED / VERIFIED / FROZEN (owner, 2026-10-15).** H2-1..H2-5, negative control, 120 s
stress (JFR) and focused regression PASS. Since P2P-FORWARDING-FAIL-CLOSED-001, H2-1 / H2-3 run on
the owner thread (see that gate's §3).

Sub-gate statuses:

| Sub-gate | Status |
|---|---|
| P2P-MAP-STATE-001 (H1) | CLOSED / VERIFIED / FROZEN; re-run unchanged |
| P2P-FORWARDING-001 (H3) | OPEN / CONFIRMED; re-run unchanged |
| CALLBACK-UNDER-P-001 | OPEN |
| AE2-COMPAT-P2P-001 | OPEN |
| unpin | **BLOCKED** |

## 1. Model

The state is AE2 15.4.10's `CapabilityP2PTunnelPart.accessDepth`. It is the only owner of that
field; FE, item and fluid tunnels inherit it.

It is a plain `int` on the tunnel, read and written by:
- `getAdjacentCapability` (`++`);
- `CapabilityGuard.get`: `== 0` → ISE "get was called after closing", `== 1` → the real neighbour
  handler, `> 1` → the empty handler (recursion);
- `CapabilityGuard.close`: `--`, and `< 0` → ISE "Close has been called multiple times".

The depth describes **the current synchronous call stack**, not the tunnel object.

**Fix:** the depth is owned by **(thread × tunnel instance)**.
- Each tunnel gets a per-instance `ThreadLocal<int[]>` (`CapabilityP2PTunnelPartMixin`).
- All 4 field reads and 2 field writes are redirected to it:
  `getAdjacentCapability` and the guard's `get` / `close` (`CapabilityGuardMixin`, nestmate
  `getfield` / `putfield`).
- At depth 0 the entry is `remove()`d.
- AE2's semantics are byte-for-byte unchanged:
  - depth 1 → real handler;
  - depth > 1 → empty handler;
  - depth 0 at `get` → ISE;
  - depth < 0 at `close` → ISE.
- No lock, no new blocking primitive.

Counters:
- `p2pDepthRejected` — vanilla recursion rejections;
- `p2pDepthFalseReject` — rejections on fixture-watched tunnels that no fixture recursion passes
  through;
- `p2pDepthUnderflow`.

Negative switch: `P2PDepth.PROTECT = false` (`rig/negp2p_shareddepth.py`), which restores AE2's
shared field.

## 2. Deterministic fixtures (`rig/h2-run.sh`, scratch rig, everything in one region)

| Case | Setup | THREAD depth (fix) | SHARED int (negative = vanilla) |
|---|---|---|---|
| **H2-1 real recursion** (H2-4 boundary) | loop I1 → O1 → I2 → O2 → I1 on one grid, transfer on main | **PASS**: accepted 0, exactly 1 rejection, guard sequence `[O1@1, O2@1, O1@2]` (rejected at depth **2**, AE2's boundary), no StackOverflow, all depths 0, no ThreadLocal residue | PASS. Single thread; the semantics are preserved by construction |
| **H2-2 two threads, one tunnel** (FE / item / fluid) | A = main parked **inside** a transfer through output O (depth 1); B = RegionThread does an independent transfer through the same O | **PASS ×3**: B accepted 1000 / 16 / 1000; A resumed and accepted; both depths 0; `falseRejects+0` | **FAIL ×3**: B got the empty handler (**accepted 0**), `falseRejects+1`; B observed depth 1 left over from A |
| **H2-3 handler throws** (FE / item / fluid) | an exception inside the guarded call (fixture hook at depth 1) | **PASS ×3**: propagated; depth 0 afterwards; the next transfer on the same thread works | PASS ×3. In vanilla too, single-thread cleanup is correct (`try-with-resources`) |
| **H2-5** | FE, item and fluid share one mechanism (`CapabilityP2PTunnelPart`) | H2-2 and H2-3 were run for all three | — |

The discriminating test is **H2-2**. It fails under the shared int exactly as the audit predicted
(§3.2: "T1 `++`, T2 `++`, T2 `get` → treated as recursion → transfer silently refused").

## 3. Stress (120 s, `nwcompatae2 p2p h2stress`, JFR recording)

| Workload | Threads |
|---|---|
| plain producers on the 3 shared tunnels: FE / item / fluid input handlers resolved on main; producer 0 makes the handler throw every 100th transfer | 4 |
| owner RegionThread: real targets | 1 |
| recursion loop, 1 per step, on a second host of the same region | 1 |
| main: real transfers every tick | 1 |

| Metric | THREAD (fix) | SHARED, 30 s (comparison) |
|---|---|---|
| guard entries | 90,455,328 | — |
| plain transfers | 90,210,891 | — |
| region / main transfers | 7,203 / 7,215 | — |
| loops → **realRecursionRejected** | 2,401 → **2,401** (exact) | — |
| **crossThreadFalseRejects** | **0** | **55** |
| **depthUnderflow** | **0** | **2,438** |
| **depthLeak** | **0** | yes (`depth=-2` left on threads) |
| thrown / caught (expected callback exceptions) | 222,816 / 222,816 | — |
| unexpected exceptions | **0** | ISE "Close has been called multiple times", ISE "get was called after closing the wrapper" |
| verdict | **PASS** | **FAIL** |

The SHARED run reproduces every vanilla failure mode in the audit:
- false refusal;
- the ISE pair;
- a negative or stuck depth, i.e. the "permanently dead tunnel" class.

H2 is therefore **CONFIRMED by run**, and the fix eliminates it.

**JFR** (`evidence/h2-stress120-120752.jfr`):
- **no `jdk.ThreadPark` event on the depth path**;
- the 30 `JavaMonitorEnter` events all come from the fixture's own `synchronizedList`;
- CPU samples show `ThreadLocalMap.getEntry` / `set`, the expected ThreadLocal cost;
- longest GC pause 16.3 ms.

The fix introduced no unexpected monitor or park paths.

## 4. Focused regression (H2 jar)

| Suite | Result |
|---|---|
| H1 (`rig/h1-run.sh`) | liveview / allocrace / updaterace / independent: 4/4 PASS |
| P2P-9 | 9A conserved (FE 3/3, item 2/2); 9B / 9C unchanged (H3 still present, as expected) |
| QUEUE / T2 | 8/8, 4/4 PASS |
| GRID-PHASE | STAGING ×5, OVERLAP 6/6, REPLAY-FRESH, MULTIGRID, STRESS, NESTED: PASS |
| integration | 3/3 PASS |
| LEGACY | migration, no legacy keys, no re-migration, live fail-closed `liveSkipped=1`: PASS |
| kernel | NORMAL, `unauditedPaths=0`, `callbackExceptions=0` |

## 5. Rig / fixture defects found (not regressions)
- **H2 loop geometry, first attempt.** Tunnels on UP / DOWN block the vertical cable connection, so
  the loop split into unpowered grids (`active=true,true,false,false`). It was rebuilt horizontally
  (NORTH / SOUTH tunnels, east cable column). The fixture now refuses to run unless all four loop
  tunnels are active.
- **The first stress run was vacuous for the plain producers.** Off-thread `getBlockEntity(in)` is
  null, so their "transfers" returned before reaching the tunnel; `entries` = 21,624 exposed it.
  Input handlers are now resolved on main, so plain producers call them directly. That run is not
  counted as evidence.
- **P2P-9 9A after the H2 stress reported `accepted=0` ("nothing transferred").** The H2 stress
  had filled the shared 9A cube and chest. The targets were reset and 9A re-run conserved.
- **`legacy-run.sh` unload.** A combined RCON call (`save-all flush` + `forceload remove`)
  intermittently lost the connection after the first command, which left the chunk force-loaded.
  The calls are now separate, and the remove is retried in the wait loop.

## 6. What H2 does not do
- Two threads now pass the recursion guard **correctly**, and they can still mutate a foreign
  region's target incorrectly. That is H3, which P2P-9 re-confirmed.
- H1 CLOSED + H2 CLOSED does **not** close AE2-COMPAT-P2P-001 and does **not** open unpin.
