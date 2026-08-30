# Aggressive Forge Startup Optimization — spec

Full ТЗ as authored by the user on 2026-08-26. Goal: turn NestWorldCore's cold-boot path into a
dependency-aware scheduler — Forge keeps its guaranteed ordering wherever that ordering is a real
contract, but every CPU core does useful, *proven-safe* work in parallel with it. This is the
follow-up project to the already-shipped registry-bake parallelization (see project memory
`registry-bake-parallel-init-cache.md`), which is the validated baseline below.

## 1. Мета

1. максимально скорочує час від запуску JVM до готовності сервера;
2. використовує всі доступні CPU-ресурси під час startup;
3. не порушує гарантований Forge порядок виконання там, де він необхідний;
4. не вимагає модифікації сторонніх модів;
5. має fallback на оригінальну Forge-поведінку;
6. не переносить небезпечний мод-код у паралельне виконання без доведення thread-safety;
7. дозволяє виконувати незалежну підготовчу роботу паралельно;
8. дозволяє частину роботи прибрати з critical startup path;
9. окремо оптимізує spawn-chunk generation.

## 2. Поточний baseline

```
Було:       ~92–93 с
Стало:      ~78 с
Виграш:     ~15 с
```

Джерело виграшу: `GameData$BlockCallbacks.onBake()` split у два проходи — послідовне присвоєння
BlockState id (`blockstateMap.add`, MUST stay ordered) відокремлено від `state.initCache()`
(доведено pure-per-state, тепер `parallelStream()`). Live-validated двічі, ідентичний результат,
197-мод пак. Деталі: project memory `registry-bake-parallel-init-cache.md`.

Встановлено другим profiling-раундом:

- `Util.blockUntilDone()` / `WorldLoader.load()` — НЕ великий постійний bottleneck.
- datapack loading — НЕ головна причина startup.
- повторно виявляється зона `Block$1.load()` / Guava `LoadingCache` (contention, побічний ефект
  паралелізації initCache — вже задокументовано як known imperfection).
- частина mod initialization йде через `ModWorkManager.SyncExecutor`.
- моди типу Draconic Evolution виконують значну роботу прямо в конструкторах.
- пряме паралельне виконання таких конструкторів небезпечне через Forge dependency/order semantics.

## 3. Основний принцип — НЕ робити

Заборонено просто перетворити послідовний ланцюжок модів на "паралельний for-loop" без доведеної
відсутності `dependsOn`/`loadsAfter`/registry dependencies/side effects/static-init dependencies/
implicit Forge lifecycle dependencies.

## 4. Нова модель startup

```
Forge startup
     │
     ▼
Dependency-aware scheduler
     │
┌────┴─────┐
│          │
CRITICAL   PREPARATION
│          │
▼          ▼
Forge      Worker pool
ordered    (cache/bake/data/chunk preparation)
work       │
│          │
└────┬─────┘
     ▼
synchronization
     ▼
Server READY
```

## 5. Startup phases

- **Phase 0 — JVM/Forge bootstrap**: залишається vanilla-close, безпечним.
- **Phase 1 — Mod discovery**: discovery/dependency graph/metadata/loading order. НЕ паралелізувати
  довільно.
- **Phase 2 — Dependency graph**: NestWorldCore будує DAG (`dependencies`/`loadsAfter`/`loadsBefore`/
  required mods/Forge lifecycle constraints) → результат: групи незалежних задач.

## 6. Independent task groups

Якщо `A→B` і `C→D` незалежні одна від одної, і кожна конкретна операція позначена
safe-to-parallelize — готувати їх на різних workers.

## 7. Safe/unsafe classification

Для кожної операції:

| Категорія | Означення | Паралелізація |
|---|---|---|
| SAFE | deterministic, pure, no global state, no registration-order dependency, no side effects, no thread-confined objects | дозволена |
| READ_ONLY | читає вже finalized state | дозволена за умови thread-safe доступу |
| ORDERED | має виконуватись у Forge order | синхронно |
| UNKNOWN | безпечність не доведена | **default → ORDERED**, заборонено паралелити |
| UNSAFE | гарантовано належить конкретному Forge thread | заборонено |

