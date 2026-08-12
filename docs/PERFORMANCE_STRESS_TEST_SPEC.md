# ТЗ: постійний performance/stress-тест NestWorldCore

Отримано від власника проєкту 2026-08-12, у відповідь на серію знахідок тієї ж ночі
(distancemanager-tier1-volume-freeze, validator-rebuild-unbounded-scan,
syncghostzones-unbounded-scan-investigation) — усі одного класу бага: "unconditional O(n) per-tick
scan, real per-element cost, disguised as bounded/O(1)". Перетворює ATM9 з разового тестового
сервера на постійний живий стенд для систематичного полювання на цей та суміжні класи проблем.

**Статус: ІНФРАСТРУКТУРА В РОЗБУДОВІ.** Не разова задача — постійний процес.

---

## 1. Мета

Перетворити поточний тестовий Minecraft-сервер на постійний живий стенд для виявлення:
- O(n) / O(n²) операцій у hot path;
- необмежених щотікових сканувань;
- main-thread блокувань;
- region-thread synchronization bottleneck'ів;
- проблем chunk loading/worldgen;
- деградації продуктивності при збільшенні кількості регіонів;
- проблем із boundary/ghost-zone механізмами;
- прихованих race condition та deadlock;
- TPS/MSPT spike під реальним навантаженням.

Сервер НЕ використовувати як production. Допускаються експериментальні зміни ядра,
instrumentation та контрольовані рестарти.

## 2. Основний принцип

Не оптимізувати код лише за "avg". Для кожного підозрілого механізму збирати: invocations/tick,
objects processed/invocation, total cost/tick, max invocation cost, thread виконання, кількість
активних регіонів, завантажених чанків, pending chunk requests, border-band/ghost-zone чанків.

Ключова метрика: «скільки мілісекунд цей механізм реально додає до одного тіку.»

## 3. Пріоритет №1 — RegionThreadPool / RegionThread

Повний аудит: `RegionThreadPool`, `RegionThread`, `awaitLatch`, `pollTask`, region barrier,
wake/sleep механізмів, передачі задач між region threads, main-thread → region-thread
synchronization.

Перевірити чи може main thread `await`/`join`/`CountDownLatch.await()`/`Future.get()`/
`CompletableFuture.join()` чекати region-thread. Особливо шлях:
```
Server thread → RegionThreadPool.awaitLatch() → pollTask() → region task
```
Якщо main thread змушений чекати регіон — зафіксувати причину, тривалість, кількість
очікувань/tick, кількість регіонів що ще не завершили роботу.

**Заборонено**: не робити оптимізацію шляхом простого збільшення кількості потоків без доказу, що
bottleneck саме CPU.

## 4. Пріоритет №2 — BoundaryManager.syncGhostZones()

Повністю інструментувати (calls/tick, chunks scanned, block entities scanned, NBT serializations,
total_ms/tick, max_ms, region_count, border_band_chunks) — **вже частково зроблено** (`/nestworld
ghostzones`), продовжити.

Окремо перевірити: чи виконується NBT-серіалізація для block entity кожного тіку навіть тоді, коли
дані не змінилися (dirty-tracking замість повного щотікового serialization).

Перевірити feedback loop: більше навантаження → більше region split → більше region boundaries →
більше border-band chunks → більше syncGhostZones() → більше tick cost → ще більше навантаження.

Якщо підтвердиться — запропонувати incremental/delta-based механізм. **Не реалізовувати фікс до
завершення вимірювань.**

## 5. Пріоритет №3 — DistanceManager.nestworldHasPlayerTicket()

Продовжити аудит — **вже почато** (`/nestworld tickets`). Інструментувати: calls/tick, tickets
inspected/call, max tickets at position, total ticket lookup time/tick. Тестувати одночасно:
player loading, forceload, FTBChunks, інші chunk-loading sources. **Не робити висновок про
bottleneck за "max_tickets=2"** (перший знімок був під мінімальним навантаженням).

## 6. Chunk generation concurrency benchmark

Не змінювати `chunkGenMaxConcurrent` без окремого benchmark. Порівняти щонайменше concurrency =
4, 8, 12. Для кожного: chunks/sec, worldgen ms/chunk, MSPT, p95/p99 MSPT, CPU utilization, GC,
кількість waiting Worker-Main, TPS, кількість region-thread waits.

