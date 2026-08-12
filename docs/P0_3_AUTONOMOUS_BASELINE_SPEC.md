# P0.3/P1 — Автономний baseline без гравця

(Verbatim ТЗ from project owner, 2026-08-12, follow-up to P0/P0.1/P0.2's pollTask/DistanceManager
attribution work — see [[p0-regionthreadpool-mainthread-wait-audit]] memory.)

## Мета

Визначити, де саме в автономній генерації виникає затримка, не змішуючи її з:
- player ticket;
- login/join;
- client chunk requests;
- player movement;
- entity tracking навколо гравця.

Гравців під час тесту не використовувати взагалі.

## 1. Тестовий режим

Запустити ATM9-сервер: players = 0. Генерація запускається серверним тестовим механізмом:
`chunk requests → DistanceManager/ChunkMap → Tier-2/Bulk pipeline → worldgen → lighting → completion`.
Використовувати однаковий набір координат для повторних тестів. Не використовувати player tickets
для ініціації генерації.

## 2. Зафіксувати baseline

TPS, MSPT median/p95/p99/max, CPU process, CPU system, GC, heap.
Додатково: worldgen active tasks, chunkGenMaxConcurrent, WORLDGEN_SHARDS.

## 3. Основна телеметрія

Окремо: TIER2_SELECT_PROMOTE, TIER2_PROCESS, GENERIC_OTHER, WORLDGEN, LIGHTING, COMPLETION,
DistanceManager, ChunkMap, RegionThreadPool, RegionThread, pollTask, actual latch wait.
Для кожного: calls/tick, total_ms/tick, avg, max, p95. Без повних O(n) сканів для telemetry.

## 4. TIER2_SELECT_PROMOTE (кандидат №1)

select_time, selected_count, examined_count, queue_size. Цікавить співвідношення
`examined / selected` — низьке = ефективний top-N; високе (≈queue_size) = поганий алгоритм.

## 5. GENERIC_OTHER

Розкласти: CompletableFuture, ChunkTaskPriorityQueueSorter, ProcessorMailbox, worldgen completion,
lighting completion, NestWorld completion. Мета — відокремити реальну вартість worldgen від
вартості нашого scheduler/bookkeeping.

## 6. RegionThreadPool

Без гравця це особливо корисно. Порівняти region_tick_avg/p95/max, poll_avg, actual_wait_avg,
poll_calls/tick. Підтвердити гіпотезу: main thread не стільки чекає регіони, скільки виконує
власні queued tasks через pollTask().

## 7. syncGhostZones

ghostzones_calls, ghostzones_ms, border_band_chunks, total_loaded_chunks, border_ratio. Перевірити
залежність: regions↑ → border-band↑ → syncGhostZones time↑ (positive feedback loop = окремий P1).

## 8. nestworldHasPlayerTicket (контрольний тест)

Гравців немає → очікування player tickets≈0, cost≈0. Якщо ні — сильний сигнал, що проблема не в
player workload, а в самій структурі DistanceManager.

## 9. Серія навантажень

TEST A: 16 chunks, B: 64, C: 256, D: 1024, E: 4096. Для кожного: generation time, chunks/sec,
MSPT p95/p99, TIER2_SELECT_PROMOTE, worldgen, completion. Головна метрика: chunks/sec.

## 10. Масштабування

chunkGenMaxConcurrent = 1/2/4/8 (WORLDGEN_SHARDS фіксований). Окремо WORLDGEN_SHARDS = 1/2/4/8
(concurrency фіксований). Мета: чи генерація справді масштабується.

## Критерій успіху

Для кожного етапу конвеєра (bulk request → Tier2 selection → worldgen → lighting/completion →
chunk ready) мати реальний вимірюваний час, не припущення.

## Важливе обмеження

Не робити поки Phase 2 RegionChunkScheduler. Спочатку чиста крива chunks/sec vs concurrency:
якщо concurrency 4→8 дає ~2x при відповідному зростанні CPU — є сенс піднімати паралелізм;
якщо приріст плоский (~1.05x) — проблема не в concurrency cap, копати сам pipeline.

Тест виконується повністю автономно, без гравця і без втручання клієнта.