## 8. Parallel Preparation Pool

Окремий startup worker pool, який НЕ замінює Forge `SyncExecutor`:

```
Forge SyncExecutor            NestWorld Startup Pool
   │ ordered lifecycle              │
   ▼                          ┌─────┼─────┬─────┐
                               ▼     ▼     ▼     ▼
                              CPU1  CPU2  CPU3  CPU4
```

Використовується тільки для: pure calculations, cache precomputation, immutable data construction,
незалежний preprocessing, chunk generation, інші доведені thread-safe задачі.

## 9. CPU scheduler

Враховує: доступні CPU, CPU utilization, активні worker tasks, Forge main-thread latency. Режими:
`CONSERVATIVE` / `BALANCED` / `AGGRESSIVE` / `MAXIMUM`. Benchmark target: `MAXIMUM`.

## 10. Guava LoadingCache optimization

Профілювати окремо `Block$1.load()` / `LoadingCache.get()`: cache hit/miss, `load()` duration, lock
wait, total cache time, кількість звернень, duplicate calculations. (Це та сама contention точка вже
знайдена побічно при initCache-паралелізації — тут формалізується як окремий вимірюваний напрямок.)

## 11. Cache prewarming

Якщо cache data доведено deterministic + immutable + залежить лише від finalized registry state:

```
Registry finalized → collect required keys → parallel cache generation (CPU1..N) → cache ready
```

Звичайний Forge lifecycle після цього отримує вже готові результати.

## 12. Заборонити duplicate computation

Instrumentation для пошуку "input X → calculate → result discarded → same input X → calculate
again". Лог на кожну важку операцію: operation, input identity/hash, start, finish, result reused?

## 13. Persistent Startup Cache

Persistent cache між рестартами, ключований мінімум за: MC version, Forge version, NestWorldCore
version, mod list, mod versions, relevant config, registry signature.

```
startup-cache/<environment-hash>/{block-data.bin, support-data.bin, preparation.bin}
```

hash mismatch → rebuild; match → load. Не повинен змінювати observable Forge behavior.

## 14–17. Deferred Initialization

