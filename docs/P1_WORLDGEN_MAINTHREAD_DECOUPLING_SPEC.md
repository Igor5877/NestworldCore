# P1 — Decoupling Worldgen від Main Thread

(Verbatim ТЗ from project owner, 2026-08-12, follow-up to P0.3-P0.7's chunkGenBudget/TIER2_SELECT/
WORLDGEN_SHARDS chain — see [[p0-3-autonomous-baseline-tier2-bottleneck]] and
[[p0-7-chunkgenbudget-safety-limit]] memories.)

## 1. Мета

Зробити так, щоб генерація/продовження worldgen-конвеєра виконувалися у фоновому executor'і, а
Server thread виконував лише ті операції, які справді потребують main-thread/authoritative world
state. Ключова ціль: **збільшення chunkGenBudget не повинно пропорційно збільшувати MSPT звичайного
ігрового тіку.** Не ставити за мету просто отримати більшу кількість chunks/sec.

## 2. Поточний baseline

Production: WORLDGEN_SHARDS=8, chunkGenBudget=2, chunkGenMaxConcurrent=4. budget=2→~40/s,
budget=4→~80/s, budget=8→~160/s. budget=12 → стійка деградація (P0.7). GENERIC_OTHER ~70%
pollTask(), ~89% з якого CompletableFuture$UniCompose. TIER2_SELECT вже виправлений (P0.4),
WORLDGEN_SHARDS не впливає на throughput при поточному budget (P0.6) — проблема НЕ в selection
bookkeeping, а у вартості подальшого просування worldgen pipeline.

## 3. Головна гіпотеза

`Server thread → pollTask() → CompletableFuture continuation → worldgen work` конкурує з player
tick/entity tick/block entities/scheduled ticks/network/region barrier. Мета:
`Server thread → normal tick + короткий dispatch/apply`, а worldgen work переноситься в окремий
executor з bounded completion queue назад до main thread.

## 4. Phase P1.0 — тільки аудит (нічого не переносити)

Інструментувати кожен pollTask(), розділити на WORLDGEN/CHUNK_PROMOTION/TIER2_SELECT/GENERIC_OTHER/
NON_WORLDGEN/UNKNOWN. Для GENERIC_OTHER окремо: CompletableFuture$UniCompose/UniApply/UniAccept,
ChunkTaskPriorityQueueSorter, інші. Бажано: timestamp, thread, task class, chunk position, chunk
status, ticket level, region, duration, executor per task.

## 5. P1.1 — межа безпеки

Для кожного continuation: A) можна на worker thread (не мутує authoritative ServerLevel, не
main-thread-only state, не unsafe Forge event, не unprotected thread affinity, не залежить від
іншого регіону без механізму); B) потрібен main thread (commit chunk, ChunkHolder, ticket state,
player-visible state, POI, entity insertion, block entity/world mutation, Forge callbacks, що
vanilla прямо очікує на server thread). Не переносити B.

## 6. P1.2 — Worldgen executor

`NestWorldWorldgenExecutor`, не використовує Server thread. Метрики: worldgen_active/queued/
completed/failed/queue_max/task_time/continuation_time.

## 7. P1.3 — перенаправлення CompletableFuture

Тільки підтверджені worldgen continuation-и, `thenComposeAsync(..., nestWorldWorldgenExecutor)`.
Заборонено масово замінити thenCompose→thenComposeAsync без per-site thread-safety audit.

## 8. P1.4 — Completion Queue

Worker → CompletedChunkResult → bounded queue → main thread. Upper bound (MAX_COMPLETED_WORLDGEN);
при переповненні — не unbounded backlog, не блокувати Server thread, притискати admission нових
worldgen tasks.

## 9. P1.5 — окремий Apply Budget

`worldgenApplyBudgetNanos` (1-2ms/tick) — контролює лише main-thread commit/apply, окремо від
generation budget. generation=160/s можливий разом з apply=лише стільки, скільки main thread
безпечно приймає.

## 10. Backpressure

`worldgen request → queue depth? → normal:accept / high:throttle / full:defer`. Не допускати
worker-генерує-160/s-main-приймає-30/s → необмежений backlog → RAM explosion.

## 11. Критично важлива вимога — не змінювати vanilla chunk correctness model

chunk status ordering коректний; dependencies між stages збережені; generation order без race;
chunk не visible до завершення статусу; unload не знищує генеруючийся chunk; ticket removal не
залишає orphaned future; exception у worker не залишає chunk permanently stuck.

## 12. Failure handling

success/failure/cancelled/timeout per background task. exception → future exceptionally completed
→ chunk state repaired → error telemetry. Заборонено: exception → future silently lost → chunk
forever pending.

## 13. P1.6 — тестування

Baseline (budget 2/4/8) vs New architecture (budget 2/4/8/12). budget=16 тільки після підтвердження
безпеки 12.

## 14-16. Acceptance criteria

pollTask worldgen time ↓, особливо CompletableFuture$UniCompose on Server thread ↓↓↓. MSPT normal
tick НЕ повинен масштабуватися разом із generation budget (2→4→8: MSPT ~baseline, невеликий overhead
допустимий). При budget=12: до P1 GENERIC_OTHER~70%+UniCompose+Can't-keep-up; після P1 — budget=12
не повинен створювати пропорційного росту main-thread worldgen cost. НЕ приймати як success "TPS 20,
але chunks/sec впав у 2 рази" — потрібні throughput+main-thread health+correctness одночасно.

## 17. Регресійні тести

chunk load/unload, player movement, forceload, distant tickets, chunk promotion, generation
cancellation, concurrent generation сусідніх чанків, region split/merge, server restart, world save,
dimension generation, modded worldgen, ATM9, великі backlog-и, одночасне навантаження регіонів.

## 18. Заборонено на цій фазі

Одночасно: Stage 4 global latch redesign, local clocks, region scheduler redesign, зміну
WORLDGEN_SHARDS/chunkGenMaxConcurrent/chunkGenBudget у production, unrelated vanilla optimization.
P1 змінює тільки worldgen → main-thread boundary.

## 19. Координати тестів (після 2 інцидентів)

`|X| < 30,000,000`, `|Z| < 30,000,000`, бажано вже перевірений діапазон, не максимально допустимий.
Не використовувати 34M. Координати не міняти між тестами без потреби.

## 20. Критерій завершення P1

Correctness (без race/deadlock/stuck futures/orphaned generation, ATM9 проходить тести) +
Performance (UniCompose worldgen continuation-и практично зникають з main-thread workload, pollTask()
перестає бути основним споживачем tick time через worldgen, budget=12 більше не створює той самий
degradation pattern) + Throughput (не деградує суттєво, worker pool ефективний) + Stability (TPS=20,
MSPT без стійкого росту пропорційно budget, bounded completion queue не росте безконтрольно).
