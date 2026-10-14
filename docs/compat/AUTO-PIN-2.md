# AUTO-PIN-2 — stack attribution names the faulting mod, not the mutating mod

**Status: OPEN finding (2026-10-14). Auto-pin is retained as a mitigation; no change in MOD-COMPAT-1's
first gate.**

## Finding
Auto-pin (`NestworldPins.noteCallbackError`) attributes an error to the mod owning the first mod
frame of the stack. For a cross-thread race on shared state, that is the *reader* that tripped
over the inconsistency. The *writer* that mutated the state from another thread is usually a
different mod.

Observed on the island (`docs/contracts/OPS-INCIDENT-2026-10-05-ae2-energy-race.md`):

| Pin | What followed |
|---|---|
| `mod:ae2`, caught-elsewhere | the AE2 race recurred 17 times |
| `mod:mekanism`, caught-elsewhere | 2 more NPEs within 2 s, then the circuit-breaker crash |
| `be:pipez:energy_pipe`, own-tick | the AE2 race was not seen again (correlation) |

The code-level mechanism is in AE2-COMPAT-001 finding 2 and PIPEZ-COMPAT-001 finding 1. An external
block entity's Forge Energy call mutates the receiving mod's network state on the caller's thread.
The caller's stack is never the one that fails.

## Possible future attribution inputs (not in this gate)
- caller mod and target subsystem;
- target grid or network identity;
- origin region and origin block entity;
- the capability boundary crossed;
- the causal chain (which thread was inside the target's phase).
