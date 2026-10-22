# P2P-FORWARDING-FAIL-CLOSED-001 (H3 safety gate) — implementation and evidence

**Status: CLOSED / VERIFIED (owner, 2026-10-23)** as an H3 *safety boundary*. P2P-9 PASS, the
negative control discriminates, focused regression PASS, production-mode verification PASS.
True cross-region forwarding stays OPEN.

| Item | Status |
|---|---|
| P2P-FORWARDING-001 (H3) | **OPEN**: cross-region P2P is **unsupported**, not supported |
| guarantee | unsupported ≠ corruption, ≠ resource loss, ≠ foreign mutation |
| buffered cross-region P2P | not started (separate gate) |

## 1. Mechanism
- **Core** (`72d92a4c7` on the branch; `1b1797756` on the canary branch): a narrow read-only query,
  `net.nestworld.region.NestworldOwnership.isCurrentThreadOwner(Level, BlockPos)`, `CONTRACT = 1`.
  - It uses the same definition as `NestworldOwnershipAssertions`: the region's `owningThread`.
    Outside every region, or on an unmanaged level, the owner is the server thread.
  - A region with no thread (transient) has no owner, so the answer is false.
  - No callback execution, scheduling, mailbox or mutation.
- **Addon** (`glue/P2POwnership`, `CapabilityGuardMixin.nw$ownedNeighbourOnly`). AE2's
  `CapabilityGuard.get` performs the forward as `level.getBlockEntity(facingPos)` of the other tunnel
  end. That call is wrapped:
  - if the current thread does not own `facingPos`, it **does not happen**. AE2 sees no block entity
    and returns its own empty handler: FE 0 accepted / extracted, item stack unchanged / extract
    EMPTY, fluid 0 / empty.
  - Neither the ghost copy nor the live foreign BE is touched. Nothing is thrown.
  - It covers both directions: the input → outputs push, and the output → input pull through
    `getInputCapability`.
- **Contract lookup** is by name, since the addon has no Core compile dependency:

  | Situation | Mode | Behaviour |
  |---|---|---|
  | contract present | `CONTRACT` | refuse cross-owner forwards |
  | NestWorld Core present without the contract (e.g. 47.4.234 islands) | `NO_CONTRACT_REFUSE_ALL` | **every** P2P capability forward is refused: locality cannot be established |
  | no NestWorld | `NO_NESTWORLD` | vanilla |

- **Counters:** `p2pForwardLocalAllowed`, `p2pForwardForeignRejected`,
  `p2pForwardRejectedMainCaller`. Ghost and deep are not distinguishable before the ownership
  query, so one rejection counter is kept plus the main-caller split.
