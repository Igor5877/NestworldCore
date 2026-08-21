# ТЗ: NestWorldCore — високопродуктивний chunk generation pipeline + повна observability

(2026-08-21, live ATM9 session — записано дослівно від project owner після ночі watchdog-
крашів/пампбюджет-фіксу/Prometheus-модуля. Реалізація йде фазами, Phase 1 — telemetry
foundation, без adaptive controller. Phase 6 (Region Chunk Scheduler) свідомо відкладено.)

## 0. Мета

Мета цього етапу — **максимально прискорити генерацію чанків у NestWorldCore**, не
відмовляючись від глобального Minecraft tick, не переходячи на Folia-модель і не
переносячи зараз весь chunk integration на регіональні потоки.

Ключова ідея:

> **Worker-side генерація повинна працювати настільки паралельно, наскільки це дозволяє
> CPU, але ніколи не повинна неконтрольовано створювати backlog роботи, яку main thread
> не здатний інтегрувати.**

## 1. Поточна архітектура (не ламати на першому етапі)

```
PredictiveChunkGen -> addRegionTicket() -> DistanceManager.runAllUpdates()
  -> Tier 1 / Tier 2 -> updateFutures() -> Generation pool
  -> [NOISE, SURFACE, CARVERS, FEATURES] -> готовий ProtoChunk
  -> Main-thread integration (ProtoChunk -> LevelChunk, ChunkMap, entities,
     block entities, neighbour updates, world state)
```

Глобальний tickCount, глобальний тік, worker-side generation, vanilla-compatible
main-thread integration, mailbox/region architecture — усе залишається.

## 2-4. Основна проблема і цільова архітектура

Не можна просто підняти `chunkGenMaxConcurrent` і вважати генерацію швидшою — throughput
може падати після певної точки, MSPT росте нелінійно. Потрібен весь feedback loop:
Promotion pipeline -> Admission Controller (concurrency limit, backlog limit, CPU/MSPT
feedback) -> generation workers -> Integration backlog -> Main thread.

## 5. Три різні bottleneck (розділити явно)

- **5.1 Promotion**: ticket -> DistanceManager -> updateFutures(). Метрики: pending,
  admitted, completed, avg_ms, p99_ms, pump_ms, pump_deadline_hits. (Вже є з сьогоднішньої
  роботи.)
- **5.2 Generation**: updateFutures() -> worker pool -> реальна генерація терену.
  Метрики: active, pending, completed, failed, deferred, throughput, duration.
- **5.3 Integration**: generated -> ready -> main-thread integration. Метрики: pending,
  completed, rate, duration, p95, p99.

Мета — вміти відрізнити "generation slow" від "generation fast but integration slow".

## 6-16. Generation Admission Controller (Phase 3-4, НЕ Phase 1)

`GenerationAdmissionController` вирішує, чи можна запустити ще одну generation task,
враховуючи maxConcurrency/active/pending/integration backlog/CPU/MSPT/generation
latency/server health. Жорсткі hard limits (`hardMaxConcurrency`,
`hardMaxPending`, `hardMaxIntegrationBacklog`), які adaptive-алгоритм ніколи не обходить.
Два режими: STATIC (поточна поведінка, для бенчмарків) і ADAPTIVE (сам шукає оптимум).
Adaptive: sampling window 1-2с, `+1` при CPU/MSPT/backlog у нормі і черга непорожня,
`-1` швидше при перевантаженні, emergency `concurrency *= 0.5` при MSPT > 100ms (з нижньою
межею). Hysteresis: збільшення після ≥5с стабільних умов, зменшення — вже після ≥2с.

## 12-14. Integration Backpressure + рівні

GREEN (<25% backlog) / YELLOW (25-50%, не збільшувати) / ORANGE (50-75%, зменшувати) /
RED (75-100%, сильно обмежувати bulk/predictive admission) / CRITICAL (>= hard limit,
нові bulk/predictive tasks блокуються). **Player-critical path (Tier 1 PLAYER > 1a > 1b
> Tier 2 bulk > predictive) не повинен ламатися через backpressure** — контролер
інтегрується з існуючою Tier-системою, не обходить її.

