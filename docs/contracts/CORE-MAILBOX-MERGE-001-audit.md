# CORE-MAILBOX-MERGE-001 — region mailbox lifecycle audit (research gate, no code changes)

**Fix: CLOSED / VERIFIED 2026-10-02 — see `CORE-MAILBOX-MERGE-001-fix-spec.md` (contract, implementation, evidence).**

**Status at audit time: AUDIT COMPLETE (2026-10-02) — no code changed; fix not started. §8–§11 reviewed and approved by
the architecture owner 2026-10-02; the fix contract (linearization order, old-worker exclusion,
join-timeout policy, accounting invariant) is specified separately in
`CORE-MAILBOX-MERGE-001-fix-spec.md` and refines §10 where they differ.** Goal set by the architecture owner (2026-10-02): before any
fix, prove on the current code (1) every mailbox producer, (2) every consumer, (3) every region
lifecycle transition (split, merge, retirement, shutdown), and (4) the exact linearization point of
"accepted". Only then write the fix ТЗ.

Target invariant (owner):
> After a successful enqueue into a region mailbox, the message cannot become unreachable merely
> because that region was split, merged or retired.

Two races to cover (owner):
```
ENQUEUE RACE     resolve owner → retirement → enqueue           → must not enqueue into a dead destination
RETIREMENT RACE  enqueue accepted → retirement                  → accepted work must be drained / transferred / re-resolved
```

Evidence to beat (API-1B.7 `stress-churn`): 171 missing terminal outcomes == 171 messages stranded
in retired regions' mailboxes.

---

## 1. Storage: what a "mailbox" is today

Per `WorldRegion` (`WorldRegion.java`):

| Structure | Lines | Role |
|---|---|---|
| `nestworldMailbox` — `ConcurrentLinkedQueue<RegionMessage<?>>` | 364 | inbound queue, all message types mixed |
| `nestworldMailboxDepth` — `AtomicInteger` | 501 | O(1) depth for backpressure, maintained beside every add/remove |
| `nestworldOutboundPending` — `ConcurrentLinkedQueue<(destination WorldRegion, message)>` | 513-516 | **sender-side** backpressure queue: messages this region wanted to send but held back because the destination was over `MAILBOX_BACKPRESSURE_THRESHOLD` |