Критерій успіху: підвищення concurrency корисне лише якщо chunks/sec ↑ ТА одночасно TPS не
погіршується, p99 MSPT не вибухає, main/region synchronization не збільшується, GC не стає
проблемою.

## 7. Заборонений патерн

Кожен новий optimization patch перевіряти на «bounded output ≠ bounded work». Не допускати
`processFirstN()` якщо перед ним `for (allEntries) classify()`. Так само "budgeted batch +
unbudgeted snapshot creation" вважати потенційним performance bug (саме це і був корінь
validator-rebuild-unbounded-scan).

## 8. Постійний watcher

На тестовому сервері watcher залишається активним постійно. При інциденті автоматично зберігати:
timestamp, TPS, MSPT, p95, p99, active regions, loaded chunks, pending chunk requests, worldgen
concurrency, CPU, RAM, GC, і мінімум 5 jstack-семплів з інтервалом ~500-1000мс. Структура на
інцидент:
```
incident/
  <timestamp>/
    metrics
    jstack_1..5
```

## 9. Класифікація інцидентів

A — Main-thread CPU (Server thread → дорогий Java method)
B — Main-thread wait (Server thread → await/join/latch/future)
C — Region synchronization (RegionThread → barrier/latch/other region)
D — Worldgen (Worker-Main → levelgen)
E — Chunk loading (DistanceManager/ChunkMap/ChunkHolder)
F — GC / allocation (G1, allocation pressure)
G — Mod-specific (ComputerCraft, MineColonies, KubeJS, Mekanism, Botania, ...)

## 10. Benchmark scenarios

- **Test A** — чистий рух гравця по незгенерованій території: chunks/sec, TPS, MSPT, worldgen
  concurrency.
- **Test B** — кілька джерел chunk loading одночасно: player view distance + forceload +
  FTBChunks.
- **Test C** — region scaling: 10 → 20 → 40 → 80 → 160 регіонів, чи росте tick cost лінійно.
- **Test D** — border scaling: regions ↑, border chunks ↑, syncGhostZones cost ↑.
- **Test E** — heavy modpack (ATM9) — не оцінювати ядро лише на vanilla worldgen.

## 11. Основний критерій архітектури

Для кожного механізму: «Хто має виконувати цю роботу?» (Main thread / Region thread / Worldgen
worker / Background worker). Особливо небезпечні: `Main thread → O(n)` і `Main thread → wait for
RegionThread`. Якщо операцію можна перенести на відповідний region/background worker без
порушення Minecraft thread-safety — підготувати окремий дизайн.

## 12. Порядок внесення змін (обов'язковий для КОЖНОГО fix)

1. Reproduce
2. Instrument
3. Capture baseline
4. Design
5. Gemini/code review
6. Implement
7. Compile
8. Deploy to test server
9. Stress test
10. Compare against baseline
11. Keep/revert

Не приймати fix лише тому, що один профіль після рестарту виглядає краще. Мінімально потрібне
порівняння: before vs after під однаковим навантаженням.

## 13. Backlog

**P0**
- [ ] Повний аудит RegionThreadPool/RegionThread.
- [ ] Виявити всі main-thread waits.
- [ ] Виявити всі region barrier waits.
- [ ] Визначити, чи chunk loading може блокувати main thread.

**P1**
- [ ] Повна статистика syncGhostZones() (частково зроблено).
- [ ] Визначити реальний feedback loop region boundaries.
- [ ] Перевірити можливість incremental ghost-zone sync.

**P2**
- [ ] Розширити статистику nestworldHasPlayerTicket() (частково зроблено).
- [ ] Стрес-тест із кількома chunk-loading sources.

**P3**
- [ ] Benchmark chunkGenMaxConcurrent.
- [ ] Визначити оптимальне значення для 20 CPU threads.
- [ ] Перевірити, чи можна безпечно збільшити concurrency.

## 14. Головна вимога

Цей сервер залишається постійним тестовим стендом NestWorldCore. Не потрібно боятися контрольованих
експериментів, рестартів або навмисного навантаження. Але кожна зміна повинна залишати після себе
вимірюваний результат:

**ЩО БУЛО → ЩО ЗМІНИЛИ → ЩО ВИМІРЯЛИ → ЩО СТАЛО → ЧИ СТАЛО КРАЩЕ**

Основна мета — не просто підняти TPS у конкретному тесті, а знайти архітектурні місця, де
NestWorldCore має необмежену роботу в hot path або змушує один потік чекати інший.
