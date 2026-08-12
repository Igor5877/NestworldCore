# ТЗ: P0 RegionThreadPool — усунення main-thread залежності від найповільнішого регіону

Отримано від власника проєкту 2026-08-12, у відповідь на [[p0-regionthreadpool-mainthread-wait-audit]]
(перший P0-аудит: підтверджено 5 місць блокування main thread на region-потоках, спільна
30-секундна стеля, timeout не вбиває повільний регіон). Частина постійного стенду
[[perf-stress-test-standing-infra]].

**Мета**: прибрати залежність тривалості main-thread tick від найповільнішого регіону та
виключити 30-секундне блокування.

## Цільова модель

```
┌─ Region A ──┐
              ├─ Region B ──┤
Main Thread ──┼─ Region C ──┼──► collect results
              ├─ Region D ──┤
              └─ Region E ──┘
                     │
               timeout/error
                     ▼
           тільки проблемний region
           ізолюється, а не весь tick
```

## Фаза 1 — baseline (ПОТОЧНА)

Перед змінами додати телеметрію: `region_tick_time`, `region_wait_time`,
`region_poll_task_time`, `region_timeout_count`, `region_timeout_duration`,
`regions_completed`, `regions_pending` — окремо для entity round, scheduled ticks, random
ticks, block entities, block events.

**Критично**: розділити час виконання регіону і час, який main thread провів у `awaitLatch()`
(раніше конфліковані в одному "wait" числі — сама ця проблема вже знайдена в P0-аудиті).

## Фаза 2 — окремі бюджети

Не один `TICK_WAIT_LIMIT_NANOS = 30s` для всіх фаз. Окремі: `ENTITY_WAIT_BUDGET`,
`SCHEDULED_TICK_WAIT_BUDGET`, `RANDOM_TICK_WAIT_BUDGET`, `BLOCK_ENTITY_WAIT_BUDGET`,
`BLOCK_EVENT_WAIT_BUDGET`. Конфігураційні значення, не хардкод.

## Фаза 3 — безпечний timeout

Явна state machine:
```
RUNNING
   │
   ├── COMPLETED ──► COMMIT
   │
   └── TIMEOUT ────► ISOLATE
                       │
                       ├─ STOP accepting new work
                       ├─ finish/abort safely
                       └─ report fault
```
Спочатку довести коректність ізоляції, потім оптимізувати timeout.

## Фаза 4 — прибрати глобальний latch (ДОВГОСТРОКОВО, НЕ реалізовувати одразу)

`awaitLatch(allRegions)` → `region → future/result`, main thread чекає лише реально
необхідне для tick. Перед реалізацією: встановити, які дані між регіонами і main thread мають
жорсткі tick-order залежності.

## Фаза 5 — correctness audit (важливіше за приріст TPS)

Entity mutation, block entity mutation, scheduled ticks, block events, chunk state, ticket
state, neighbor updates, entity transfer між регіонами, chunk unload/load, `setRemoved()` для
BlockEntity, visibility між region/main threads.

## Порядок пріоритетів на постійному стенді

P0 RegionThreadPool → P1 GhostZones (dirty-driven update замість щотікового повного скану,
+перевірка feedback loop split→more borders→more ghost work→higher tick cost→more
imbalance→more splits) → P2 DistanceManager (nestworldHasPlayerTicket → O(1) структура з
інваріантом "position ∈ exactly one classification set", перевіреним у debug/test режимі) →
P3 worldgen scheduler/concurrency (chunkGenMaxConcurrent НЕ піднімати лише через вільний CPU —
живі дані вже показують: CPU~23%, 20 потоків, Worker-Main здебільшого paused, worldgen реально
виконується, але MSPT p95=583ms і TPS падає до 9.48 — це вказує на DRUGY бутилнек
(main-thread/region synchronization), не на брак worker-паралелізму).
