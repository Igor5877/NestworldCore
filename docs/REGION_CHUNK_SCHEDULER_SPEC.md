# ТЗ: Region-Owned Chunk Scheduler / асинхронна генерація чанків NestWorld

Отримано від власника проєкту 2026-08-11, у відповідь на живий інцидент (DistanceManager
validator rebuild spike, TPS 18.1, MSPT p99=670ms, backlog=20,508) — див. проєктну пам'ять
[[validator-rebuild-unbounded-scan]] та [[distancemanager-tier1-volume-freeze]] для повної історії
двох попередніх, вужчих фіксів того ж класу бага в поточній `DistanceManager`-архітектурі.

**Статус: SPEC-ONLY, реалізація не розпочата.** Це велика архітектурна ініціатива (повна заміна
`chunksToUpdateFutures`/`DistanceManager`-підходу на event-driven, region-owned, priority-based
scheduler), яка вимагає власного цілісного циклу дизайну (Gemini review) → поетапна реалізація →
стрес-тести → soak на ATM9, за встановленою в цій сесії методологією. Не почато відразу, бо на
момент отримання ТЗ йшов активний деплой вузького, вже затвердженого фіксу під час живої сесії
гравця.

---

## 1. Мета

Перебудувати поточну систему завантаження та генерації чанків так, щоб chunk scheduling і основна
робота з backlog більше не виконувались на головному Server thread. Власник роботи має
визначатися через `RegionTree`: найближчий/відповідальний регіон отримує chunk request і обробляє
його через власний scheduler.

Головна ціль: генерація та масове завантаження чанків не повинні блокувати main thread на сотні
мілісекунд або секунди навіть при backlog у десятки/сотні тисяч requests.

## 2. Проблема поточної реалізації

Поточна схема використовує `DistanceManager`, де накопичується великий `chunksToUpdateFutures`.
При backlog у десятки тисяч записів main thread виконує операції, які масштабуються від розміру
backlog:

```text
chunksToUpdateFutures
        ↓
DistanceManager
        ↓
O(N) classification / scan
        ↓
chunk processing
```

У реальному тесті: backlog 171,964 записи, `Server thread` потрапляв у `DistanceManager`,
спостерігались фризи 18-19 секунд, TPS падав, CPU при цьому не був повністю завантажений.

Додатково підтверджено: просте підняття `budget = 2/4/8` не вирішує проблему, якщо сам пошук
роботи залишається `O(N)`.

## 3. Нова архітектура

Окремий `RegionChunkScheduler` для кожного `WorldRegion`:

```text
                    Chunk Request
                         │
                         ▼
                  RegionTree lookup
                         │
                         ▼
                Responsible Region
                         │
                         ▼
              RegionChunkScheduler
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
           CRITICAL     HIGH       BULK
              │          │          │
              └──────────┼──────────┘
                         ▼
                Worldgen admission
                         │
                         ▼
                 Worldgen workers
                         │
                         ▼
                  Chunk completion
                         │
                         ▼
                 Region integration
```

## 4. Основний принцип ownership

Кожен chunk request повинен мати визначеного owner region:

```java
Region owner = regionTree.findRegion(chunkPos);
owner.chunkScheduler.enqueue(request);
```

Main thread не повинен після цього сканувати глобальну колекцію всіх pending requests.

## 5. Scheduler — категорії пріоритету

Priority-based queue на регіон, мінімальні категорії:

| Категорія | Приклад дистанції |
|---|---|
| CRITICAL | 0-2 чанки — потрібні гравцю прямо зараз |
| HIGH | 3-6 чанків — поблизу активного гравця |
| NORMAL | 7-12 чанків — звичайні chunk requests |
| LOW | 13+ чанків — віддалені requests |
| BACKGROUND | forceload / bulk / pre-generation |

Конкретні дистанції — конфігуровані.

## 6. Вимога до складності

Заборонити архітектурно `for (allPendingChunks)` на кожному серверному тіку. Scheduler повинен
працювати через priority queue / intrusive queue / bucketed queues / іншу структуру з
`O(1)`/`O(log N)` для enqueue/dequeue. Повний O(N) scan backlog на кожному tick неприпустимий.

## 7. Main thread — мінімум координації

```text
request → find owner region → enqueue → return
```

Заборонено переносити на main thread: повний scan pending chunks; вибір top-N перебором усього
backlog; перебудова повного snapshot backlog; масова worldgen admission; очікування завершення
worldgen; масове chunk generation integration.

## 8. Worldgen

`RegionChunkScheduler` передає роботу у СПІЛЬНИЙ worldgen pool (не окремий величезний pool на
регіон):

```text
Regions
  │
  ├── scheduler #0 ─┐
  ├── scheduler #1 ─┤
  ├── scheduler #2 ─┤
  └── scheduler #N ─┘
                    │
                    ▼
             Shared Worldgen Pool
```

## 9. Adaptive concurrency

Поточний жорсткий `chunkGenMaxConcurrent=4` не повинен залишатися єдиним механізмом керування.
Adaptive controller на вхідних метриках: CPU utilization, worldgen queue length, main-thread
MSPT, region MSPT, integration backlog, JVM heap pressure, GC pause time, completed chunks/sec.
Значення не hard-code — MSPT low + CPU low → increase concurrency; MSPT high / integration
backlog high → decrease concurrency.

## 10. Захист від runaway backlog

