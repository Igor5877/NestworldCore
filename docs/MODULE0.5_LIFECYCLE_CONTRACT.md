# MODULE-0.5 — Lifecycle & Execution Contract Resolution

Status: IN PROGRESS (written incrementally; if cut short, everything above the cutoff is final).
Research/architecture/documentation only — no `ModuleClassLoader`, no hot reload, no production
Module SPI interfaces, no changes to `net.nestworld.modapi` (Mod API, Milestone 1, frozen at
commits `d405f5187`/`7123ef366`). Resolves the three named PARTIAL gaps from `docs/MODULE0_AUDIT.md`.

**Standing distinction carried forward, restated because it matters for everything below**:
Mod API/Core contracts (including whatever this document proposes) enforce architectural
correctness for COOPERATING code. They are not a sandbox against arbitrary Java code inside the
same JVM. `EntityMutationDispatcher` is `public` today and nothing stops direct, unmediated
calls to it (`docs/MODULE0_AUDIT.md` §B) — that fact is unchanged by anything in this document
and is not being "fixed" here.

---

## G1 — Generation Semantics

**Explicit rejection of a literal `SetVelocityIntentTracker` port.** `intentVersion` answers
"which intent is newer" for one entity's one field. `ModuleGeneration` answers "which incarnation
does this work belong to" for a module's entire registered surface. The *pattern* transfers
(stable identity axis + strictly-increasing per-identity counter as the arbitration key,
assigned at submission, checked immediately before the effect takes hold) — the *semantics* do
not, because module work is heterogeneous (commands, scheduled ticks, mailbox ops, callbacks)
where `setVelocity` was one op type on one field.

### Identity model

```
ModuleId          — stable across reloads (e.g. "example-module")
ModuleVersion      — code revision; changes when the module's jar/artifact changes
ModuleGeneration   — THIS LOADED INSTANCE's ordinal; strictly increasing per ModuleId; changes
                     on EVERY load, even reloading an unchanged jar (ModuleVersion unchanged)

CurrentGeneration[ModuleId] — the generation currently considered "in charge" for arbitration
```

**Linearization point**: `CurrentGeneration[ModuleId]` is assigned and published at the
`LOADED -> ENABLED` transition (`docs/MODULE0_AUDIT.md` §C) — a module's generation number is
fixed BEFORE it can submit any work, and the transition that makes a new generation "current"
must be the same atomic step that makes the module able to act. This mirrors
`SetVelocityIntentTracker.assignVersion`'s "assign before divergence" discipline exactly, one
layer up: assigning the generation anywhere later (e.g. lazily on first work submission) would
let two near-simultaneous loads race on which one is "really" current, reintroducing the same
ambiguity Milestone 1's own linearization-point design was built to eliminate.

### Work-state model (required — "stale -> drop" is explicitly NOT a universal rule)

```
QUEUED       — accepted by a ModuleContext-owned registration point (scheduled tick, mailbox op,
               command invocation), not yet begun executing
RUNNING      — actively executing (a Core thread or region thread is inside module-supplied code,
               or inside a Core-owned op derived from it)
COMPLETED    — finished normally; terminal
CANCELLED    — invalidated before it ever began RUNNING; terminal, no side effects assumed
INVALIDATED  — was QUEUED (never began RUNNING) when its module's generation went stale; terminal
```

Deliberately no "INVALIDATED-while-RUNNING" state — see below for why RUNNING work is handled
differently, not force-fit into the same bucket as QUEUED work.

### Generation check placement

**At the `QUEUED -> RUNNING` transition only** — immediately before the work begins executing,
never at enqueue time. This is the direct transfer from `EntityMutationDispatcher.applyDirect`'s
`SetVelocityIntentTracker.tryAccept` placement (checked right before the mutation, not at
`dispatch()`/enqueue time) applied to module generations instead of entity intents: check
`work.generation == CurrentGeneration[work.moduleId]`. If stale, transition directly to
`INVALIDATED`, never enter `RUNNING`. This is exactly the mechanism `docs/MODULE0_AUDIT.md` §E
named as needed to prevent "generation N submits work, gets replaced by N+1, N's delayed work
executes after N+1 is already running" — the module-lifecycle analog of the deterministically-
proven `API1A7-SEC11-ORDERING` inversion, now closed the same way `API-1A.8` closed it for
`setVelocity`.

