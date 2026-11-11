# WP3-B evidence and continuation (2026-11-10)

Scope: bus transit only. AE2-COMPAT-PRODUCTION-VERIFIED remains closed. Production pins and MAIN ownership of AE2 network/storage stay unchanged. The owner subsequently explicitly requested live activation before further tests; see the live continuation below. This report does not certify a production acceptance PASS.

## 1. B1 handover

Existing run `/root/airis-2026-10-25/evidence-be-ready/b10-U-lane-on-run1` finished at 14:22, before this continuation: `alive=1 seconds=1851 stopSeconds=47 crashNew=0`; pins restored byte-identically to live. No rerun or new B1 correctness investigation is needed for WP3-B. This was a TEST-ONLY narrow-unpin run, not evidence that production pins can be removed. Its final item balance is -42; the run must not be relabelled a clean accounting PASS. Attribution of that older fixture discrepancy is outside this task.

## 2. Export flow map

Implementation: `BusTransit.onBusWork` (bus interception), `prepareExport`, `enqueueRegion`, `regionProbe`, `plan`, `regionPush`, `publish`, `onServerTickEnd`, `networkStep`, `finish`. Tick numbers below describe a healthy, normally serviced mailbox, not a guaranteed deadline under overload.

### Previous two-hop path (committed baseline)

| Phase | Thread / domain | Typical tick | Boundary and reason |
|---|---|---|---|
| Bus tick; snapshot configured keys, AE2 stock/energy SIMULATE | Server thread / AE2 MAIN | N | AE2 owns scheduling, network and energy. No extraction yet. |
| Target capacity SIMULATE (`regionProbe`) | Target inventory's owning thread / neighbour domain | N+1 | MAIN → Core domain mailbox; target capability may only be accessed in its domain. |
| Publish probe; extract from AE2 (`plan`) | Server thread / AE2 MAIN | N+1 or N+2 | Per-grid result inbox → MAIN commit pass. Target domain cannot extract from AE2. |
| Push extracted resources (`regionPush`) | Target inventory's owning thread / neighbour domain | N+2 or N+3 | Second MAIN → target mailbox. Probe did not reserve inventory capacity. |
| Ack; return leftovers; finish and modulation | Server thread / AE2 MAIN | N+2..N+4 | Per-grid result inbox → MAIN; network returns/energy/modulation remain MAIN-owned. |

Rig run5 observed export p100 3–4 ticks. The extra target SIMULATE round trip is the removable hop. No synchronous region→MAIN wait is necessary.

### Existing uncommitted short path (Claude dev9; under review)

| Phase | Thread / domain | Typical tick | Boundary and reason |
|---|---|---|---|
| Bus tick; stock/energy SIMULATE; extract bounded resources into held record (`exportRound`) | Server thread / AE2 MAIN | N | Network is accessed only on MAIN; resources now belong to the transit record. |
| Push held resources (`regionPush`) | Target inventory's owning thread / neighbour domain | N+1 | One MAIN → target mailbox, existing Core lifecycle dispatch plus ticking/domain revalidation. No preceding capacity probe. |
| Publish ack; charge accepted units; return leftovers; finish | Server thread / AE2 MAIN | N+1 or N+2 | Per-grid inbox and fair MAIN scheduler; no global FIFO/lock. |

Smallest change: optimistic bounded export extraction on MAIN and one target MODULATE hop; return unaccepted resources on MAIN. Existing uncommitted implementation also attempts remaining configured slots after partial acceptance; those continuation rounds and energy semantics require targeted validation, not an unconditional ≤2 claim.

Existing dev9 evidence `/root/airis-2026-10-25/evidence-be-ready/b4-runB1/out.txt`: export p100 2 in 600- and 1200-tick windows; 32 items/op and identical scheduled throughput OFF/ON; zero final outstanding/missed/domain failures. Single cobblestone filter only: not evidence for multiple keys, low energy, partial-slot continuation or production benefit.

## 3. Optimistic import accounting

Accepted contract remains `extracted = insertedToAE2 + returnedToSource + accountedFallback + held/inflight` (held/inflight must be zero at final quiescence). No redesign is planned.

