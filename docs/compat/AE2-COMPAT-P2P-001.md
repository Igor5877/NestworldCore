# AE2-COMPAT-P2P-001 — P2P service maps: synchronous writers vs region-thread transfer readers

**Status: CONFIRMED FROM CODE / OPEN.** Separated from GRID-PHASE-001 (owner, 2026-10-14). Source:
SERVICE-MUTATION-001 §2.
- **Writers:** `P2PService.updateFreq` (memory card, part thread), the `newFrequency()` allocation,
  and `add/removeNode` tunnel membership (topology).
- **Readers:** `getInput()` / `getOutputStream()` / output lists on FE / item / fluid transfer paths,
  on any region thread at any time.
- No grid phase iterates these maps, so P(G) does not protect them. Using it for `updateFreq` was
  REJECTED as a false protection.
- **Approved direction:** a per-grid P2P state guard.
  - Readers take the read guard, capture a stable snapshot or reference set, release it, then
    transfer.
  - Writers take the write guard, perform the map / frequency mutation atomically, increment the
    generation, and release.
  - `newFrequency()`: "find a free value and register it" is **one** linearizable writer
    transaction.
  - The guard is never held across an external FE / item / fluid callback.
- Separate audit and design required. It does not block GRID-PHASE implementation.
- It blocks full unpin, and any energy unpin whose scope runs FE P2P region-parallel.
