# nestworld-compat-ae2 — production canary readiness (2026-10-23)

## Verdict: READY for ONE production-like canary instance, under the conditions below

It is **not** ready for a fleet rollout, unpin, or a claim of cross-region P2P support.

## Exact builds

| Artifact | Identity |
|---|---|
| Addon | `nestworld-compat-ae2-0.1.0-all.jar`, built from branch `claude/nestworld-hybrid-arch-invq4h` at **`6d6c7591d`**. SHA-256 `71a5d781e3308cf926123db43ba940fb51c5131ddf1e90184b6c6777beb233be` |
| Core | `forge-1.20.1-47.4.307-canary-core-306-ownership-installer.jar`, **clean rebuild** (owner, 2026-10-23): manifest `Git-Commit: 1b179775`, `Git-Branch: canary/core-306-ownership`, **`Git-Dirty: false`**. SHA-256 **`d0dbffafed320798e5b45e70298c35c1f6c8b09e6a63e2acc6044c429b2f27fc`** |
| Core source | **Core 306 (`7803c678`) + exactly one commit**: `NestworldOwnership` (canary branch `canary/core-306-ownership`, `1b1797756` = cherry-pick of `72d92a4c7`) |
| Why not HEAD Core | HEAD contains the FastTransition work, which also changes the login handshake loop for everyone. The canary keeps a single Core delta |
| Staged copies | `/root/canary-artifacts/ae2-canary-2026-10-23/`: both jars, `canary-baseline.sh`, `SHA256SUMS`. The earlier dirty build (`246716c0…`) was moved to `superseded/`; **do not deploy it** |
| Clean-binary smoke | rig: clean install (version dir removed first, manifest verified `Git-Dirty: false`), then P2P-9: 9A conserved, 9B / 9C REFUSED, gate PASS, `mode=CONTRACT`, kernel NORMAL |
| Branches pushed | `claude/nestworld-hybrid-arch-invq4h` @ `76804ea34`+; `canary/core-306-ownership` @ `1b1797756` |

**Provenance:** source commit `1b1797756` == the binary's manifest (`Git-Dirty: false`) == the
recorded SHA-256 `d0dbffaf…2f27fc`.

## Mods / versions (audited matrix only)
- **Audited:** AE2 15.4.10 (manifest sha256-pinned), GuideME, Pipez, Mekanism, as on the rig.
- **Not audited:** MegaCells, ExtendedAE and other AE2 addons.
  - An unknown `IGridServiceProvider` or storage / crafting provider makes the manifest
    NOT ELIGIBLE → DEGRADED, with pins kept. That is the designed fail-closed state, not a crash.
  - Unknown P2P tunnel subclasses outside `CapabilityP2PTunnelPart` are not yet manifest-checked
    (audit Q5, not implemented).

## Policy for the canary

| Setting | Value |
|---|---|
| addon | ON (no `-Dnestworld.compatAe2.fixtures`, no `mixin.debug` flags) |
| AE2 pin policy | **KEEP** as on the target today |
| selective unpin | OFF |
| full unpin | OFF |
| cross-region P2P (FE / item / fluid) | **fail-closed**: refused, 0 transferred |
| Core / addon / pin | change **only** Core → 307-canary and add the addon; nothing else in the same step |

## Unsupported / known behaviour (tell the server owner)
1. **Cross-region P2P is unsupported.** An FE / item / fluid P2P whose other end faces a block in
   another region (or dimension) transfers **nothing** (counted, one WARN). Same-region P2P works
   unchanged. Region boundaries move with split / merge, so which P2P links are affected can
   change over time.
2. **Main-thread callers are refused too.** A forward made on main, i.e. from a pipe ticking in
   the border band or a pinned machine, into a region-owned target is refused.
   - **Q1 decided (owner, 2026-10-23): keep strict.** Main is not the owner of a free-running
     region's positions, so the forward is refused. There is no "main is trusted everywhere".
   - A later separate gate may define MAIN_OWNED / BORDER_OWNED / REGION_OWNED.
   - **`p2pForwardRejectedMainCaller > 0` on the canary is not a failure.** It records functional
     degradation.
3. **Redstone / Light P2P** across regions still perform their synchronous world updates
   (CALLBACK-UNDER-P-001 / REDSTONE-P2P-CALLBACK-001 open). Not addressed by this canary.
4. **If the addon's startup gate FAILS** (an AE2 jar different from the audited one), **no**
   addon mixin applies, including the P2P gate. AE2 then runs unmodified with the old hazards.
   Canary acceptance requires gate PASS.