Dev9 fallback run logged `droppedLedger=384`, but station 2 physical count found only 160 loose items and conservation -224. This is unresolved, not proof of either actual loss or a clean PASS. The fixture places inventories at Y=300 and counts entities only within Y=297..304; falling drops can leave its counting volume. Reproduction needs a contained catch platform before judging the drop path. Also inspect actual entity insertion acceptance before treating the ledger as proof of delivery.

## Remaining ordered gates

1. Targeted short-export and contained import-fallback evidence.
2. Production-pack A/B with unchanged pins/topology and measured MAIN attribution; existing barrel numbers are not production evidence.
3. Targeted MasterfulMachinery integration.
4. Final verdict with measured MAIN delta, MSPT, throughput and transit overhead. Until all gates pass: DO NOT DEPLOY.

## Continuation: contained rig run

`/root/airis-2026-10-25/evidence-be-ready/codex-wp3b-short-export-contained-20261110`, tested addon SHA256 `a427764ad9cd315cdee00374d412e90c5d33914740c0ca8b8eb64315705898dc` (before fixture performance telemetry was added). `check-run-b.py` returns PASS.

| Check | Observed |
|---|---|
| Export 600 ticks OFF / ON | 960 ops, 30,720 items in both, 32 items/op |
| Export ON 1200 ticks | 1920 ops, 61,440 items, 32 items/op |
| Export latency ON | p50/p95/p99/p100 = 1/1/1/1 in both windows |
| Quiescent export count / partial-fill count | conservation 0 / 0 |
| Import refusal, source full, AE2 refuses, timeout fallback | network 6912 + physical drops 512 = created 7424; held 0; conservation 0; drop ledger 512 |
| Grid gone + bus broken; recovery ON / rebuilt | conservation 0 / 0 |
| Recovery OFF negative control | conservation -512, exactly matching `voidedHeld=512` |
| Final missed / commit failures / region domain failures / crash delta | 0 / 0 / 0 / 0 |
| Cleanup | rig jar, arguments and AE2 channel config restored; own lock released |

The contained rerun supports the falling-entity counting explanation for the earlier -224 discrepancy: with the same bus logic, physically recovered fallback items now equal the ledger. This validates the exercised path, not every exception/restart permutation of spawning.

Rig bus-only MAIN cost (inline + transit op + commit): OFF 0.085248 ms/tick; ON 0.062693 ms/tick in the 600-tick window (delta -0.022555 ms/tick, -26.46%). ON commit overhead 0.024790 ms/tick; region work 0.044150 ms/tick. These are cheap-barrel fixture measurements, **not production benefit**. The subsequent production-copy benchmark must decide that independently.

Performance instrumentation is fixture-only: complete previous-tick durations from vanilla's existing `tickTimes` ring, Server-thread CPU time, actual accepted item counts in managed inline storage strategies versus transit item commits. The wrappers return AE2's original values unchanged; no new routing, scheduling, topology or pin policy is introduced. Non-item transfers are excluded from item throughput counts.

## Production-pack A/B (completed; diagnostic only)

Evidence `/root/airis-2026-10-25/evidence-be-ready/codex-wp3b-pack-ab-20261110`; addon SHA256 `85700cd5702b93dde39b5b9b5f77e66c91ac559d2034295019dcece6cf78a648`, AE2 15.4.11 SHA256 `f2ff9ee5122b037d4e93537425ab9fbe49860fc9c3977db13b20a50ca7d30f9e`. Same JVM/world/topology, unchanged production pin files at entry, four OFF/ON/OFF/ON natural-traffic windows, 180 seconds of checked waits each (command/sampling overhead makes wall duration longer); startup warmup 120 seconds. The offline world census found 74 Sophisticated chests, 1842 AE2 cable-bus BEs and the pack's real MM machines/ports. Natural production recipes are not a stationary, rate-controlled workload: report observed throughput and do not infer causation from raw MSPT changes.

| Phase | Samples | MAIN CPU ms/tick | MSPT avg / p50 / p95 / p99 | Inline import / export items | Transit import / export items |
|---|---:|---:|---|---:|---:|
| 1 OFF | 1459 | 129.427316 | 127.568879 / 116.844054 / 178.024866 / 260.311043 | 35500 / 52563 | 0 / 0 |
| 2 ON | 1544 | 122.398319 | 119.922095 / 112.157318 / 151.547593 / 289.127117 | 22198 / 48619 | 0 / 0 |
| 3 OFF | 1551 | 122.343658 | 118.709198 / 112.458247 / 148.378468 / 241.971086 | 37049 / 47759 | 0 / 0 |
| 4 ON | 1541 | 120.134819 | 118.932394 / 111.505125 / 144.846304 / 239.220797 | 31206 / 47537 | 0 / 0 |

