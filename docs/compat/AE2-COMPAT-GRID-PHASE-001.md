# AE2-COMPAT-GRID-PHASE-001: grid death during main's grid phase tick (non-energy services)

**Status: OPEN. CONFIRMED (observed once, 2026-10-14). Implementation gap against Design-1.4 §3.2.
Owner decision needed.**

## Observation
Run: the clean-jar integration run (`5bf32db6…`), while QUEUE-LIFE-001 removed a single-node AE2
grid (an inscriber) from RegionThread `NestWorld-Region-21`.
```
TickHandler.onServerTickEnd → Grid.onServerEndTick → PathingService.onServerEndTick:134
  → achievementPost:266 → grid.getPivot().getLevel()
NullPointerException: return value of "appeng.me.Grid.getPivot()" is null
```
- 2 occurrences in one tick. Core's catch-and-continue skipped that tick.
- Log: `/root/compat-ae2-rig/evidence/integration-pivot-npe.log`.
- Not seen in any other run: race A/B, multi-region, the 60-min soak or the queue runs.

## Mechanism (STRONGLY INDICATED)
- `Grid.onServerEndTick` checks `pivot != null` once, then runs every service's phase method.
- If a RegionThread destroys the grid's last node meanwhile, `Grid.remove` sets the pivot to null.
  That happens under T0 plus the domain, but main's Pathing tick holds neither.
- `PathingService` then dereferences the pivot.
- The race is between AE2 topology on a region thread and **non-energy** grid services on main
  (Pathing here; Storage, Crafting, Spatial and P2P have the same exposure). It is not specific to the
  addon: the same race exists unpinned without the addon.

## Gap against the approved design
- Design-1.4 §3.2 specifies the **T1 phase wrapper around each `Grid.on{Server,Level}{Start,End}Tick`
  invocation per grid**, with liveness (`pivot != null`, grid identity) validated under the domain.
- The implementation (`27ddb5212`) wraps only the **EnergyService** and **TickManagerService** phase
  methods. Grid-level wrapping was not implemented.
- That is a deviation from the written design, reported here rather than silently corrected.

## Why the fix is not applied unilaterally
Wrapping `Grid.on*Tick` holds the grid's domain across **every** service's phase code. The
non-energy services call foreign code, for example crafting pushing patterns into machines and
storage reaching external inventories. That code is not in the audited participant set
(001.1–001.2), so the proof "only audited code runs under a domain lock" would no longer hold. That
proof is the basis of the M1/M2/M5c deadlock argument.

## Options (owner)
1. **Grid-level phase wrapper per 1.4 §3.2**, preceded by an audit of every service's phase code for
   foreign calls. Each foreign call becomes either deferred (M1-like) or suspended (T2-like). Large.
2. **Liveness-only hardening.** Make grid death invisible to an in-progress main phase tick: defer
   the pivot := null and `removeNetwork` of a grid that main is currently ticking until that grid's
   phase call returns. That is a small topology-side change, with no lock held across foreign code.
   Needs a precise contract.
3. **Out of scope.** Record it as part of "storage / pathing / P2P are outside this gate". Whole-AE2
   unpin stays forbidden; the energy-only selective unpin is unaffected.
