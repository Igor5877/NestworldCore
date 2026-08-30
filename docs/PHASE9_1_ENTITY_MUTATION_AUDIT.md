# Phase 9.1 — ENTITY completeness audit (first pass)

Per `docs/REGION_OWNERSHIP_CROSS_REGION_SPEC.md` section 5. Table format:
`Mutation | Owner | Guard | Cross-region | Action`.

This is a **first-pass consolidation** of everything already found/fixed across this project's
prior audits (full-core-audit-2026-08-27, the P1 ENTITY/PLAYER forks, entity-safety-layer-and-
explosion-p0), not a fresh line-by-line re-scan of the whole codebase — that exhaustive pass is
still open (see "Not yet audited" below) and, per the lesson from the reverted piston fix, any
further guard work found here should NOT be implemented ad hoc; it should wait for the
`EntityMutationDispatcher` (Phase 9.2) so every guard goes through one reviewed mechanism instead
of N bespoke call sites.

## Already guarded (CONFIRMED safe — live-validated where noted)

| Mutation | Owner | Guard | Cross-region | Action |
|---|---|---|---|---|
| `Player.attack()` melee hit (`hurt`+`Damage`) | region-owned target, main-thread self | `redirectFromMainThread` | Yes | mailbox — **live-tested w/ real mineflayer client** |
| `Player.attack()` main knockback | region-owned target, main-thread self | `redirectFromMainThread` | Yes | mailbox — live-tested |
| `Player.attack()` sweep-AoE loop (per-entity `hurt`+`Knockback`) | region-owned target, main-thread self | `redirectFromMainThread`, per-entity | Yes | mailbox — live-tested |
| `Explosion.explode()` entity-impact loop | region-owned entity, any region thread | ownership check + `RegionMessage.entityImpact` | Yes | mailbox (`applyPendingEntityImpacts`) — P0, oldest fix in this family |
| `RegionThread.applyPendingEntityPushes()` | region-owned entity | ownership recheck | Yes | mailbox — sibling fix to Explosion P0 |
| `ItemEntity.mergeWithNeighbours()` | region-owned item entity | region-awareness added | Yes | mailbox |
| `AreaEffectCloud` tick (potion cloud hit) | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `EvokerFangs` bite (2 sites) | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `ThrownPotion` splash (3 sites: direct hit, splash AoE, `applyWater`/extinguish) | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `LlamaSpit` hit | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `ShulkerBullet` direct hit + explosion-adjacent | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `FireworkRocketEntity` explosion (attached entity + AoE loop) | region-owned entity, per-entity in loop | `redirectIfForeign` | Yes | mailbox |
| `Guardian` laser damage | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `Ravager` roar knockback | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `FallingBlockEntity` fall-damage AoE (`m_6249_` scan) | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `SonicBoom` (Warden) hurt | region-owned entity | `redirectIfForeign` | Yes | mailbox — push() left unguarded, see below |
| `Llama` fall-damage-to-passenger | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `AbstractHorse` fall-damage-to-passenger | region-owned entity | `redirectIfForeign` | Yes | mailbox |
| `EnderDragon` bite/crush damage (2 sites) | region-owned entity | `redirectIfForeign` | Yes | mailbox — knockBack()'s custom-vector push() left unguarded, see below |

## Known gaps — CONFIRMED, not yet fixed (do NOT ad-hoc fix; route through Phase 9.2 dispatcher)

| Mutation | Owner | Guard | Cross-region | Action |
|---|---|---|---|---|
| `Player.interactOn()` (all right-click-entity interactions) | region-owned target, main-thread self | **none** | Yes | needs new dispatcher entry — synchronous-result requirement (interact can return a value/consume an item) makes this harder than fire-and-forget damage ops, flagged as needing its own design pass |
| `ItemEntity`/`ExperienceOrb.playerTouch()` (pickup) | region-owned item/orb, main-thread player | **none** | Yes | needs new dispatcher entry — doesn't fit the closed `EntityMutationOp` set (inventory mutation + entity removal, not a simple op) |
| `EnderDragon.knockBack()` custom-vector `push()` calls | region-owned entity | **none** | Yes | no existing helper accepts a custom force vector; `EntityPushHelper` only computes its own from relative position |
| `SonicBoom` (Warden) `push()` after the guarded `hurt()` | region-owned entity | **none** | Yes | same custom-vector gap as EnderDragon |
| `Raid.java` hero-of-the-village effect application | N/A (no natural `self` entity) | **none** | Likely (mostly players, usually main-thread) | lower priority — doesn't fit `redirectIfForeign(self, target, op)`'s shape at all, needs bespoke handling |

## Not yet audited (genuinely open — needs the exhaustive pass, not done this turn)

`setPos()`/teleport call sites outside the already-covered ones, `remove()`/`kill()` call sites
outside explosion/mob-death paths, passenger mount/dismount, generic projectile-hit call sites
beyond the ones listed above (there are more projectile subclasses in a 420-mod pack than this
project's own vanilla/Forge audit surface covers — mod-added projectiles are explicitly out of
scope for a vanilla/Forge-file audit and would need their own pass if a specific mod is flagged).
This matches the spec's own section 5 table shape exactly but the FULL sweep (grep every
`.hurt(`/`.kill(`/`.remove(`/`.setPos(` call site across `projects/forge/src/main/java` and
classify each) has not been re-run from scratch this session — what's above is the union of
everything already found across this project's several targeted audits, not a fresh line count.

## Recommendation for next step

Do NOT write more bespoke `redirectIfForeign` call sites for the gaps above. Build
`EntityMutationDispatcher` (Phase 9.2, task #27) first, with `interactOn`/`pickup` as its two
hardest test cases (synchronous-result and non-`EntityMutationOp`-shaped mutation respectively) --
if the dispatcher's API can cleanly express both, it's proven general enough for the rest of the
audit surface. Only after 9.2 exists should the remaining gaps in this table be implemented, each
as a dispatcher call, not a new one-off guard.