**Confirmed gate failure:** flag and runtime switch are ON, but `transitImport=0`, `transitExport=0`, network transfer ledger and region transit time remain zero. `inline.strategy` is nonzero; no bus reaches the neighbour-domain eligibility test. Exact flow: `onBusWork` → `strategiesAllowed` rejects a strategy in the facade → `inlineStrategy` → existing neighbour affinity + original AE2 operation on MAIN. The allowlist checks every strategy in a facade, including strategies registered by other pack mods. Rejected-class telemetry was added for the next targeted MM run; no allowlist expansion or bypass has been made.

The first-pair MAIN CPU delta is -7.028997 ms/tick (-5.43%), but **it is not WP3-B benefit**: no work was offloaded, and import/export traffic rates changed. Keep the raw numbers as observations, not acceptance evidence. No production MAIN reduction is demonstrated by this candidate.

All four windows finished; pi-smoke baseline jar, arguments and pin files were restored (`restored ...: 1`). Machine-readable diagnostic and contained-rig results are in `docs/compat/evidence/wp3b-20261110/`. Per the owner's revised instructions, the copy run is not the final performance verdict; that requires live measurements.

## Item-only strategy gate correction

The production facade includes AppliedFlux FE and IFSoulsDisks soul strategies absent from the original whole-facade allowlist. A targeted bytecode check found: FE/soul export `push(AEItemKey,...)` returns zero before accessing the neighbour; FE/soul import `transfer` checks `isKeyTypeEnabled(nonItemType)` before accessing the neighbour. The additional Soulplied exporter is an unconditional zero-return stub.

`ItemNoopStrategies` permits these exact class-resource SHA256 values only for configured item exports or positive item-filtered imports. Unknown bytecode, empty import filters, inverted filters and mixed resource types still fall back to MAIN. This does not authorize FE/soul resource transfers. Existing network ownership and Core pin semantics are unchanged. Class hashes are embedded in the helper; audited jar SHA256 values: AppliedFlux `7673b0e44ee5f247fe224c09b8178f500a01a96e7d07baa77a4a5a110462eaea`, IFSoulsDisks `37dccf51580f8710c68645c55387f8e529c02c0f747b913d056626e58f54f32f`, Soulplied `998aa554c3de8180373a0edf6e29a0e3b3ff12a7daf35a19f529b0829c95eb10`.

## Live continuation (owner-authorized, acceptance pending)

- 17:57: owner requested `online-mode=false`; applied with backup, successful startup 17:59:33. `enforce-secure-profile` left unchanged (server source conditions enforcement on onlineMode).
- Owner explicitly excluded player `Igor` from the player gate. The new gate still refuses other players, malformed counts and RCON errors; five parser cases passed.
- 18:09: owner explicitly requested enabling WP3-B first and running tests afterwards. Enabled it in the existing live jar SHA256 `5ea76d1919a81f888dc015a912f91f5c7c327e504d58e9c8166344bb50c07572`, no jar replacement. Startup 18:11:58; runtime `flag=true active=true`, but 1033 operations included zero transit and 767 strategy fallbacks. Snapshot overall TPS 19.192 / MSPT avg 51.99 is not proof of improvement.
- 18:15: after the owner requested actual offload and said “то роби”, installed the item-only gate candidate SHA256 `1f6d4a57133604d4931a9a9250e8ed5ef31302b3773a4efb048114ec08dd5c86` with transit true and fixtures false. The Core, other performance flags and production pin files were not changed. Startup/actual work and safety counters must be checked before claiming any effect.
- Rollback: `/root/project-infinity/wp3b-backup-20261110-181540/` contains the previous jar, original arguments, prepared arguments with transit disabled, and policy pin hashes. `online-mode=false` remains enabled as requested.
- MM fixture is a real cloned 2x2x2 Fire Attuned controller/port machine, recipe 128 lava crystals → 128 infused crystals, OFF/ON and held-export unload/reload cases. Earlier queued old-jar runs were cancelled because their gate could not exercise transit; pi-smoke cleanup confirmed `restored=1`. The item-only candidate run is queued separately. It is not yet a PASS.