Кожен region scheduler: queue size, admission limit, priority, backpressure. Якщо backlog росте
швидше, ніж можна обробити: CRITICAL завжди приймається, HIGH приймається, NORMAL/LOW throttling,
BACKGROUND pause/reject. Гравець не повинен конкурувати з forceload за той самий ресурс.

## 11. Chunk completion

```text
Worldgen worker → Chunk result → ownerRegion.mailbox → Region thread → bounded integration
```

Інтеграція теж має бюджет (`maxIntegrationMsPerRegion`), щоб навіть тисячі готових chunks не
створили новий spike.

## 12. Гравець як найвищий пріоритет

```text
player chunk requests > nearby chunks > normal generation > forceload > background generation
```

Критично: 100,000 forceload requests + гравець летить → його chunks НЕ повинні чекати в тій самій
черзі.

## 13. Взаємодія з Region Free-Running

Сумісність з: FREE-RUNNING, barrier-synchronized regions, region split, region merge, entity
ownership, mailbox, boundary transfer. При split chunk requests повинні бути атомарно
перевласнені відповідному child region — заборонити ситуацію `chunk owner = B, scheduler owner =
A`.

## 14. Split / Merge

При split: (1) зупинити прийом нових requests у старий scheduler; (2) snapshot queues;
(3) класифікувати requests за новим RegionTree; (4) розподілити requests між children;
(5) перевірити ownership invariant; (6) запустити child schedulers. Merge — аналогічно у
зворотному напрямку.

## 15. Compatibility з vanilla/mod API

НЕ переписувати blindly весь `DistanceManager`. Визначити API boundary:

```text
Vanilla/mod chunk request → NestWorld adapter → RegionChunkScheduler
```

Зберегти семантику: player ticket, loading levels, chunk lifecycle, chunk unload, futures, ticket
priorities. Особлива увага модам, які напряму викликають `ServerChunkCache.getChunk()`,
`DistanceManager`, `ChunkMap`, `ChunkHolder`.

## 16. Thread safety — обов'язкові invariants

Chunk має одного scheduler owner; chunk має одного region owner; request не може одночасно бути у
двох queues; completion не може бути застосований двічі; split не може створити duplicate
request; merge не може втратити request. Debug guards: `ChunkSchedulerOwnershipGuard`,
`ChunkRequestDuplicateGuard`, `ChunkRequestMisrouteGuard`.

## 17. Діагностика

`/nestworld chunk` або розширення `/nestworld status`. Мінімально, per-region:

```text
Region #3
  queue: critical=12 high=38 normal=421 low=1820 background=14320
  generating=8  completed/sec=37  queue growth=+12/sec
  integration: pending=21 avg=2.4ms max=7.8ms
  CPU: worldgen=68%
  MSPT: region=14.2
```

Глобально: Chunk Requests (pending/generating/completed per sec/failed/cancelled), Worldgen
(workers/active/idle/concurrency/CPU), Integration (pending/avg/p95/p99).

## 18. Критичні алерти

`CHUNK_BACKLOG_GROWING`, `CHUNK_QUEUE_STARVATION`, `WORLDGEN_SATURATION`, `INTEGRATION_BACKLOG`,
`REGION_CHUNK_MISROUTE`, `DUPLICATE_CHUNK_REQUEST`, `MAIN_THREAD_CHUNK_BLOCK` (якщо main thread
проводить > X ms у chunk scheduling).

## 19. Профілювання

Інтеграція з існуючою Spark-подібною системою. Trace ID на кожен request:

```text
REQ-84931
player=Igor2  chunk=184,-92  region=12
queued:       12:31:02.123
admitted:     12:31:02.181
generation:   12:31:02.181–12:31:02.742
completed:    12:31:02.742
integrated:   12:31:02.751
total:        628ms
```

## 20. Телепортація до проблеми

`/nestworld debug chunk <x> <z>`, `/nestworld debug teleport <region>` — адміністратор
телепортується напряму до проблемного регіону/chunk.

## 21. Тести

Normal (100-500 chunks); Player flight (швидкий політ через unexplored terrain); Multiple players
(10/50/100 одночасно генерують terrain); Huge backlog (100k+ requests, main thread не зависає на
секунди); Forceload flood (100k background + player flight, player chunks мають priority); Region
split (активний scheduler + split, 0 duplicate/misroute/lost); Region merge (те саме); Modded
generation (ATM9 / важкий modpack); Failure (worldgen exception, request не блокує queue
назавжди).

## 22. Критерії успіху

0 O(N) backlog scan на кожному tick; main-thread chunk scheduling < 1ms у нормальному режимі;
немає freeze на 10-20с при backlog 100k+; player chunk requests не блокуються bulk/forceload;
worldgen використовує доступний CPU адаптивно; region MSPT залишається ізольованим; TPS ≈ 20 під
контрольованим навантаженням; 0 ownership violations; 0 duplicate requests; 0 misrouted requests;
split/merge не втрачають chunk requests.

## 23. Принцип, якого треба дотримуватися

Не оптимізувати черговий O(N) цикл. Якщо під час реалізації виникає рішення типу "давайте просто
сканувати 170,000 записів раз на секунду" — це НЕ фінальне рішення. Фінальна архітектура повинна
бути: event-driven + region-owned + priority-based + bounded integration + adaptive worldgen. Це
дозволить NestWorld використовувати свою головну перевагу — локалізацію навантаження по регіонах,
а не повертати chunk generation назад у централізований main-thread bottleneck.
