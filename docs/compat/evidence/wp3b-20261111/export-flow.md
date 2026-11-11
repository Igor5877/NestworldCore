# Safe export flow and latency scope

| Phase | Thread / execution domain | Typical tick | Boundary and reason |
|---|---|---|---|
| Bus tick, snapshot, register transfer | Server / AE2 MAIN | N | Record owns operation before extraction; no stock moved yet |
| Target capacity SIMULATE | Target owner / neighbour domain | Usually N+1 | MAIN to Core domain mailbox; lifecycle/domain validation before access |
| Probe publication, plan, powered extraction | Server / AE2 MAIN | N+1 safe point | Per-grid inbox to coalesced Core MAIN mailbox pump; actual power debit precedes stock removal |
| Insert held items into target | Target owner / neighbour domain | N+1 or N+2 | MAIN to neighbour mailbox; actual acceptance may differ from probe |
| Ack, rejected remainder return, finalize | Server / AE2 MAIN | N+1 or N+2 | Per-grid inbox to MAIN pump; server-END fallback |

Previously probe/ack processing waited for one server-END pass. A late probe could wait until N+2, push until N+3, late ack until N+4. The existing Core dimension MAIN safe points let the addon service its inbox earlier without synchronous REGION-to-MAIN waits or changing pin semantics.

The capacity probe remains necessary for reference power/extraction semantics. The pump removes scheduler waiting rather than bypassing ownership or eliminating this safety step. MAIN pump and END passes share one budget per tick; neither replenishes it.

The measured copied-world run showed export p100=1 tick, but only 48 exported units. This is not a universal <=2 guarantee. Region backlog, missed safe points, budget exhaustion, lifecycle rerouting and failed pump anchors can defer owned/queued operations. Finite-energy, multi-key and loaded/free-running acceptance remain distinct from this small productive sample.