## 17-19. Worker pool telemetry + per-stage timing

Pool: configured/active/idle/pending/completed/failed/rejected. Stage-level timing
(NOISE/SURFACE/CARVERS/FEATURES/STRUCTURES/BIOME/LIGHTING/POST_PROCESS) — почати з
coarse-grained "generation total", додати per-stage тільки якщо не ламає pipeline без
глибокого втручання у Forge internals.

## 20. Оптимальний concurrency

Не максимальний — **найбільший throughput, при якому server health лишається в межах**.
Приклад: throughput росте 4->8, плато на 8-9, деградація MSPT далі — оптимум ≈ 8, не 9,
навіть якщо hard limit дозволяє більше.

## 21-22. Нові API snapshots (розширення сьогоднішнього net.nestworld.api)

`ChunkGenSnapshot` доповнити generation/integration лічильниками (generatedTotal,
generationFailedTotal, generationDeferredTotal, generationAvg/P50/P95/P99/MaxMs,
generationThroughput, integrationPending/Completed/Avg/P95/P99Ms, genPoolActive).
Новий `GenerationControllerSnapshot` (configuredConcurrency, effectiveConcurrency,
hardMaxConcurrency, cpuUsage, targetCpuUsage, currentMspt, targetMspt,
integrationBacklog, state: enum GREEN/YELLOW/ORANGE/RED/CRITICAL, increases/decreases/
emergencyReductions) — з'являється тільки в Phase 4 разом з adaptive controller.

## 23-24. Counters + Histograms

Cumulative counter `generated_total`, rate рахує сам Prometheus (`rate(...)[1m]`), core
не рахує throughput сам. Histogram buckets для latency (promotion/generation/
integration/region tick/pump окремо): 0.5ms..60s.

## 25-27. JVM telemetry + власний модуль

heap/nonheap/metaspace/codecache, GC counters+time, threads (+peak), classes loaded/
unloaded, CPU usage, uptime — усе через MXBeans, без дублювання окремим JMX exporter.
Модуль (`nestworld-prometheus.jar`) залежить ТІЛЬКИ від `net.nestworld.api`, ніколи від
internal-класів (`internal -> NestWorldCore -> Public API -> Prometheus module`) — так
internals можна міняти без переписування exporter'а. HTTP `/metrics`, дефолт bind
`127.0.0.1` (не `0.0.0.0`), окремо дозволяти remote bind при потребі.

## 28-30. Multi-server + Grafana dashboards

`server_id` лейбл на кожен інстанс. 5 дашбордів: Overview, Chunk Generation, Chunk
Pipeline (найцінніший — візуалізація всього ланцюжка tickets->promotion->admitted->
generation pending->active->generated->integration pending->integrated), Region,
JVM/GC. Окремий Correlation dashboard: MSPT/TPS/CPU/GC/generation active-pending/
integration backlog/promotion backlog/pump deadline hits/region p99 на одній осі часу —
щоб бачити causal ланцюжки (generation↑ -> integration backlog↑ -> MSPT↑ -> TPS↓, або
GC↑ -> MSPT↑).

## 31-32. Watchdog + event telemetry

`long_tick_total` buckets (>50ms..>50s), `last_tick_ms`, `max_tick_ms`,
`watchdog_margin_ms` — бачити наближення до watchdog ДО краху. Lightweight diagnostic
events (не логувати кожен — лише counters + останній timestamp):
CONCURRENCY_INCREASED/DECREASED, BACKPRESSURE_ENTERED/EXITED, GENERATION_REJECTED,
PUMP_DEADLINE_HIT, INTEGRATION_BACKLOG_HIGH, LONG_TICK.

## 33-38. Категоричні заборони + thread-safety + benchmark methodology