### The hard case: work already RUNNING when generation changes

**This is where "stale -> drop" cannot apply, per the directive's own explicit warning.** A
callback already executing a transactionally-significant operation cannot be retroactively
invalidated without risking a half-applied effect worse than letting it finish. Resolution:

1. Work that is `RUNNING` when its module's generation is superseded is **not** interrupted. It
   runs to `COMPLETED`, bounded by a timeout (reusing `NestworldShutdownWatchdog`'s own "safety
   net, not the primary path" philosophy, `docs/MODULE0_AUDIT.md` §E.2 — the watchdog's actual
   mechanism, halting the whole JVM, is far too blunt for one module, but the *shape* — bounded
   wait, then a forceful terminal action, never an unbounded wait — is the right one to reuse).
2. On timeout, the work (and, by extension, the module's `QUIESCING`/`DISABLED` transition
   waiting on it) transitions to `FAILED` (`docs/MODULE0_AUDIT.md` §C), never silently to
   `DISABLED` as if the drain had succeeded — proceeding silently would repeat the exact
   "distinct identity proves nothing about resource cleanup" mistake `docs/contracts/
   api-1a7-findings.md`'s "Lifecycle identity replacement: PASS" finding already taught this
   session.
3. **New structural constraint this analysis surfaces, not previously stated**: because stale-
   but-`RUNNING` work cannot be safely reconciled against whatever the new generation has
   already done to shared state, any work type that is allowed to be long-running/uninterruptible
   must be scoped, by the SPI contract itself, to touch ONLY resources it exclusively owns for
   the duration of that one operation — never shared, cross-generation Core state. This is a
   real design constraint on what kinds of extension-point work are eligible for `RUNNING`-state
   protection at all, not a detail to defer. Work that must touch shared state (e.g. a
   region-mutation op routed through `EntityMutationDispatcher`) should instead be short enough
   that this race window doesn't matter in practice — the same "at-most-one-mutation-call,
   fire-and-forget" shape Milestone 1's own ops (`SetVelocity`, `Ignite`, etc.) already use, not
   a long-lived transactional callback.

### QUIESCING behavior, unified with the generation check above

`docs/MODULE0_AUDIT.md` §C flagged `QUIESCING` as having zero Core precedent (Core's own
shutdown is silent loss, `RACE_CLASSIFICATION.md` item 15). Recommendation: **model entering
`QUIESCING` as "this module's generation is no longer current" even before any replacement
generation N+1 literally exists** — i.e. `CurrentGeneration[ModuleId]` is set to a sentinel
("quiescing") at the moment `QUIESCING` begins. This means the SAME `QUEUED -> RUNNING` check
built for cross-generation staleness (above) also correctly handles "same generation, but now
quiescing," with zero additional code path:

- `QUEUED`, not yet `RUNNING`: naturally invalidated by the unified check the instant
  `QUIESCING` begins. New submissions are separately blocked at the `ModuleContext` registration
  boundary itself (straightforward, per `docs/MODULE0_AUDIT.md` §E.2 — a flag the registration-
  forwarding methods check).
- `RUNNING` at the moment `QUIESCING` begins: the hard case above — bounded wait, then `FAILED`
  on timeout, never silent success.
- `COMPLETED`/`CANCELLED`/`INVALIDATED`: already terminal, nothing to do.

### Where the tag physically lives — Open Question 2 from MODULE-0, now given a recommendation