5. **Core without `NestworldOwnership`** (e.g. islands on 47.4.234): the addon runs in
   `NO_CONTRACT_REFUSE_ALL`, so **every** P2P capability forward is refused. It is safe but a
   large functional regression. Do not use the addon on old Core except deliberately.

## Known open gates

| Gate | Status |
|---|---|
| P2P-FORWARDING-001 (real cross-region P2P) | OPEN |
| CALLBACK-UNDER-P-001 | OPEN |
| AE2-COMPAT-P2P-001 | OPEN |
| SOLARFLUX-COMPAT-001 | OPEN |
| LEGACY-ORIENTATION-LIVE | fail-closed |
| unpin | BLOCKED |

Other mod races seen in production (Draconic `deSpawnedMobs`, Mekanism `NetworkAcceptorCache`) are
unrelated to the addon (`docs/ops/OPS-TRIAGE-2026-10-23-tick-skips-crashes-dumps.md`).

## Canary checklist

**Baseline before the first start** (read-only; fill in RCON to include live status):
```
./canary-baseline.sh <server dir> baseline-pre-start       # before any change (old Core / mods)
# ... deploy ...
RCON_PORT=<p> RCON_PASS=<pw> ./canary-baseline.sh <server dir> t0-after-start
RCON_PORT=<p> RCON_PASS=<pw> ./canary-baseline.sh <server dir> t30min
RCON_PORT=<p> RCON_PASS=<pw> ./canary-baseline.sh <server dir> t2h
```
Records:
- Core universal-jar SHA, commit and dirty flag, and whether the `NestworldOwnership` contract is
  present; the active Core from `run.sh`;
- addon and every mod jar SHA, with AE2 / MegaCells / ExtendedAE / Mekanism / SolarFlux / Pipez
  called out;
- JVM args (freeRunning flag; warns if the fixtures flag is present);
- pin files (`nestworld-pins`, `-be-pins`, `-pinned-mods`, `-be-cascade-safe`);
- with RCON: `nwcompatae2 stats`, `nestworld status` (regions), `nestworld pins`, forceloads;
- log signal counts and recent crash reports.

**Deploy** (owner-run; empty server; world backup first):
```
java -jar forge-1.20.1-47.4.307-canary-core-306-ownership-installer.jar --installServer <server dir>
cp nestworld-compat-ae2-0.1.0-all.jar <server dir>/mods/
# recommended for evidence: add -XX:-OmitStackTraceInFastThrow to user_jvm_args.txt (owner decision)
```

**Startup log must show:**
```
[nestworld-compat-ae2] startup gate PASS (...) ; compatibility manifest ELIGIBLE
```
and, on the first P2P use, `P2P forwarding ownership: mode=CONTRACT`. `NO_CONTRACT_REFUSE_ALL`
means the wrong Core.

**Every interval** (30 min → 2 h → longer):
```
/nwcompatae2 stats
  mode=NORMAL                 (DEGRADED = stop, classify)
  manifestEligible=true
  unauditedPaths=0
  callbackExceptions=0
  mainAffinityViolation=0
  pWriterStarvationWarning=0, pNestedUnresolved=0
  p2pInputDisplaced=0, p2pDepthUnderflow=0, p2pDepthFalseReject=0
  p2pForwardForeignRejected / p2pForwardRejectedMainCaller   (informational: how many P2P forwards are refused)
/nestworld status
```
Logs and crash reports: grep for `appeng`, `nestworld-compat-ae2`, `ConcurrentModification`,
`NullPointerException`, `Cannot modify energy requesters`, `TickHandler.processQueue`, and watchdog.

**Acceptance per interval:**
- 0 concurrency exceptions with AE2 frames;
- 0 NPE from P2P targets;
- 0 `EnergyService.addRequester` ISE (seen 26× on airis 234 without the addon);
- 0 processQueue spin;
- 0 unexpected DEGRADED;
- 0 unaudited paths;
- no watchdog hang attributable to AE2 or the addon;
- TPS comparable to the pre-canary baseline.

Non-AE2 mod races (Draconic `deSpawnedMobs`, Mekanism `NetworkAcceptorCache`, PlayerSync) are
separate incident classes. They are not counted as addon failures unless there is evidence of a
causal link.

**Order (owner):** deploy ONE instance → backup → baseline → 30 min → if clean, 2 h → STOP and
review the evidence.
