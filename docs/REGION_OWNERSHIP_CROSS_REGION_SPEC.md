# NestWorldCore: завершення Region Ownership та безпечної cross-region моделі

Статус: Design / Implementation Roadmap
Пріоритет: correctness → compatibility → latency → performance
Ціль: закрити всі підтверджені cross-thread/cross-region проблеми, не змінюючи зараз threading-модель мережі та не ламаючи Forge/mod compatibility.

Прийнято 2026-08-27, слідом за [[piston-cross-region-atomicity-open-design-issue]] (P1's naive
`BLOCK_ENTITY_WRITE` guard reverted after live-testing broke cross-region piston pushes — this
spec is the direct response: formalize "atomicity of gameplay operation, not atomicity of a single
Java method" as a standing invariant, and build the completeness audit + dispatcher infrastructure
needed to do this correctly across the whole mutation surface instead of one bespoke guard at a time).

---

## 1. Мета

Побудувати в NestWorldCore завершену модель spatial ownership, у якій:

> Кожен region-owned об'єкт змінюється тільки owner region thread або через механізм доставки операції до owner region.

При цьому:
- не вводити entity locks;
- не переносити зараз packet processing із main thread;
- не ламати ensureRunningOnSameThread;
- не переписувати Forge networking;
- не робити Actor-per-Entity;
- не переводити ядро на ECS;
- не використовувати глобальну synchronization для entity;
- зберегти vanilla/Forge semantics.

---

## 2. Поточна архітектура

NestWorldCore вже має:

```
World
 │
 └── RegionTree
       │
       ├── Region A → Region Thread A
       ├── Region B → Region Thread B
       └── Region C → Region Thread C
```

Region є owner для просторового стану.

Вже існує `WorldRegion.nestworldPostMessage(...)`, який може безпечно використовуватися з іншого
thread. Також вже існують Stage 3 ownership guards для частини world mutations.

---

## 3. Основні інваріанти

**INV-01 — Entity ownership**: Region-owned Entity MUST NOT напряму мутуватися non-owner thread.
Допустимо: owner region thread → direct mutation, або foreign thread → owner region mailbox →
owner region thread → mutation.

**INV-02 — Block ownership**: Region-owned block state не повинен змінюватися напряму з foreign
region thread.

**INV-03 — BlockEntity ownership**: BlockEntity має той самий ownership boundary, що і його block
position. Не допускається `Block → Region B, BlockEntity → Region A` як результат частково
виконаної cross-region операції.

**INV-04 — Atomicity**: Якщо vanilla-операція логічно складається з декількох пов'язаних mutations
(block state + block entity + ticker + related lifecycle), вони повинні залишатися однією логічною
операцією при crossing region boundary. Не можна механічно розділяти їх на незалежні mailbox
messages. (Це саме те, що зламав P1's `BLOCK_ENTITY_WRITE` guard — див.
[[piston-cross-region-atomicity-open-design-issue]].)

**INV-05 — No entity locks**: Не вводити `synchronized(entity)` або entity-level mutex для
вирішення ownership. Основним механізмом залишається thread ownership + mailbox.

---

## 4. Phase 9 — повний Mutation Audit

Перед новими великими змінами провести inventory усіх world mutations: BLOCK (setBlock,
setBlockAndUpdate, destroyBlock, remove, neighbor updates, block events, scheduled block ops,
piston ops, redstone), BLOCK ENTITY (setBlockEntity, removeBlockEntity, ticker
registration/removal, setChanged, invalidate, load/unload, lifecycle), ENTITY (add, remove, kill,
hurt, movement, teleport, passenger, projectile, knockback, AoE, target interaction, ownership
transfer), PLAYER (attack, sweep, interaction, movement, item use, block break, block place,
inventory, teleport, command — player packet execution NOT moved at this stage).

## 5. Phase 9.1 — ENTITY completeness audit

Table format: `Mutation | Owner | Guard | Cross-region | Action`.

## 6. Phase 9.2 — EntityMutationDispatcher

```
EntityMutationDispatcher.dispatch(Entity target, Mutation mutation);
```
target → determine owner → current thread == owner? YES → execute directly; NO →
`owner.nestworldPostMessage(...)`. Dispatcher не повинен створювати locks.

## 7. Безпечне виконання queued mutation

Кожна queued entity operation перед виконанням повинна повторно перевірити: entity still exists?
still valid? still owned by destination region? not removed? Result on failure: NO-OP, not crash.

## 8. Cross-region Entity operations

Melee: `Player Region A → Target Region B → DamageCommand → Region B`.
Sweep: batch per destination region (`Region B → [target1,target2]`, `Region C → [target3]`), not
one message per target.

## 9. BLOCK + BLOCK ENTITY — окремий P0

Поточний P1 (`setBlock()` → `BLOCK_WRITE`, `setBlockEntity()` → `BLOCK_ENTITY_WRITE` as two
independent mailbox types) — **не повертати цей guard у поточному вигляді**. Причина: two separate
messages can split a vanilla operation (confirmed live — see
[[piston-cross-region-atomicity-open-design-issue]]).

## 10. Atomic Block Mutation

`CrossRegionBlockMutation { position, old block state, new block state, block entity
creation/update/removal, ticker lifecycle, related block operation }` — ONE mailbox operation,
destination region executes it in the correct order.

## 11. Piston regression

Mandatory test: standard `minecraft:piston`, no mods. Verify extension, retraction, moving piston,
`MovingPistonBlockEntity`, cross-region boundary. Guarantee: BlockState + BlockEntity + Ticker never
left in a logically inconsistent state. Separately test piston where source → Region A, target →
Region B.