`docs/MODULE0_AUDIT.md` §H named two real precedents without choosing between them: a new field
on message/op records (mirroring `EntityMutationOp.SetVelocity`'s `intentVersion` field) vs. a
side-table keyed by an operation id (mirroring this session's own diagnostic `opId` mechanism).
**Recommendation: the field approach, not a side-table**, for the same reason Milestone 1 chose
it for `intentVersion` — a side-table requires a second lookup and a second place for staleness
to creep in (the side-table entry itself could go stale relative to the work item), whereas a
field travels atomically with the work. Concretely: any future `ModuleContext`-owned work
envelope (a scheduled-tick registration, a mailbox op, a command invocation record) should carry
`(moduleId, generation)` as fields, the same way `RegionMessage.EntityMutate` already carries
`opId` (diagnostic) and `EntityMutationOp.SetVelocity` carries `intentVersion` (semantic) side
by side — this session already has a working precedent for a message type carrying both a
diagnostic-only field and a semantic-arbitration field without confusing the two, worth reusing
directly.

---

## G2 — Execution Vocabulary Reconciliation

### The matrix, built before any naming decision

| GMEIL `ExecutionPolicy` value | What it actually means (`docs/gmeil/POLICY_MODEL.md`) | What a Module SPI capability needs |
|---|---|---|
| `MAIN_COMPAT` | Default classification for **unknown, opaque code** — execute with server-thread affinity, evidence required is just "this is the default for code we haven't certified anything about" | A Module SPI hook is never "unknown" — the module explicitly registered against a KNOWN contract. There is no "default because we don't know" case in a declared SPI. |
| `SERIAL_COMPAT` | Earned via **runtime evidence** ("proven no main-thread affinity; token handoff and wait model validated") — a certification tier reached through observation over time | Module SPI hooks don't get "promoted" through observed evidence — their execution context is fixed by which extension point they registered against, decided at design time, not discovered at runtime. |
| `REGION_CERTIFIED` | Requires **"positive, closed proof for this exact path and runtime artifact"** — an evidence-graded certification, invalidated by a changed jar hash, loader/version, etc. | A module's region-local hook doesn't need "certification" — Core already knows, by construction, that it's invoking the hook on the owning region's thread (the SPI contract guarantees this), the same way `RegionThread.requestWork` already does today with zero certification machinery. |
| `CORE_OWNED` | **"Core-controlled shared state path"** — Core owns the semantic boundary, not the callback | **This one is the same concept in both systems.** A split/merge-policy module supplying a decision Core then executes (`docs/MODULE0_AUDIT.md` §B/§E.2 addendum) is exactly "Core-controlled shared state path, module only supplies a decision." |
| `EXTERNAL_ASYNC` | **"Entry from an unmanaged executor"** — specifically about code arriving from a thread Core doesn't control, requiring mutations to be routed to an owner or rejected | `docs/MODULE0_AUDIT.md` §D already recommends modules get NO raw executor access — this GMEIL tier exists to handle a failure mode (unmanaged executor entry) the Module SPI design should prevent by construction, not accommodate. |
| `DENY` | Explicit restriction or a broken invariant, for code GMEIL has evidence should not run | A Module SPI hook either exists as a registered extension point (with one of the contexts below) or was never registered at all — there is no "registered but denied" state; an unregistered module simply has no hook to invoke. |

### The decision

**Do not reuse `ExecutionPolicy` as the Module SPI's execution-context type, and do not extend
it with new values.** The two systems answer structurally different questions that happen to
share physical vocabulary (main thread / region thread / Core-controlled / unmanaged-async):

- **GMEIL classifies EMPIRICALLY-OBSERVED trust for OPAQUE, uncontrolled callback code** — the
  legacy/third-party mod-compatibility problem. Its whole model (evidence requirements,
  certification invalidated by a changed jar hash, hardening-only transitions
  `REGION_CERTIFIED -> SERIAL_COMPAT -> MAIN_COMPAT -> quarantine`) exists because GMEIL does
  not control the shape of the code it's classifying.
- **Module SPI classifies the DECLARED CONTRACT of a KNOWN, first-party-designed extension
  point** — the module author writes directly against a documented method signature Core
  defined. There is nothing to "certify" or "hold evidence for" — Core already knows exactly
  what thread a `SplitMergePolicy.decide(...)` implementation will be invoked on, because Core
  wrote the invocation site.

Applying GMEIL's enum to Module SPI would incorrectly imply SPI hooks need the same evidence-
graded certification lifecycle opaque mod callbacks do — a real, avoidable modeling error, not
just an aesthetic naming clash.

**New, minimal, disciplined vocabulary — only as many values as this audit's evidence actually
supports, deliberately smaller than the four-placeholder draft in `docs/MODULE0_AUDIT.md` §E.2**:

```
ModuleExecutionContext {
    MAIN_ONLY     — invoked on the main/server thread
    REGION_LOCAL  — invoked on the owning region's own thread
    CORE_OWNED    — module supplies a decision/callback; Core decides if/when/where it runs
}
```

Naming rationale, each justified against real precedent, not invented fresh:
- **`MAIN_ONLY`** — reused verbatim from `ModCallbackRouter`'s own existing `MAIN_THREAD_ONLY`
  classification (`docs/MODULE0_AUDIT.md` §A, a real, already-shipped Core precedent), not from
  GMEIL's `MAIN_COMPAT` (whose name carries GMEIL's specific "default when we don't know
  anything" connotation, wrong for a declared contract).
- **`REGION_LOCAL`** — deliberately NOT `REGION_CERTIFIED`, precisely to avoid implying GMEIL's
  certification/evidence lifecycle applies. Matches `RegionThread.requestWork`'s already-proven
  production mechanism (reused directly in this session's own `API1A7` same-owner visibility
  test) — a module hook running here is guaranteed region-thread affinity by the SPI's own
  invocation contract, not by observed evidence.
- **`CORE_OWNED`** — reused VERBATIM from GMEIL, deliberately, because this is the one row in
  the matrix above where the underlying concept genuinely is identical in both systems: "Core
  controls the shared-state path; the callback only supplies a decision." Reusing the name here
  is a considered choice, not an oversight — it signals real conceptual overlap where it exists,
  while the other two values signal real conceptual difference where that's also true.

**No `CONCURRENT`/unmediated-execution value in this design.** The earlier four-placeholder draft
included one; this pass drops it deliberately, since `docs/MODULE0_AUDIT.md` §D already
recommends against modules getting raw execution access with no Core state visibility at all —
there is no real extension point evidenced anywhere in §A that needs this context, and inventing
a value with no grounded use is exactly the kind of un-evidenced vocabulary expansion this gate
exists to prevent. If a genuine need for unmediated concurrent module code surfaces later, it
should earn its own value from real evidence then, not be pre-allocated now.

**No `DENY`.** An extension point a module hasn't registered against simply has no hook — there
is no "registered but denied" state to represent, per the matrix row above.

**Composition, not collision, when the two systems meet**: if a module's `CORE_OWNED` or
`REGION_LOCAL` hook internally invokes further OPAQUE, third-party callback code (not written
against the Module SPI contract — e.g. a legacy mod's own listener the module happens to call),
THAT nested call is still independently subject to GMEIL's own classification. The two systems
operate at different layers — Module SPI classifies Core's own designed extension points; GMEIL
classifies arbitrary opaque code wherever it's encountered, including inside a module's own
hook body. They compose; they do not need to be unified into one vocabulary.

---

## G3 — Unload Guarantee, Reformulated

### The exact wording

**Retired framing** (never actually assertable, per `docs/MODULE0_AUDIT.md` §F addendum):
> NestWorld guarantees complete module unload.

**Adopted wording**:
> NestWorld guarantees revocation and lifecycle management of resources acquired through
> `ModuleContext`: command registrations, event-bus subscriptions, generation-tagged scheduled/
> mailbox work (§G1), and Core-mediated async work. For these, Core can enumerate what a module
> currently holds and can assert — not merely hope — that the set is empty once `DISABLED`.
> NestWorld does NOT guarantee, and must never claim to guarantee, release of any resource a
> module acquires outside `ModuleContext`: raw threads/executors, `ThreadLocal`s set on
> Core-owned threads, static fields inside module-authored classes, or live Minecraft/Core
> object references captured inside module-internal state. For these, only classloader
> collectibility is empirically testable (a `WeakReference` held after an explicit `System.gc()`,
> checked for `null`) — and that is a verification TECHNIQUE for validating a design after the
> fact, not a runtime guarantee Core can rely on before it happens, and it only proves the
> classloader itself, not necessarily every object transitively reachable through it if
> something outside the module still holds a reference into that graph.

### Ownership split (grounded in `docs/MODULE0_AUDIT.md` §D, not restated from scratch)

```
ModuleContext-owned — Core CAN revoke/invalidate/stop, and CAN assert emptiness:
    command registrations       -- Brigadier tree supports node removal (§A "Commands" row)
    event-bus subscriptions     -- ModuleContext.subscribe(...) returns a revocable handle
                                    (§D "Listeners" row)
    scheduled/mailbox work      -- ONLY once G1's generation tagging exists; currently NOT
                                    revocable in practice despite being the right conceptual
                                    shape, because nothing tags ownership today (§D)
    Core-mediated async work    -- IF modules never get raw executor access and all async work
                                    routes through a Core-owned, trackable facility (§D's own
                                    recommendation)

Module-owned — Core CANNOT guarantee anything about these, structurally:
    new Thread(...) / ExecutorService   -- the exact failure mode that originally motivated
                                            NestworldShutdownWatchdog (a leaked non-daemon
                                            executor kept the JVM alive post-shutdown, §D)
    static FIELD = INSTANCE (module's own classes) -- Core can fix ITS OWN static fields
                                            (the VelocityBackendRegistry token-check pattern,
                                            generalized) but has zero visibility into a
                                            module's own internal static state (§D)
    arbitrary ThreadLocal        -- DimensionChangeScope.java:23 is Core's own safe counter-
                                     example (Core-lifetime, never expected to go away); a
                                     MODULE-set ThreadLocal on a Core-owned long-lived thread
                                     (e.g. a RegionThread) is the actual hazard (§D)
    external-library callback    -- unless the registration itself was made THROUGH a
                                     ModuleContext-owned facility, Core has no path to it (§D)
```

### The incentive structure — stated as contract, not just caveat

This split is not incidental — it is the actual lifecycle-strength lever available to module
authors, and should be documented to them as such, not buried as a limitations footnote. A
module that routes its resource acquisition entirely through `ModuleContext` (commands via
`ModuleContext.command(...)`, listeners via `ModuleContext.subscribe(...)`, async work via a
Core-mediated dispatch facility) gets NestWorld's strongest available unload guarantee: Core can
assert zero owned registrations remain. A module that spawns its own threads, keeps its own
static caches, or registers directly with external Forge/library APIs forfeits that guarantee
proportionally — not as an arbitrary penalty, but as the direct, mechanical consequence of Core
no longer holding a reference it could revoke. **State this to module authors as a design
incentive built into the contract, not merely a disclaimer about what might go wrong.**

### Observable/testable unload criteria — three honesty tiers, not one flat list

**ASSERTABLE** (Core checks this itself, synchronously, no external test infrastructure needed):
zero entries in `ModuleContext`'s own registration handle set after `DISABLED`.

**TESTABLE** (needs a deliberate verification pass — e.g. a future MODULE-1+ test gate,
matching this session's own standing evidence discipline of not trusting a single unverified
run): classloader collectibility via `WeakReference` + `System.gc()` + null-check. Refined here,
not accepted blindly: a single `System.gc()` call and immediate check is NOT sufficient evidence
on its own — the same discipline this session applied throughout Milestone 1 (never trust one
unverified test run) applies here too. A real verification pass should retry across multiple GC
cycles and/or confirm via actual GC logging that a collection genuinely ran, before treating a
non-null `WeakReference` as a real finding rather than a timing artifact.

**NOT VERIFIABLE by any mechanism this audit or MODULE-0 found**: executor termination (only
even ASSERTABLE if raw executor access is disallowed by design per the ownership split above —
otherwise not verifiable at all, by construction), absence of active callbacks inside module-
internal code Core never mediated, `ThreadLocal` cleanup, a module's own static-field cleanup.
These are permanent limitations of what "unload" can honestly mean for this design, not gaps to
be closed by more engineering effort later — no mechanism exists in the JVM that would close
them without the module's own cooperation.

---

## Closing — Are the Three Gaps Resolved?

**G1 (generation semantics)**: RESOLVED as a design. A concrete identity model, a five-state
work-state model, a precise generation-check placement (`QUEUED -> RUNNING` boundary, mirroring
`SetVelocityIntentTracker.tryAccept`'s placement), an explicit non-universal treatment of stale
work (RUNNING work completes bounded by timeout, never silently treated as success), a
`QUIESCING` unification with the same staleness check, and a field-placement recommendation for
where the tag physically lives. **Not implemented** — this is a chosen shape, not code — but no
longer an open design question the way it was at the end of MODULE-0.

**G2 (execution vocabulary)**: RESOLVED as a decision. A three-value `ModuleExecutionContext`
(`MAIN_ONLY`/`REGION_LOCAL`/`CORE_OWNED`) that deliberately does NOT reuse or extend GMEIL's
`ExecutionPolicy` (different question: declared contract vs. empirically-earned trust for
opaque code), reuses GMEIL's own `CORE_OWNED` name where the concept is genuinely identical, and
reuses `ModCallbackRouter`'s existing `MAIN_THREAD_ONLY` precedent under `MAIN_ONLY` rather than
inventing new terminology where real precedent already exists. The two vocabularies are declared
to compose (a module hook may itself invoke opaque code subject to independent GMEIL
classification), not to collide.

**G3 (unload guarantee)**: RESOLVED as wording + a three-tier honesty framework
(ASSERTABLE/TESTABLE/NOT VERIFIABLE) replacing the single flat "successful unload" question
MODULE-0 correctly refused to answer with one number. The incentive-structure consequence
(ModuleContext-routed resource acquisition earns a strictly stronger guarantee) is stated as
part of the contract module authors should be told about directly, not as a caveat buried in
documentation.

**All three gaps that blocked MODULE-0's PASS verdict now have a chosen design.** None of the
three required inventing new mechanism beyond what this session's own existing precedents
(`SetVelocityIntentTracker`'s linearization discipline, `ModCallbackRouter`'s `MAIN_THREAD_ONLY`,
`VelocityBackendRegistry`'s token-check pattern, `NestworldShutdownWatchdog`'s bounded-wait-then-
force philosophy, GMEIL's `CORE_OWNED` concept) already supply templates for — which is itself
evidence these designs are grounded in how this codebase actually works, not invented from
nothing.

**This does NOT mean MODULE-1 should implement hot reload.** Per the architecture owner's own
framing: even a fully resolved MODULE-0.5 only unblocks a MODULE-1 gate scoped to a MINIMAL
startup-loaded SPI skeleton + `ModuleContext` resource accounting + the lifecycle foundation
above — `STARTUP_ONLY`-classified capabilities first (per `docs/MODULE0_AUDIT.md` §F's own
reloadability matrix, sidestepping G1's generation-tagging complexity and G3's unload-guarantee
limits entirely for a first slice, mirroring how Milestone 1 itself narrowed to one primitive
before generalizing). Hot unload/reload, and the harder generation-tagged scheduled/mailbox-work
capabilities, should get their own later gate once a minimal SPI skeleton has been built and
verified against real Core code — not designed further here.

**This document does not design MODULE-1 itself, scope its first capability, or decide whether
MODULE-1 should be started now versus returning to API-1B first.** Those are the architecture
owner's decisions to make after reviewing this gate's output, per the directive.

---

## Verdict

**MODULE-0.5 RESOLVED**

All three gaps named in `docs/MODULE0_AUDIT.md`'s PARTIAL verdict — generation semantics,
execution vocabulary, and the unload guarantee's honest scope — now have a concrete, evidence-
grounded design. The MODULE track is no longer blocked by an undecided foundational question;
what remains is implementation-scoping and sequencing decisions, which belong to the
architecture owner, not this audit.