- **Main-thread callers.** The server thread is not the owner of positions inside a region. So a
  forward made on main (border-band or pinned pipe, the H2 fixtures' A thread) into a region target
  is refused. This follows the ownership definition; see open decision §6 Q1.
- **Release hygiene (owner TODO, done).** `ArmorStandMixin` and `RedstoneP2PProbeMixin` are not
  applied at all unless `-Dnestworld.compatAe2.fixtures=true` (`Ae2CompatMixinPlugin.FIXTURE_ONLY`).

## 2. P2P-9 (rig, Core 47.4.307-canary-core-306-ownership, guard ON)

| Case | FE (3/3) | item (2/2) |
|---|---|---|
| 9A SAME-OWNER | **CONSERVED**: accepted 1000, destination +1000, `forwardRejected+0` | **CONSERVED**: 16 / +16 |
| 9B GHOST | **REFUSED**: accepted 0, destination unchanged, `forwardRejected+1` | **REFUSED**: no NPE, stack unchanged, destination unchanged |
| 9C FOREIGN-LIVE | **REFUSED**: accepted 0, owner-thread target unchanged (16000 → 16000), no foreign mutation | **REFUSED**: 160 → 160 |

On the "no detached BE accessed" check: the fixture's own `fixtureProbeView` lookup now runs
**after** the transfer, so it cannot influence the result. The guard replaces AE2's lookup, so with
the guard ON, AE2 performs no `getBlockEntity` for a non-owned target.

**Negative control** (`rig/negp2p_noforwardguard.py`, `P2POwnership.PROTECT=false`), same fixture:
- 9A conserved;
- **9B FE: loss** (1000 accepted / +0) ×3;
- **9B item: NPE** ×2;
- **9C: cross-owner mutation** ×3 FE, ×2 item.

All old failures were reproduced, and the clean jar was rebuilt afterwards.

## 3. Focused regression (guard ON, `rig/regress-h3.sh`, `evidence/h3-regression-*`)

| Suite | Result |
|---|---|
| H1 | 4/4 PASS |
| H2 | H2-1 recursion PASS (now run on the owner thread); H2-2 fe / item / fluid PASS, with B (owner) accepted and `falseRejects+0`, and A (main) **refused by the gate as expected**; H2-3 ×3 PASS (owner thread) |
| P2P-9 | as §2 |
| QUEUE / T2 | 12/12 PASS, exceptions 0 |
| GRID-PHASE | STAGING ×5, OVERLAP 6/6, REPLAY-FRESH, MULTIGRID, STRESS, NESTED: PASS |
| integration | 3/3 PASS, exceptions 0 |
| LEGACY | PASS (live fail-closed `liveSkipped=1`) |
| kernel | NORMAL, `unauditedPaths=0`, `callbackExceptions=0` |

**Fixture change, recorded:** H2-1 and H2-3, and the expectation for H2-2's A, moved to the owner
thread. A forward on main into a region target is now refused by design, so the old main-thread
fixture would have measured the gate, not the depth guard.

## 4. Production-mode verification

Same jar, without `-Dnestworld.compatAe2.fixtures`, with `-Dmixin.debug.export=true`:
- startup gate PASS; manifest ELIGIBLE; kernel NORMAL; `unauditedPaths=0`;
- fixture commands absent (`nwcompatae2 p2p …` → "Incorrect argument");
- exported `CapabilityP2PTunnelPart$CapabilityGuard` contains `nw$ownedNeighbourOnly` (H3) and the
  H2 depth redirects;
- `ArmorStand` and `RedstoneP2PTunnelPart` were loaded but **not transformed** (no export), i.e.
  the fixture-only mixins were not applied.

## 5. Not changed / still open
- **CALLBACK-UNDER-P-001:** OPEN. **REDSTONE-P2P-CALLBACK-001:** CONFIRMED.
  - Redstone and Light P2P do not use `CapabilityGuard`, so this gate does not cover their
    cross-region world updates (`notifyBlocksOfNeighbors`, `markForUpdate`).
  - Documented only, no refactor.
- **Not exercised:** ME P2P (connections only, no capability forwarding).
- **Not exercised:** addon tunnels (ExtendedAE, …). Unknown `P2PTunnelPart` subclasses that extend
  `CapabilityP2PTunnelPart` inherit the gate. Others are a manifest matter (audit Q5); manifest
  enforcement for unknown P2P subclasses is **not implemented yet**.

## 6. Open decisions
- **Q1, main-thread callers: DECIDED (owner, 2026-10-23), keep strict for the canary.** No
  automatic main trust. A later gate may define MAIN_OWNED / BORDER_OWNED / REGION_OWNED.
  Original question: should a forward on main into a region-owned target stay refused
  (current, strict ownership)? Or does the owner want main treated as owner of border-band positions?
  That would need Core to expose border-band membership through the same read-only contract.
- **Q2, provenance: RESOLVED.** Clean rebuild from `1b1797756`: `Git-Dirty: false`, SHA-256
  `d0dbffaf…2f27fc` (readiness doc). History: the first canary Core installer was built with
  `Git-Dirty: true`. The reason is an
  untracked `compat/` directory in the work tree. The Core sources are exactly the canary branch
  commit `1b1797756`. A clean rebuild is possible before any production use if required.