## 12. Інші BlockEntity regression tests

After piston: chest, furnace, hopper, dispenser, dropper, brewing stand, command-related
BlockEntity, mod BlockEntities — confirm composite mutation doesn't break Forge/mod lifecycle.

## 13. Ownership Assertions

Debug/test mode: `assertEntityOwner()`, `assertBlockOwner()`, `assertBlockEntityOwner()`. On
violation: `CROSS_REGION_MUTATION` report with object/owner/current thread/expected thread/region/
callsite — maximally informative.

## 14. Phase 9.3 — RegionThreadPool correctness

Re-verify scheduler after mutation changes. Guarantee: dispatch submitted → executed exactly once,
or dispatch explicitly failed. Never: dispatch lost → latch waits forever. Check: duplicate
dispatch, lost dispatch, region thread crash, queue corruption, shutdown, region split, region
merge, ownership transfer.

## 15. Phase 9.4 — Stress testing

Combined scenario: players + entities + projectiles + pistons + redstone + block entities + chunk
load/unload + teleports + region boundaries + save. Look for: deadlock, race, NPE, lost message,
duplicate message, wrong owner, stale entity, ticker mismatch, world corruption.

## 16. Phase 10 — Player Region Execution

NOT implemented in Phase 9 — only after full mutation audit passes.

```
Network → Packet decode → Player Input Queue → Player Owner Region → Gameplay execution
```
Player стає region-owned gameplay actor, but not full Actor-per-Entity.

## 17. Player Input Queue

```
PlayerInput { sequence, tick, type, payload }
```
Types: MOVE, ATTACK, INTERACT, USE_ITEM, DIG, PLACE, DROP, SWAP. Network thread only delivers
input; region executes gameplay semantics.

## 18. Sequence ordering

Monotonically increasing per-player sequence. Region must: process input in correct order, reject
stale sequence, detect missing sequence, never execute duplicates.

## 19. Player Handover

Region A → boundary → Region B: freeze input → transfer ownership → install player in B → resume
input. Never simultaneous gameplay execution on both A and B.

## 20. Network threading migration

Separate high-risk phase. Do NOT change `ensureRunningOnSameThread` or equivalent Forge/vanilla
mechanism now. Future: packet → player owner lookup → player region execution, staged rollout.

## 21. Compatibility testing

Vanilla: movement, attack, inventory, chat, commands, teleport, block interaction.
Forge: packet handlers, custom networking, capabilities, events, login, player lifecycle.
Mods: custom packets, mods expecting server thread, `enqueueWork`, main-thread assertions,
synchronized server access, mods calling world/entity API from packet handlers.

## 22. Rubber-band testing

Mandatory for Player Region Execution. Test: normal movement, high latency, packet burst,
cross-region movement, region handover, chunk boundary, chunk unload/load, teleport, vehicle,
elytra, high-speed movement. Metrics beyond TPS: position correction rate, rubber-band events,
input latency, dropped input, duplicate input, sequence gaps.

## 23. Performance критерії

Local action: `Player Region → gameplay → local entity`, no mailbox. Cross-region action: mailbox
allowed, but measure mailbox latency, queue depth, cross-region operations/sec.

## 24. Заборонені підходи

❌ Entity locks (`synchronized(entity)`) · ❌ Global world lock (`synchronized(level)`) ·
❌ Actor-per-Entity · ❌ Повний ECS rewrite · ❌ RCU всього світу · ❌ Blind mailbox (не
розділяти логічно атомарну vanilla operation на незалежні повідомлення).

## 25. Критерії готовності Phase 9

- [ ] ENTITY audit завершений
- [ ] BLOCK audit завершений
- [ ] BLOCK_ENTITY audit завершений
- [ ] ownership map завершений
- [ ] EntityMutationDispatcher працює
- [ ] cross-region entity mutations безпечні
- [ ] piston atomicity вирішена
- [ ] BlockEntity lifecycle коректний
- [ ] ownership assertions працюють
- [ ] RegionThreadPool stress test PASS
- [ ] 197/420-mod test pack boot PASS
- [ ] vanilla regression PASS
- [ ] RCON functional tests PASS
- [ ] no new race/deadlock

## 26. Критерії готовності Phase 10

Тільки після Phase 9: Player Input Queue, Player Owner Region, Sequence numbers, Player handover,
gameplay execution on region, cross-region commands, packet ordering preserved, vanilla movement
PASS, combat PASS, inventory PASS, Forge networking PASS, mod networking PASS, rubber-band PASS.
Лише після цього — розглядати перенесення `ensureRunningOnSameThread`-подібної логіки на owner
region.

## 27. Фінальна ціль

```
NETWORK
   │
   ▼
Player Input
   │
   ▼
Player Owner
   │
   ▼
┌──────────────┐
│    REGION    │
│ Player       │
│ Entities     │
│ Blocks       │
│ BlockEntities│
└───────┬──────┘
        │
 ┌──────┴──────┐
 ▼             ▼
LOCAL        CROSS
 │             │
direct      command
                │
             mailbox
                │
                ▼
         Destination Region
```

Ключовий принцип: Local → synchronous. Cross-region → message/command. Global → explicit global
mechanism. Locks → не використовуються як основний ownership mechanism.

Головне: не переносити зараз мережеву обробку гравця. Спочатку закрити mutation correctness — це
дозволить реалізувати Player Region Execution пізніше вже поверх стабільного фундаменту.
