# ТЗ — Phase 10 / #30: Global-State Audit NestWorldCore

## 1. Мета

Провести повний аудит глобального та shared mutable state у NestWorldCore + патченому vanilla/Forge-коді після завершення #27 EntityMutationDispatcher.

Головна мета:

> Виявити всі об'єкти, які можуть читатися/мутуватися кількома region threads, але не мають формально визначеного ownership або безпечного механізму cross-region mutation.

Це аудит, а не масштабний рефакторинг. Кожна зміна коду допускається лише для підтвердженої race/unsafe mutation.

## 2. Поточна базова архітектура

Region-owned state → Owner Region Thread → direct mutation.
Foreign region state → Region mailbox → Owner Region Thread.
Global state → Global Owner / serialized access.
Immutable state → Concurrent read allowed.

Заборонено використовувати як автоматичне рішення: `synchronized`, `ConcurrentHashMap`, global lock, main-thread fallback — якщо ownership/message model вирішує проблему без них.

## 3. Scope аудиту

**3.1 Vanilla**: MinecraftServer, ServerLevel, Level, LevelChunk, ChunkAccess, Entity, Player, BlockEntity, SavedData, MapItemSavedData, CommandStorage, Raid, PrimaryLevelData, Scoreboard, GameRules, WorldBorder, PoiManager, ServerChunkCache, DistanceManager, tick systems, scheduler systems, random/state systems, server-wide collections.

**3.2 Forge**: event buses, event handlers, capabilities, registries, networking, server lifecycle, world lifecycle, entity/block/chunk events, global Forge managers, callbacks reachable from region threads. Especially code assuming "this always runs on Server thread".

**3.3 NestWorldCore**: static mutable state, singleton state, global caches, RegionThreadPool, RegionScheduler, RegionTree, WorldGrid, WorldRegion, mailboxes, ownership tables, handover state, metrics, diagnostic state, pending queues.

## 4. Методологія

Для кожного mutable object: WHO owns it? WHO reads it? WHO writes it? ON WHICH THREAD? HOW CAN OWNERSHIP CHANGE? WHAT HAPPENS DURING HANDOVER? WHAT HAPPENS DURING REGION SPLIT/MERGE?

## 5. Класифікація

- **A — REGION**: Owner = конкретний WorldRegion, записи owner thread, cross-region → mailbox.
- **B — GLOBAL**: не належить конкретному region, потребує Global Owner або serialized execution.
- **C — MESSAGE-OWNED**: мутується з різних контекстів, але через command/mailbox.
- **D — IMMUTABLE**: не мутується після створення, concurrent read дозволений.
- **E — EXTERNAL**: належить Forge/JVM/OS/іншому subsystem, NestWorldCore не змінює threading contract.
- **F — MAIN-THREAD-ONLY**: лише якщо реально підтверджена вимога vanilla/Forge, з джерелом/причиною.

## 6. Особлива увага — вже знайдені класи

Повторно перевірити MapItemSavedData, CommandStorage, Raids, PrimaryLevelData — переконатися, що фікси відповідають загальному ownership contract, а не є поодинокими патчами.

## 7. Static state audit

Знайти всі `static Map/Set/List/Queue/Cache`/mutable fields. Для кожного: field, class, creation thread, read threads, write threads, lifecycle, owner, protection. Особливо HashMap/HashSet/ArrayList/ArrayDeque/WeakHashMap/Guava Cache — але тип сам по собі не доказ race.

## 8. ServerLevel / Level audit

entities, block entities, ticks, scheduled ticks, game rules, scoreboard, world border, saved data, raids, POI, chunk source, players, level data, random, events. Для кожного mutation path: direct/mailbox/global serialized/unsafe.

## 9. Entity audit (доповнення до #27, не переписувати)

Чи існують entity-related global structures, які обходять EntityMutationDispatcher? entity collections, player lists, tracking lists, UUID maps, global entity indexes, projectile lists, vehicle/passenger structures. P0/P1 → виправити, P2 → задокументувати.

## 10. Block / BlockEntity audit