`CRITICAL` vs `DEFERRED` за `required_before_server_ready`. Deferred tasks запускаються ПІСЛЯ
`Server READY`, мають dependency barrier (стани: `NOT_STARTED`/`QUEUED`/`RUNNING`/`READY`/`FAILED`/
`CANCELLED`) — споживач блокується (`await`), якщо результат ще не готовий. Кожна оптимізація
зобов'язана мати fallback: `READY→use` / `FAILED→synchronous calculation` / `TIMEOUT→synchronous
calculation`. Ніколи crash сервера через worker-помилку.

## 18. Forge SyncExecutor

НЕ замінювати глобально. Оптимізувати лише: queue overhead, unnecessary scheduling/futures, duplicate
waits, redundant synchronization, idle gaps, зайві context switches. Execution semantics лишаються.

## 19. Experimental Parallel Lifecycle

`startup.experimental.parallelLifecycle=true` — незалежні lifecycle tasks паралельно, з dependency
validation + execution graph + timeout + fallback + детальним logging. **НЕ default до завершення
compatibility tests.**

## 20–23. Spawn Chunk Generation

Окремий напрямок: chunk scheduler з кількома workers замість послідовного `chunk1→chunk2→chunk3`.
`chunkGenBudget` (вже існує) розширити параметрами: `maxConcurrentChunkTasks`,
`maxChunkGenTimePerTask`, `maxTotalStartupWorkers`, `maxQueueDepth` — запобігати CPU starvation,
memory explosion, runaway queue, gen deadlock.

Профілювати stage-by-stage (ProtoChunk/Heightmap/Noise/Terrain/Surface/Carvers/Structures/Features/
Block population/Lighting/Serialization) — кожна: main-thread required? thread-safe?
parallelizable? dependency? **Заборонено fake parallelism** (просто закинути весь chunk gen в
executor без per-stage класифікації, thread safety доведена тестом).

## 24–26. Startup critical path profiler

Власний profiler, що показує total + breakdown по фазах (bootstrap/mod loading/registry/cache/setup/
chunk gen/other) ТА CPU utilization по часових вікнах. Будує dependency graph задач і визначає, які
реально лежать на critical path до `SERVER_READY` (важливіше за простий "top CPU methods" список).
Кожен тестовий запуск логує: JVM start, Forge start, mod loading start/end, registry start/end,
datapack start/end, server startup start, chunk gen start, first spawn chunk ready, Server READY,
first player join.

## 27. Benchmark matrix

Test A vanilla / Test B невеликий modpack / Test C типовий NestWorld modpack / Test D максимальний
production modpack / Test E cold cache / Test F warm cache.

## 28. Compatibility testing (після кожної оптимізації)

startup, registry, recipes, tags, capabilities, entities, blocks, items, world loading, chunk
generation, player join, save, restart, shutdown.

## 29. Crash safety

Worker exception → capture → log → mark task FAILED → fallback. Ніякого silent failure.

## 30. Debug режим

`startup.debug=true` → `[Startup] Task scheduled/started/finished/deferred/waited`, `Cache hit/miss`,
`Fallback`.

## 31. Основна вимога сумісності

Дозволено змінювати: час виконання, scheduling, момент фактичного обчислення.
**Заборононо змінювати**: registry IDs, object identity (де це контракт), mod dependency order,
lifecycle semantics, game logic, worldgen результат.

## 32. Acceptance Criteria

| # | Критерій |
|---|---|
| AC-1 | Startup не повільніший за baseline (78s) |
| AC-2 | < 78s на поточному benchmark environment |
| AC-3 | Ціль: < 60s |
| AC-4 | Aggressive target: < 50s, якщо profiling підтверджує технічну можливість |
| AC-5 | Жодних registry/order regressions — перевіряти проти `registry-baselines/registry-truth-*.txt` |
| AC-6 | Production modpack повністю запускається |
| AC-7 | Cold-cache і warm-cache тестуються окремо |
| AC-8 | Worker failure не призводить до silent corruption |
| AC-9 | Spawn chunks не створюють deadlock |
| AC-10 | Після запуску: player join / world interaction / save / restart працюють нормально |

## 33. Порядок реалізації

```
1. Startup profiler
2. Critical-path graph
3. Cache instrumentation
4. Duplicate-work detection
5. Parallel pure preparation
6. Cache prewarming
7. Persistent startup cache
8. Deferred initialization
9. Dependency barriers
10. SyncExecutor overhead optimization
11. Spawn chunk scheduler
12. Chunk-stage parallelization
13. Experimental parallel lifecycle
```

**Ключове**: не починати з SyncExecutor parallelization. Почати з critical-path profiler — спершу
треба знати, де реально знаходяться секунди, а не просто "знайти ще один повільний метод".

## Status (оновлено 2026-08-26)

- **Phase 1 (startup profiler) — DONE.** `net.nestworld.startup.NestworldStartupProfiler`,
  milestone timeline + CPU-utilization-by-window report, live на 197-мод паку.
- **Phase 2 (critical-path graph) — DONE для знайденого провалу.** SIGQUIT-дампи в CPU-dip вікні
  `mod_gather_init` виявили: (a) реальну модову роботу (UNSAFE/ORDERED, не займатись), і (b) справжній
  vanilla-алгоритмічний баг — `StateDefinition`/`StateHolder` використовували `Map` як ключ хешмапи
  (treeification). Пофіксовано (ключ → `List<Comparable<?>>`), live-валідовано: mod_gather_init
  -46% (30.6s→16.4s), загальний boot -22% (66.9s→52.3s). Разом із registry-bake фіксом це вже ~35%
  сумарного скорочення від першого baseline.
- **Phase 3 (cache instrumentation) — DONE.** `SHAPE_FULL_BLOCK_CACHE.concurrencyLevel` 4→64
  (безпечне tuning, застосовано на основі попередньо зафіксованого contention). Live-тест ЦІЄЇ сесії
  виміряного прискорення НЕ підтвердив (чесний негативний результат) — фікс залишено як
  zero-downside, ефект на рантайм region-потоках не перевірявся.
- **Phase 4 (duplicate-work detection) — DONE, closed minor.** 915 recipe-parse-then-discard подій
  знайдено (сумісні рецепти для відсутніх модів), але це лише ~1-2s сплеск всередині 15.1s
  datapack_load фази — фікс вимагав би чіпати vanilla RecipeManager або чужий мод-контент, поза
  скоупом. Не переслідувалось далі.
- **Phase 5 (parallel pure preparation) — одна ціль випробувана, відкочена.** DataFixers.getDataFixer()
  прогрів на фоновому потоці з main() (JVM class-init locking робить це provably safe). Зловив
  реальний баг (ordering відносно SharedConstants.tryDetectVersion() — невдалий `<clinit>` назавжди
  "отруює" клас для всього JVM), пофіксив. Після фіксу — 2 боути показали ГІРШИЙ час, але
  повторний тест БЕЗ зміни показав таку саму варіацію (~52.3-55.2s на нібито ідентичному коді) —
  ефект менший за шум тест-рігу. Відкочено, не варте продовження без набагато більших вибірок.
  Повний SAFE/UNSAFE фреймворк + окремий worker pool — НЕ побудовано.

## Ревізія плану після Phase 5 (user, 2026-08-27)

Ключовий висновок Phase 5: не можна будувати optimization infrastructure без конкретного кандидата,
який має ДОСТАТНІЙ бюджет у critical path (≥2-3s), і не можна довіряти 1-2 прогонам при шумі рігу
~52.3-55.2s (~6%). Переглянутий порядок:

- **Phase 6.5 (variance elimination)** — репрезентативний бенчмарк-харнес: мінімум 5 прогонів
  baseline + 5 прогонів patched, порівнювати MEDIAN, не один прогін. Будується ПЕРШИМ, бо без
  нього жодне майбутнє "виміряне прискорення" не є надійним.
- **Phase 6 (cache candidate discovery)** — не "зробити prewarming", а спершу знайти кеш, який
  реально вартий уваги: total accesses/hit%/miss%/load() total+avg+max time/contention/
  unique keys/чи обчислюваний до першого використання. Жорсткий критерій: якщо кеш не займає
  ≥2-3s CPU/critical-path або не створює суттєвого contention — не чіпати. Шукати
  LoadingCache/computeIfAbsent/HashMap-as-lazy-cache/Supplier-memoization, що масово
  використовуються під час старту. Фільтр на прогрів: якщо ключі відомі одразу після registry
  finalized і обчислення pure — пробувати; якщо ключ залежить від мод-конструктора/є side effects/
  Forge ordering required — не чіпати.
- **Phase 7** — свіжий критичний профіль фази `datapack_load`.
- **Phase 8** — свіжий критичний профіль spawn/world-initialization фази.
- **Phase 9** — оптимізація найбільшої з Phase 7/8 знахідок.
- Лише якщо після цього профіль покаже, що Forge lifecycle (SyncExecutor) досі домінує —
  повертатись до deferred/parallel lifecycle. **SyncExecutor поки НЕ чіпати.**

Дух: після Phase 2 (-46% на mod_gather_init через точковий data-structure фікс) — правильні
алгоритмічні знахідки цінніші за ризиковану паралелізацію Forge lifecycle. Полювати на наступне
"StateDefinition-рівня" рішення, а не на черговий executor/cache tuning.

- **Phase 6.5 (benchmark harness) — DONE.** `boot_bench.sh`/`boot_bench_report.py`, median-of-5,
  baseline 54.81s (stdev 0.82s). Стандартне правило: усі майбутні claim'и — median ≥5 прогонів.
- **Phase 6 (cache discovery) — DONE, closed negative.** Жоден кеш не перетинає поріг ≥2-3s.
- **Phase 7 (datapack_load profile) — DONE, реальна знахідка, ВІДКОЧЕНО.**
  `Files.exists()` на UnionFileSystem кидає повний stack-trace через NoSuchFileException на кожен
  "не знайдено" (LootUtil.determineSource сканує ~200 pack-шарів на кожну loot-таблицю). Корінь
  (`securejarhandler`/UnionFileSystem) — бінарна залежність, непатчабельна. Спроба кешу в
  `PathPackResources.getRootResource()` (форджевий файл, патчабельний) зламала реальний ресурс
  (HostileNeuralNetworks NPE "Missing builtin model tier: faulty") — Files.walk() не бачить усі
  union-шари так, як точкові Files.exists()-перевірки. Відкочено негайно, підтверджено чисто.
- **Phase 8 (spawn/world-init profile) — DONE, closed negative.** 14/14 дампів — кожен інший сайт,
  жодного повторення. Легітимна суміш диск I/O + генерація, нічого нового фіксабельного.
- **Phase 9 — немає кандидата** з Phase 7/8 (обидва закрито негативно/відкочено).
- Кроки 10-13 — deprioritized, див. секцію нижче.

## Startup Optimization v1 — ЗАВЕРШЕНО (user verdict, 2026-08-27)

**Baseline locked**: median **54.81s** (stdev 0.82s, 5-run [[phase65-benchmark-harness]]), вниз від
початкового ~93s (**-40%**). Це не "здалися" — велике безпечне architectural bottleneck (StateDefinition
HashMap-treeification) знайдено й пофіксовано; кожна з наступних 6 гіпотез (Guava cache tuning,
duplicate-work, DataFixer prewarm, cache discovery, datapack UnionFileSystem workaround, spawn/
world-init) або дала нуль-ефект, або чесно закрита негативно, або відкочена після реальної
регресії. 14/14 випадкових семплів у spawn/world-init window влучили в 14 РІЗНИХ місць — це не
"профайлер нічого не знайшов", це сигнал, що залишковий startup — широкий набір легітимної роботи,
не один патологічний hotspot.

**Phase 10-13 (SyncExecutor tuning, spawn chunk scheduler, chunk-stage parallelization,
experimental parallel lifecycle) — НЕ розпочинати без нового профільного кандидата.** Ризик
непропорційний очікуваній вигоді при поточному стані доказів.

**Benchmark harness стає стоячим regression-check**: кожен майбутній великий патч NestWorldCore
(включно з runtime-роботою) варто прогонити через `boot_bench.sh`/`boot_bench_report.py`
(median-of-5+) — якщо якийсь runtime-фікс раптом підняв startup з 54.8s до, скажімо, 62s, це
одразу видно.

**Залишений відкритим напрямок — "algorithmic archaeology"** (не "який метод повільний", а):
1. **Allocation storms**: 1 операція → сотні тисяч/мільйони short-lived object allocations → GC
   тиск. Потенційний другий StateDefinition-рівня баг, знайти можна лише через
   allocation-профайлер (не CPU stack samples).
2. **Quadratic/superlinear алгоритми**: `for A: for B: search()`, `list.contains()` усередині
   великого циклу — там, де мав би бути HashSet/HashMap/indexed lookup.
3. **Serialization/codec overhead**: decode→temp objects→copy→convert→copy знову (codec decoding,
   chunk deserialization, PalettedContainer вже спостерігались у Phase 8 дампах, але лише як CPU
   stack samples, не allocation-профіль).

Метрика успіху для будь-якого майбутнього кандидата з цього списку — НЕ лише wall-clock:
```
median startup / CPU time / allocation count / allocated bytes / GC time / peak RSS
```
Наприклад, -1.1s wall-clock але -5GB allocated + -0.9s GC — сильний кандидат навіть з малим
секундним виграшем.

**Пріоритет ресурсів зміщено на runtime** (WorldRegion/RegionThread/RegionTree/chunk budget/
parallel entity ticking) — вищий практичний ROI для мети 200 гравців/200+ модів/складний worldgen,
ніж подальше копання в одноразовому боут-шляху. Не розпочато цим повідомленням — окреме рішення
користувача, коли буде готовий.