Entry points: `nestworldPostMessage` (378, records `MailboxAudit` "sent"), `nestworldRequeueMessage`
(389, no audit event), `nestworldSendOrQueue` (540, post or hold at sender),
`nestworldFlushOutboundPending` (559, retried from the sender's own tick).

**Finding 1.1 — no lifecycle state.** Neither the mailbox nor `WorldRegion` carries any
active/retiring/retired state. A `WorldRegion` object removed from the grid keeps accepting
`add()` forever; nothing distinguishes it from a live one.

**Finding 1.2 — a second, independent loss surface: the sender-side pending queue.** It stores a
`WorldRegion` *object* as destination, resolved before the hold. Two additional failure shapes,
both outside the original finding: (a) the SENDER region retires with entries still pending — the
queue lives only on the retired object and is flushed only from that region's own tick; (b) the
DESTINATION retires while an entry is pending — the flush later `nestworldRequeueMessage`s it into
the dead mailbox.

## 2. Message types and consumers

| Type | Drained by | Where |
|---|---|---|
| `ENTITY_TRANSFER` | main thread, barrier | `BoundaryEntityTransfer.java:138` |
| `EXPLOSION_APPLY`, `BLOCK_WRITE` | main thread, barrier, batched, over the region list | `NestworldDimensionRegion.java:1040-1041` → `:778` |
| `ENTITY_IMPACT`, `ENTITY_PUSH`, `ENTITY_MUTATE` | the owning region's own `RegionThread` | `RegionThread.java:761, 794, 828` |
| `PLAYER_INPUT`, `PLAYER_ATTACK`, `PLAYER_BLOCK_INTERACTION`, `PLAYER_USE_ITEM`, `PLAYER_USE_ITEM_ON_BLOCK`, `PLAYER_USE_CONTROL`, `PLAYER_ENTITY_INTERACT` | the owning region's own `RegionThread` | `RegionThread.java:849-960` |
| `WIRE_UPDATE` | not a mailbox type in practice — produced only into `NestworldDimensionRegion.deferredWireUpdates` (`:308`) | out of scope |

Consequence: a message's reachability depends on TWO different things — for main-thread types, on
the region still being in the list the main thread iterates; for region-thread types, on that
region's thread still running and draining.

## 3. Producers (27 sites)

All follow the same shape: **resolve a destination `WorldRegion` object, then post to it later**
(the gap is the ENQUEUE RACE window).

| Kind | Sites |
|---|---|
| main/any-thread `owner.nestworldPostMessage` (no sender region) | `EntityMutationDispatcher:120`, `EntityMutationHelper:94`, `PlayerInputDispatcher:66`, `PlayerAttackDispatcher:64`, `PlayerUseControlDispatcher:60`, `PlayerUseItemDispatcher:72`, `PlayerUseItemOnBlockDispatcher:109`, `PlayerInteractionDispatcher:82`, `EntityInteractionDispatcher:77`, `BoundaryEntityTransfer:239` |
| region-thread `self.nestworldSendOrQueue(dest, …)` | `EntityMutationDispatcher:105` (dispatch) and `:169` (applyQueued REROUTE), `EntityMutationHelper:41`, `EntityPushHelper:60`, `PlayerInputDispatcher:102`, `PlayerAttackDispatcher:99`, `PlayerUseControlDispatcher:102` (REROUTE), `NestworldDimensionRegion:817` (block write); patched vanilla: `Ravager:262`, `LivingEntity:2778`, `Explosion:323, 358, 477` |
| requeue of already-accepted messages | `WorldRegion:568` (pending flush), `NestworldDimensionRegion:748` (cascade-guard lock-timeout retry) |

## 4. Lifecycle transitions — how a region stops being a destination

There is no explicit lifecycle. "Retirement" is the combined effect of two steps, both inside
`RegionSplitManager.nestworldWithRegionLocks(...)` on the main thread, at tick phase 6
(`splitManager.onTick()`):

| Transition | Code | What happens to the old `WorldRegion` |
|---|---|---|
| split | `RegionSplitManager.doSplit` (`:491`): `tree.split(...)` → `pool.remove(region)` → owned entities reassigned to the two children by snapshot position → `pool.spawn(children)` | removed from the grid's region list (owner resolution no longer returns it); its `RegionThread` is told to stop; **both its queues are left as they are** |
| merge | `RegionSplitManager.doMerge` (`:998`): `tree.merge(a, b)` → `pool.remove(a)`, `pool.remove(b)` → `pool.spawn(merged)` → owned entity IDs copied into the merged region | same, for both `a` and `b` |
| thread stop | `RegionThreadPool.remove` (`:190`) → `RegionThread.shutdown()` (`:236`): `running = false; notifyAll()` | no drain, no hand-off (also the known #15 shutdown gap) |
| server stop | `NestworldDimensionRegion.shutdown` → `thread.shutdown()` per thread | no drain (#15) |

Callers of split/merge: the adaptive heuristic (`splitManager.onTick`), the autosplit test hook
(`NestworldDimensionRegion:638-642`), RCON `/nestworld split|merge` (`NestworldCommand:3347, 3375`),
and test fixtures.

**Finding 4.1 — the split/merge locks do not exclude producers.** `nestworldWithRegionLocks` takes
the `chunkLock` of the affected regions and their neighbours. No mailbox producer takes a
`chunkLock` to post. Its own javadoc states the assumption: "always uncontended (every region
thread is already parked) … becomes load-bearing only once regions go free-running".

**Finding 4.2 — owner re-resolution by identity already works.** `WorldGrid.findOwningRegion(uuid)`
(`:74`) scans the grid's CURRENT region list, so after split/merge it returns the child/merged
region; chunk-keyed lookup (`getRegionForChunk`) is likewise current. Re-resolving a message by its
target identity is therefore available today — the defect is that nothing ever does it for messages
already sitting in a retired region's queues.

## 5. Tick phase order — why the dominant loss needs no race at all

One dimension tick, barrier mode (`NestworldDimensionRegion.tick`, `:929-1062`):
```
1   level.tick(...)            main-thread producers post into owner mailboxes (packet handlers,
                               commands, Mod API main-thread backends) — drained by those regions NEXT tick
3   checkAndReassign()         ENTITY_TRANSFER posted AND drained in the same phase (not exposed)
4   pool.tickAllRegions()      each region thread drains ITS types at the start of its local tick
                               (RegionThread.runOneTick :558-568), then may send cross-region —
                               those land in mailboxes drained NEXT tick (or at 4b/4c for main types)
4b/4c  main drains EXPLOSION_APPLY / BLOCK_WRITE over grid.getAllRegions(), deadline-budgeted —
                               leftovers stay queued for the next tick
6   splitManager.onTick()      split/merge retires regions
```
**Finding 5.1 — deterministic RETIREMENT loss.** Any message accepted into region R's mailbox after
R's own drain in tick N (steps 1, 4, 4b/4c leftovers) and before R's drain in tick N+1 is stranded
if R is retired at step 6 of tick N. No concurrency is required; it follows from the phase order
alone. This is the dominant path in the API-1B.7 churn result.

**Finding 5.2 — ENQUEUE race, concurrent form.** A producer that resolves `owner = R` and posts
later can lose the message if R is retired in between. Under barrier mode this needs a producer
running concurrently with main-thread phase 6: any non-main, non-region thread (the Mod API facades
are callable from any thread; `EntityMutationDispatcher.dispatch` resolves and posts on the caller's
thread), or region threads under free-running regions. Live trace: API-1B.7 opId `2000000004`.

**Finding 5.3 — ENQUEUE race, delayed form (sender-side pending queue).** `nestworldOutboundPending`
stores a resolved destination object across ticks; the flush later requeues into it even if it was
retired meanwhile. No concurrency needed.

## 6. Where "accepted" is linearized today

`accepted` = the `ConcurrentLinkedQueue.add` in `nestworldPostMessage` / `nestworldRequeueMessage`
(or the `add` into the sender's `nestworldOutboundPending`). `consumed` = the `it.remove()` inside
a drain. `CLQ.remove` of a given node succeeds for exactly one caller — the only exactly-once
primitive the current code already has, and the natural ownership token for a fix.

Loss = accepted but never removed. There is no state that makes `add` fail on a dead destination,
and nothing ever removes from a retired region's queues.

`MailboxAudit` (`recordSent` before the add, `recordApplied`, `inFlight` map, `logStaleEntries`)
can already observe accepted-but-never-applied messages when enabled; it records the destination
object at send time, so a message moved to a successor must be re-addressed explicitly or it would
be counted as misrouted.

## 7. Deterministic reproduction of Finding 5.1 (no concurrency possible)

Isolated scratch NestWorld (build 47.4.241). A datapack function executes its commands back to back
inside ONE main-thread task, so no thread interleaving can occur between them.

| Function | Commands | Phase trace of the enqueued op | Outcome |
|---|---|---|---|
| `nwaudit:control` | `testmutate setvelocity <stand> 2 0 0` | opId 109: `SUBMIT → OWNER_RESOLVED owner=1 → ENQUEUE to=1 → APPLY (NestWorld-Region-1) → TERMINAL` | delivered |
| `nwaudit:retire` | same, then `nestworld split 1` | opId 111: `SUBMIT → OWNER_RESOLVED owner=1 → ENQUEUE to=1`; 6 ms later, same task: `Split Region{id=1} -> [2, 3]`; afterwards **no APPLY, no TERMINAL, ever** (checked after 10 s) | stranded; armor stand `Motion` stays `[0,0,0]` (2.0 never applied); region 1 gone, children #2/#3 live |

This proves Finding 5.1 by construction: a message accepted into a live region's mailbox is lost
when that region is retired before its next drain, with no race involved. It is also a cheap,
deterministic regression fixture for the fix (it must deliver after the fix, every time).

Side finding while setting this up (outside this gate, recorded only): **`/reload` fails on
NestWorld.** Every reload fires `RegisterCommandsEvent` again; MODULE-1 performs module discovery in
`NestworldRegionSystem.onRegisterCommands` without idempotence, the second discovery throws
`ModuleId already discovered: example-module`, and Minecraft rolls the reload back ("Reload
failed; keeping old data"). Data packs, functions and anything else that needs `/reload` are
affected. Needs its own gate (MODULE-1 lifecycle).

## 8. Complete loss catalogue

| # | Shape | Needs concurrency? | Evidence |
|---|---|---|---|
| L1 | accepted into R's inbound mailbox after R's drain, R retired before its next drain (split or merge) | **no** (phase order) | §7 opId 111; API-1B.7 churn 171/171; OFFLINE-ROUTED run 1 (split) |
| L2 | producer resolved R, R retired, producer posts to R (ENQUEUE race) | yes — off-tick threads, or free-running regions | API-1B.7 D run 1, opId 2000000004 (merge) |
| L3 | sender region retired with entries in its `nestworldOutboundPending` | no | code (§1, Finding 1.2) — not yet reproduced |
| L4 | pending entry whose destination was retired; flush requeues into the dead mailbox | no | code — not yet reproduced |
| L5 | main-drained types (`EXPLOSION_APPLY`, `BLOCK_WRITE`) left over by the 4b/4c deadline budget, region retired at step 6 | no | code — same mechanism as L1 |
| L6 | server stop with queued messages (#15) | no | `RACE_CLASSIFICATION` #15 |

Every one of these ends the same way: an accepted message with no terminal outcome and no log line.

## 9. Fix options (owner's three, evaluated against the catalogue)

| Option | L1 | L2 | L3/L4 | Notes |
|---|---|---|---|---|
| A. atomic close + drain/transfer at retirement | ✅ | ❌ alone — a post after the drain lands in the closed queue | partly (needs pending queues too) | needed, not sufficient |
| B. retired mailbox forwards to "the successor" | ✅ merge; ❌ split if forwarding to one region | ✅ if every post to a retired region forwards | ✅ if pending flush also forwards | a split has TWO successors and an explosion batch can span both — forwarding must re-resolve **per message by target identity**, never to a fixed successor; chains across generations must re-resolve from the current grid, not hop |
| C. enqueue protocol with state validation + retry | ❌ alone — messages already accepted before retirement still strand | ✅ | ✅ for L4 if the flush uses it | needed, not sufficient |

Conclusion: the two guarantees the owner named are both required and map onto **C + A with
per-message re-resolution** (B is C+A packaged inside the mailbox object, provided it re-resolves
instead of forwarding to a fixed successor).

## 10. Proposed protocol (for the fix ТЗ — not implemented)

State per mailbox: `ACTIVE → RETIRED` (one-way), owned by the region object.

**Accepted (linearization point):** the message is inserted into a mailbox that was ACTIVE at insert
and is either still ACTIVE when the producer re-checks, or the message has been taken by the
retirement drain. Ownership of a message is decided by the existing exactly-once primitive:
`ConcurrentLinkedQueue.remove(node)` succeeds for exactly one caller.

```
deliver(msg):                                   retire(R)   [main thread, inside split/merge,
  loop:                                                       AFTER R's thread has quiesced and
    R = resolve(msg.target)  // UUID or chunk                 AFTER the grid shows the successors]
    if R == null → TERMINAL TARGET_NOT_FOUND       R.state = RETIRED           (publish first)
    if R.state == RETIRED → continue               for each m taken from R.mailbox (remove):
    R.queue.add(msg)                                   deliver(m)              (re-resolve each)
    if R.state == ACTIVE → accepted, done          for each p taken from R.outboundPending:
    if R.queue.remove(msg) → continue (we own it)      deliver(p.msg)
    else → accepted (the retirement drain took it and re-delivers it)
```
- ENQUEUE race (L2): a producer that lost the race to retirement either re-resolves itself
  (`remove` succeeded) or knows the retirement drain owns the message (`remove` failed). Exactly
  once either way; no lock on the hot path.
- RETIREMENT race (L1, L5): the drain re-delivers every message accepted before retirement by its
  **target identity** — UUID types via `findOwningRegion`, chunk types via `getRegionForChunk`;
  an `EXPLOSION_APPLY` batch whose positions now span two children is split per child.
- Sender-side pending (L3, L4): retirement drains the sender's pending queue through `deliver`; the
  pending flush calls `deliver` instead of `requeue` into a stored object, so a stale destination
  re-resolves.
- Ordering constraint: the old region thread must have stopped (joined, not just
  `running=false`) before the drain, otherwise it could still apply a message on a region it no
  longer owns — not every message type re-checks ownership at apply time.
- Observability: re-resolution to "nothing" becomes an explicit TERMINAL outcome; `MailboxAudit`
  entries are re-addressed when a message moves, so they are not counted as misrouted.
- Bounded: each `deliver` loop iteration requires the resolved region to have been retired in
  between; retirements are rare main-thread events, so the loop is bounded in practice — the ТЗ
  should still cap it and fail loudly.
- Shutdown (L6) can reuse the same drain path with "no successor" → explicit TERMINAL, which also
  closes the observability half of #15.

## 11. Regression plan for the fix

1. §7 deterministic fixture: `retire` must deliver (APPLY + TERMINAL) every time.
2. API-1B.7 `stress-churn` unchanged: required `messagesStrandedInRetiredMailboxes=0`,
   `MISSING_TERMINAL=0`, and still `wrongOwnerMutation=0 duplicateApply=0 staleIntentApply=0
   callerObjectLeak=0 wrongIncarnationMutation=0`, bounded reroute.
3. New deterministic fixtures for L2 (off-tick producer held between resolve and post across a
   split — barrier A hold), L3/L4 (backpressure-held sends across sender/destination retirement),
   L5 (main-drained type left over by a forced-tiny drain budget, then split).
4. Re-run the API-1A setVelocity and API-1B routed fixtures (owner's condition).
5. Every message type, not only `ENTITY_MUTATE`: at least one fixture per drain family (main-drained
   batch types, region-drained entity types, region-drained player types).

**Status: audit complete; fix not started. Next step: owner review of §8–§11, then the fix ТЗ.**