LevelChunk.blockEntities, tickersInLevel, block entity ticking structures, chunk block state structures, neighbor notification structures, scheduled block/fluid ticks. **Не повторити помилку #28**: BLOCK_WRITE і BLOCK_ENTITY_WRITE можуть мати логічну залежність — не робити поспішного split на незалежні mailbox messages, якщо vanilla-операція семантично атомарна. #28 лишається ROOT CAUSE UNKNOWN/DEFERRED без нового доказу.

## 11. Global collections

Таблиця: Object | Owner | Reader | Writer | Thread | Protection. Приклади: MinecraftServer.players/levels, Scoreboard, GameRules, Raids, CommandStorage, SavedData, WorldBorder, POI, Forge global managers, NestWorldCore registries.

## 12. Forge Event Audit

Event/callback-и з Server/Region/Netty/worker thread — чи handler мутує entity/block/global state/ServerLevel/player state. Небезпечний сценарій: RegionThread → Forge Event → mod assumes ServerThread → global mutation. Не виправляти blanket main-thread redirect — спочатку документувати.

## 13. Мод compatibility

Не аудитити всі 420 модів окремо — перевірити boundary contracts (Forge Event, Capability, Network, Entity/Level/Block/Chunk API, SavedData, Server API), якими моди взаємодіють із ядром.

## 14. Ownership assertions

Використати NestworldOwnershipAssertions (не створювати другу систему). Розглянути додаткові assertions для global/server state/shared collections, лише якщо дешево і без зміни production semantics.

## 15. Runtime validation (420-mod ATM9)

Test A: 3-4 активні regions. Test B: entity activity. Test C: block activity. Test D: block entities. Test E: cross-region activity. Test F: split/merge/handover. Test G: global state mutation (якщо candidate знайдено).

## 16. Stress test

1000+ cross-region operations з паралельними region ticks/entity mutations/block mutations/split-merge/handover. Збирати: exceptions, ownership violations, deadlocks, stuck regions, mailbox drops, duplicate/lost operations, crashes.

## 17. Заборонені зміни (без окремого design approval)

Повний Player Region Execution; зміна Netty threading; зміна ensureRunningOnSameThread; переписування Forge event system/Minecraft scheduler; розділення одного chunk між потоками; глобальний lock навколо Level; масова заміна collections на ConcurrentHashMap; "вирішення всього через synchronized"; NeoForge port; 1.16.5 port; повторне дослідження #28 без нового доказу.

## 18. Рівні пріоритету

- **P0 Critical**: state corruption/crash/deadlock/lost mutation/permanent stuck region, реально reachable з region execution → виправити.
- **P1 High**: реальна race, рідкісна/обмежений вплив → виправити після P0.
- **P2 Medium**: threading contract порушений потенційно, репродукції нема → instrumentation + documentation.
- **P3 Informational**: архітектурний smell без доведеного unsafe behavior → documentation only.

## 19. Deliverables

`docs/PHASE10_GLOBAL_STATE_AUDIT.md` з таблицею (ID/Class/Field/Category/Owner/Readers/Writers/Thread/Protection/Risk/Action), окремо P0/P1/P2/P3 findings, формально описаний Threading Contract (Region thread/Server thread/Netty thread/Worker thread/Global owner + дозволені переходи).

## 20. Definition of Done

```
[ ] весь NestWorldCore global state audited
[ ] vanilla global state audited
[ ] relevant Forge boundaries audited
[ ] static mutable state audited
[ ] global collections classified
[ ] ownership assigned
[ ] threading contract documented
[ ] known race fixes revalidated
[ ] no undocumented mutable global state remains
[ ] P0/P1 confirmed bugs fixed or explicitly deferred
[ ] runtime stress completed
[ ] ATM9 clean boot
[ ] ownership assertions = 0 violations
[ ] no new deadlocks
[ ] no mailbox loss
```

Не потрібно отримати "0 проблем" — чесний результат CLOSED NEGATIVE теж успішний.

## Наступний етап після #30

```
#30 Global-State Audit
          ↓
#31 Player Region Execution
          ↓
Player Input → Owner Region
          ↓
менше Main → Region mailbox
          ↓
повноцінна Region-Owned Gameplay
```

Ціль зараз — не "ще швидше", а зробити threading-контракт NestWorldCore достатньо строгим, щоб перенесення player execution не перетворилось на неконтрольований набір race conditions.