НЕ: generation worker напряму мутує world; HTTP thread читає Minecraft internals
напряму; adaptive controller обходить hard limits; predictive generation має unlimited
queue; concurrency росте без перевірки integration backlog; Prometheus scrape — важка
синхронна операція. Принцип: `Minecraft state -> main/region controlled snapshot ->
immutable snapshot -> metrics exporter`; HTTP-потік ніколи не виконує
DistanceManager/ChunkMap/WorldRegion traversal чи entity/chunk access напряму — лише
читає вже підготовлений snapshot. Snapshot update: fast (щотика: TPS/MSPT/active
generation/integration backlog), medium (250-500ms: CPU/thread counts/pool state),
slow (1-5с: JVM metadata). Ціль: snapshot update <0.1ms typical, /metrics не блокує
main thread, без помітного allocation pressure (LongAdder/AtomicLong/primitive fields,
не `new ArrayList`/`new Snapshot` щотика для великих наборів).

Benchmark methodology: Test A (256 fresh chunks, synthetic), B (те саме, прогрітий
сервер), C (real ATM9 modpack), D (predictive/швидкий рух гравця), E (кілька гравців
одночасно). Для кожного — повний набір метрик (версії, concurrency/admit/pump budget,
throughput, generation/integration p50/p95/p99/max, MSPT/TPS, CPU avg/peak, GC,
backlog max).

## 40-41. Критерії успіху + очікуваний результат

A) generation throughput росте без суттєвого погіршення TPS. B) integration backlog не
росте безконтрольно. C) при рості concurrency система знаходить стабільний максимум.
D) при CPU/MSPT pressure concurrency автоматично падає. E) без demand concurrency не
створює зайвого CPU load. F) player-critical generation не блокується predictive/bulk
backlog. Результат — таблиця concurrency->throughput->CPU->MSPT p95, з якої видно точку
плато/деградації, і adaptive-алгоритм сам сходиться туди, а не до hard-limit-максимуму.

## 42. Порядок реалізації (фази)

1. **Telemetry foundation** — GenerationSnapshot, integration counters, generation
   counters, latency histograms, throughput, pool state, JVM metrics, Prometheus
   module. **Без adaptive controller.** (Спочатку побачити систему.)
2. **Benchmark** — прогнати concurrency 1..N, знайти реальну криву concurrency->
   throughput і concurrency->MSPT.
3. **Backpressure** — integration backlog + generation admission control, concurrency
   ще static.
4. **Adaptive concurrency** — CPU/MSPT/integration/throughput feedback, hysteresis.
5. **Generation profiling** — знайти найдорожчі стадії (NOISE/SURFACE/CARVERS/
   FEATURES/STRUCTURES/...), оптимізувати саме bottleneck.
6. **Region Chunk Scheduler** — лише ПІСЛЯ вичавлювання worker-side generation
   максимально. Тут продовження вже наявної відкладеної специфікації
   (`region-chunk-scheduler-spec.md`, Phase 2): `generated chunk -> region-owned
   integration`. Окремий великий architectural проєкт, не робити завчасно.

## 43. Ключовий інваріант проєкту

Цей етап **не має перетворитися на Folia**. Зберігаємо: global tick, global tickCount,
vanilla-compatible synchronization, region threads, mailbox architecture, main-thread
world integration. Оптимізуємо: worker-side generation + admission + backpressure +
CPU utilization + observability.

> Спочатку зробити generation максимально швидкою в рамках існуючої архітектури. Потім,
> якщо integration стане домінуючим bottleneck, окремо вирішувати Region Chunk
> Scheduler.

## 44. Формула

```
Generation efficiency = generation throughput / (CPU cost + TPS cost)
```

з обмеженнями: MSPT < target, integration backlog < limit, CPU < hard limit, GC < limit,
player latency < limit. Мета — не максимум generation threads, а **максимум реально
інтегрованих чанків/сек при стабільному TPS і без неконтрольованого backlog**.

`net.nestworld.api` (2026-08-21) — правильна основа: стабільний контракт, через який
можна будувати всю цю систему без постійного залізання Prometheus-модуля у внутрішні
класи ядра.
