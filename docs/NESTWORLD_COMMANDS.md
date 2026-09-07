# /nestworld — повна документація команд

Це повний адміністративний командний інтерфейс ядра NestWorldCore (region-sharding + перф-діагностика), накопичений за всю історію проєкту. Джерело: `src/main/java/net/nestworld/region/NestworldCommand.java` (3008 рядків, єдиний файл реєстрації всіх під-команд).

**Права доступу:** увесь дерево команд (`.requires(src -> src.hasPermission(2))`) потребує рівня оператора 2 — жодна під-команда нижче цього не потребує.

**Канал виводу** (нова колонка «Канал» у кожній таблиці) — усе в цьому файлі йде через vanilla `CommandSourceStack.sendSuccess(Component, boolean)` / `sendFailure(Component)`, і другий (булевий) параметр `sendSuccess` реально означає:
- 💬 **лише відправнику** (`sendSuccess(..., false)`) — повідомлення бачить ТІЛЬКИ той, хто виконав команду: чат гравця, якщо викликав гравець, або консоль/RCON-сесія, якщо викликано з консолі. Ніхто інший не бачить, у консоль сервера рядок теж НЕ дублюється (якщо джерело — не сама консоль).
- 📢 **відправнику + broadcast** (`sendSuccess(..., true)`) — відправник бачить одразу; ДОДАТКОВО, якщо ігрове правило `sendCommandFeedback` увімкнено (дефолт — так), усі ІНШІ онлайн-оператори бачать сіре italic-повідомлення в своєму чаті; і якщо правило `logAdminCommands` увімкнено (дефолт — так) і виконавець — не сама консоль, рядок ТАКОЖ друкується в консоль/лог сервера. Це vanilla-механіка Brigadier, не щось особливе для NestWorldCore.
- 📁 **файл на диску** — команда пише дані у файл замість (або на додачу до) чат/консоль-відповіді; конкретний шлях вказано в описі.
- ❌ **sendFailure** — гілки помилок (невірний ID, система не ініціалізована тощо) для ВСІХ команд без винятку йдуть лише відправнику, червоним текстом, ніколи не транслюються — це не позначено в кожному рядку окремо, щоб не дублювати одне й те саме 130+ разів.

**Типи команд**, позначені після кожної назви:
- 📊 **звіт** — тільки читає й друкує накопичені лічильники, нічого не змінює
- 🎛️ **тумблер/налаштування** — живо змінює поведінку сервера (on/off або число), без рестарту
- 🧪 **тест/стрес** — одноразово запускає штучне навантаження або перевірку, для діагностики
- ♻️ **reset** — обнуляє накопичені лічильники конкретного звіту (не впливає на поведінку)

Багато команд — це діагностика з конкретних, вже закритих розслідувань (позначені датою й назвою знахідки в пам'яті проєкту); вони лишені в коді, бо кожна відповідає на конкретне "чи це знову той самий баг" питання і майже безкоштовна, коли не викликається.

---

## 1. Регіони, розбиття/злиття, статус

| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld status` 📊 | Кількість активних регіонів, server ms/tick+TPS, топ-5 регіонів за вартістю: межі чанків, cost=ms, entities=N, deferred/workDeferred (якщо >0), mailbox=N/pending=N (якщо backpressure активний), heat=N[топ-хотспоти] (якщо блок-тік heat≥1), FREE-RUNNING(auto) localTick=N (±delta) для вільнобіжних регіонів | Головний "здоровий стан сервера" екран. Компактний вигляд (топ-5) — щоб не забивати чат на 15+ регіонах | 💬 |
| `/nestworld status full` 📊 | Те саме, але БЕЗ обмеження топ-5 — усі регіони без винятку | Для скриптованого опитування/повного дампу | 💬 |
| `/nestworld lag` 📊 | Server ms/tick+TPS, найповільніший регіон (cost, entities), його найгарячіший чанк (координати, к-сть ентіті), топ-4 типи ентіті, block-tick heat (редстоун/ферми), найближчий гравець. Якщо сервер лагає, а найгірший регіон дешевий — явне попередження "лаг НЕ в регіоні, дивись main-thread" | "Хто винен" — знаходить конкретну причину лагу за один виклик замість ручного jstack | 💬 |
| `/nestworld borders` 🎛️ | Перемикає видимість меж регіонів (частинки END_ROD, радіус 96) | Візуальна діагностика меж регіону в грі | 📢 |
| `/nestworld split <id>` 🎛️ | Повідомляє новий ID двох дочірніх регіонів; закріплює їх (`pinned=true`), щоб авто-merge одразу не з'їв результат | Ручний форс-спліт регіону для тестування/тюнінгу | 📢 |
| `/nestworld merge <a> <b>` 🎛️ | Повідомляє новий ID об'єднаного регіону | Ручний форс-мердж двох регіонів-сиблінгів у BSP-дереві | 📢 |
| `/nestworld scheduler` 📊 | К-сть регіонів у станах idle/running/delayed/overloaded; для не-idle — avg=ms, deferredEntities, workDeferred | Класифікація здоров'я планувальника регіонів (Stage 4.1), спостережна, не змінює поведінку | 💬 |
| `/nestworld freerun <id>` 🎛️ | ON/OFF для вказаного регіону; попередження, якщо к-сть вільнобіжних регіонів перевищує безпечну межу для к-сті ядер боксу | Перемикає режим "вільного бігу" (незалежний від бар'єру тік) для одного регіону вручну | 📢 |
| `/nestworld regionpool` 📊 | Пофазова телеметрія `RegionThreadPool` | P0-редизайн baseline (чисто спостережна) | 💬 |
| `/nestworld regionslow` 📊 | Рейтинг "як часто ЦЕЙ регіон — найповільніший у своєму раунді" по ID | P2-аудит бар'єру | 💬 |
| `/nestworld tickphases` 📊 | Розбиття основного тіку на 6 послідовних фаз | Атрибуція "outside_pollTask" залишку по фазах | 💬 |
| `/nestworld entityfairness` 📊 | Бюджет round-robin (мс), глобальний oversubscription; на регіон: entities/ticked/deferred, p50/p95/p99/max інтервалу між послідовними тіками ОДНІЄЇ ентіті, maxCurrentAge | Реальний вимір справедливості round-robin для щільних кластерів (курник тощо), які не можна розбити спліт-менеджером | 💬 |
| `/nestworld entityfairnessreset` ♻️ | Підтвердження скидання (усі регіони) | Скидає лічильники fairness усіх регіонів | 📢 |
| `/nestworld hotspots` 📊 | Топ-10 "гарячих точок" (heat=block/fluid-тік + щільність ентіті): регіон, heat, домінуючі типи ентіті, координати, avg cost регіону | NestWorld Inspector §4 — миттєвий скан щільності, кешується для `goto hotspot` | 💬 |
| `/nestworld modload` 📊 | Топ-15 модів за СУМАРНИМ виміряним часом тіку ентіті + окремо блок-ентіті (total ms, count, avg ms) з моменту старту | "Хто винен" за точним виміром (не семплом) — реальна атрибуція навантаження по моду | 💬 |
| `/nestworld tickchunks` 📊 | Топ-15 чанків за виміряним tick-часом (ентіті+блок-ентіті), поточне decayed вікно (~останні секунди) | На відміну від hotspots — РЕАЛЬНО виміряний час з точних хуків, а не оцінка за щільністю | 💬 |
| `/nestworld goto hotspot <id>` 🎛️ | Телепортує до ранкованого хотспоту з останнього `/nestworld hotspots` | Швидка навігація до проблемного місця | 📢 |
| `/nestworld goto tickchunk <id>` 🎛️ | Телепортує до ранкованого чанку з останнього `/nestworld tickchunks` (тільки Overworld) | Швидка навігація до чанку з найбільшим виміряним tick-часом — реалізовано через ті самі координати, що й `goto chunk` | 📢 |
| `/nestworld goto region <id>` 🎛️ | Телепортує до найцікавішого місця в регіоні (найгарячіший чанк / жива ентіті / безпечний fallback) | Швидка навігація до регіону за ID без потреби вручну шукати його межі | 📢 |
| `/nestworld goto chunk <x> <z>` 🎛️ | Телепортує в центр чанку, приземлення на висоту рельєфу | Пряма навігація за координатами чанку — для діагностики конкретного відомого місця | 📢 |
| `/nestworld goto entity <uuid>` 🎛️ | Телепортує до живої ентіті за UUID | Швидка навігація до конкретної ентіті (наприклад, знайденої в іншому звіті за UUID) для огляду/адмін-втручання | 📢 |
| `/nestworld testmutate kill\|discard <uuid>` 🧪 | Повідомляє, що диспетчеровано | Тестовий виклик `EntityMutationDispatcher` для чужо-регіонної ентіті (Phase 9.2 #27) — у обох операцій ще немає реального ігрового call site | 📢 |

## 2. Чанки — генерація, промоушн, фастпас-шлях

| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld debugsync <x> <z>` 🧪 | Скільки мс зайняв один синхронний `Level.getChunk(x,z)` на головному потоці | Відтворює точну блокуючу форму виклику, яку роблять моди типу FTBChunks при телепорті на карту-клік (mineflayer не може підключитись до модованого сервера, щоб виміряти це живим ботом) | 💬 |
| `/nestworld debugsyncburst <x> <z> <count>` 🧪 | Мс на кожен виклик + сумарний час, ВСІ виклики в ОДНОМУ тіку | Те саме, але серія в одному тіку — відтворює реальний краш 2026-08-10 (EvilCraft WorldHelpers.foldArea), перевіряє cumulative-per-tick cap | 💬 |
| `/nestworld chunkstats` 📊 | Кумулятивно: calls, fast-path hits (%), blocking calls, total wait, avg blocking ms, proxy creations, region-thread timeouts; окремо — вартість самого `nestworldRefreshLoadedChunks()` (calls/avg/last/max) | Облік викликів `ServerChunkCache.getChunk()` на головному потоці — скільки з них пройшло швидким шляхом, а скільки блокувало | 💬 |
| `/nestworld chunkpromotion` 📊 | pending (жива черга), applied, avg/p95/p99/max мс промоушну; player vs bulk split; здоров'я валідатора (% прогресу, mismatches, batch_max); snapshot-рибілд (розмір, час, тіки, часткові ребілди); requested(predictive) vs admitted(tier1b+2) vs promoted, pump_deadline_hits | Телеметрія ОКРЕМОГО від getChunk() навантаження — інтеграція щойно згенерованого чанку (`DistanceManager.runAllUpdates()`), причина мас-генераційних зависань | 💬 |
| `/nestworld chunkscheduler` 📊 | (лише якщо `-Dnestworld.chunkSchedulerShadowMode=true`) observed/duplicates_skipped/misrouted/drained, розподіл по tier'ах, поточна черга по всіх регіонах, avg/max затримка дренажу | SHADOW MODE — суто спостережна перевірка нового Region-Owned Chunk Scheduler, нуль впливу на реальне завантаження чанків | 💬 |
| `/nestworld tickets` 📊 | Розподіл ticket-ів по позиціях (топ-15) | Перевірка, чи лінійний скан `hasPlayerTicket` пояснює аномалії валідатора | 💬 |
| `/nestworld ghostzones` 📊 | Статистика `BoundaryManager.syncGhostZones()` | Перевірка, чи безумовний посекундний скан по всіх FULL-чанках — джерело того самого класу "необмежений скан" багу | 💬 |
| `/nestworld polltask` 📊 | Розбивка, куди йде час головного потоку в `pollTask()`, з кореляцією по тіках | P0.1 аудит атрибуції | 💬 |
| `/nestworld worldgenboundary` 📊 | Розбивка worldgen vs "потребує головного потоку" | P1.0 аудит межі | 💬 |
| `/nestworld worldgenglue` 📊 | Атрибуція A/B/C хуків worldgen glue до головного потоку | P1.2/P1.4 | 💬 |
| `/nestworld featurefails` 📊 | Які features/структури worldgen були пропущені (замість краху) і скільки разів | Видимість `RESILIENT_FEATURE_PLACEMENT` | 💬 |
| `/nestworld stresschunks <count> <baseX> <baseZ>` 🧪 | Час виконання count викликів `getBlockState()` по спіралі навколо (baseX,baseZ) на СВІЖИХ (ніколи не чіпаних) чанках + дельта chunkstats-лічильників | Відтворює патерн "рейкаст-подібного" реактора (Draconic Evolution), а не пакетний `/forceload` | 💬 |
| `/nestworld gentest start <count> [baseX baseZ]` 🧪 | Підтвердження диспетчеризації count тікетів навколо чанку | P0.3 — автономний (без гравця) бенчмарк генерації чанків, self-expiring тікети | 📢 |
| `/nestworld gentest status` 📊 | Прогрес поточного gentest-запуску | Перевірка прогресу без зупинки/зміни запущеного бенчмарку | 💬 |
| `/nestworld setgenconcurrency <n>` 🎛️ | Підтвердження нового значення | Живий ліміт конкурентності спільного NOISE/SURFACE/CARVERS пулу генерації (без рестарту); діє лише якщо сервер піднятий з позитивним `-Dnestworld.chunkGenMaxConcurrent` | 📢 |
| `/nestworld genconcurrency` 📊 | Поточний ліміт + к-сть у черзі пулу | Перевірка поточного ліміту конкурентності генерації без зміни | 💬 |
| `/nestworld setgenbudget <n>` 🎛️ | Нове значення + effective | Живий override `chunkGenBudget` (-1 = повернути boot-default) | 📢 |
| `/nestworld genbudget` 📊 | effective/override/boot-default | Перевірка поточного chunkGenBudget без зміни | 💬 |
| `/nestworld chunkprefetch <radius>` 🧪 | Диспетчеризовано N позицій (квадрат (2r+1)²) навколо гравця + значення "before" лічильників | Ручна перевірка `NestworldApi.prefetchChunks` — реальна точка інтеграції для зовнішніх модів | 📢 |
| `/nestworld chunkprefetchstats` 📊 | prefetched/failed/misses(немає на диску), кумулятивно | Сукупна статистика відколи стартував сервер — перевірка ефективності prefetch без запуску нового тесту | 💬 |
| `/nestworld mspt` 📊 | avg/p50/p95/p99/max мс тіку за останні 100 тіків (вбудований буфер vanilla) | Чи деградує сам головний тік під навантаженням | 💬 |
| `/nestworld setpumpbudget <ms>` 🎛️ | Нове значення + effective | Живий override дедлайн-клампу промоушну чанків (-1=default, 0=вимкнути клямп — НЕ рекомендовано, саме це спричинило інцидент 2026-08-20) | 📢 |
| `/nestworld pumpbudget` 📊 | effective/override/boot-default | Перевірка поточного override дедлайн-клампу без зміни | 💬 |
| `/nestworld teststage3write <x> <y> <z>` 🧪 | Регіон-джерело/призначення, стан "до", факт постановки в поштову скриньку | Наскрізна перевірка механізму `BLOCK_WRITE` mailbox без потреби в реальному AI/мод-тригері | 💬 |

## 3. Володіння потоками, безпека, планувальник тіків

| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld leveticksownership` 📊 | violations (має бути 0), режим STRICT/DIAGNOSTIC + звіт `MailboxAudit` | Критерій прийняття фіксу володіння `LevelTicks` — 0 порушень під навантаженням = закрито | 💬 |
| `/nestworld ownershipassertions` 📊 | Звіт `NestworldOwnershipAssertions` | Перевірка assertEntityOwner/Block/BlockEntity (Phase 9) | 💬 |
| `/nestworld mailboxaudit` 📊 | (лише якщо `-Dnestworld.mailboxAudit=true`) підсумок + застряглі >5с повідомлення | Виявлення втрачених повідомлень між регіонами | 💬 |
| `/nestworld pressure` 📊 | Звіт `BatchApplyStats` + `MailboxAudit` | Batch-Apply Phase 1 — locks/message vs locks/batch, timeout/convoy rate; завжди активна | 💬 |
| `/nestworld entityguard` 📊 | (лише якщо `-Dnestworld.entityGuard=true`) 0 порушень = "NO_FOREIGN_ENTITY_MUTATION holds" | Entity Safety Layer — критерій проходження soak-тесту | 💬 |
| `/nestworld entityrecheck` 📊 | Лічильник пропусків тіку ентіті через застарілий снапшот володіння | Завжди активна, на відміну від entityguard | 💬 |
| `/nestworld scheduledtickloss` 📊 | Для кожного вільнобіжного регіону: produced/consumed/OVERWRITTEN по block/fluid/other, batches posted/overwritten, останні перезаписані записи | Перевірка гіпотези "FREE_RUNNING Scheduled Tick Loss" (компаратори/редстоун не спрацьовують з першого разу) | 💬 |
| `/nestworld scheduledtickstress <x> <y> <z> <count>` 🧪 | Регіон/interior-статус базової позиції + факт постановки count тіків уздовж +X + `hasScheduledTick` перевірка | Керований генератор запланованих тіків для мінімального регресійного тесту, обходить `/setblock`/RCON пастки | 💬 (діагностична 1-а строка) + 📢 (підсумкове підтвердження) |
| `/nestworld scheduledtickdedupcheck <x> <y> <z>` 📊 | hasScheduledTick/willTickThisTick booleans + загальний лічильник dedup-хітів | Пряма перевірка дедуплікації vanilla `LevelTicks` проти запису, взятого у власність цим кодом | 💬 |
| `/nestworld scheduledticklossreset` ♻️ | Підтвердження скидання (усі регіони) | Обнуляє метрики хендоффу тіків усіх регіонів | 📢 |
| `/nestworld blockmainprobe <ms> <region>` 🧪 | Лічильник тіків регіону до/під час/після блокування головного потоку на ms | Ізолює, чи блокування головного потоку зупиняє незалежний цикл вільнобіжного регіону | 💬 |
| `/nestworld dumpregistries [path]` 📊 | Шлях до файлу зі снапшотом усіх реєстрів id/key/value | "Ground truth" для diff до/після патчів, що чіпають реєстрацію блоків — захищено від path traversal (перевірка виходу за межі каталогу сервера, знахідка full-core-audit 2026-08-27) | 📢 + 📁 (файл `<serverDir>/<path>`, за замовч. `registry-snapshot.txt`) |

## 4. Пінінг (примусова main-thread тіковка типів)

| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld pins` 📊 | Список закріплених типів ентіті/блок-ентіті/модів | Перевірка поточного стану пінінгу без зміни | 💬 |
| `/nestworld pin <type>` 🎛️ | Підтвердження | Закріплює конкретний тип (ентіті або BE) на головний потік — для типів, чия робота region-thread-safe | 📢 |
| `/nestworld unpin <type>` 🎛️ | Підтвердження | Знімає закріплення, відновлює region-thread тіковку | 📢 |
| `/nestworld pinmod <namespace>` 🎛️ | Підтвердження | Закріплює ВСІ ентіті/BE даного модового namespace на головний потік одразу | 📢 |
| `/nestworld unpinmod <namespace>` 🎛️ | Підтвердження | Знімає закріплення для namespace | 📢 |

## 5. Виконання дій гравця поза головним потоком (Phase 11 / #31.x)

Для кожної дії (playertick, playerattack, playerinteraction/крок2, playeruseitem/крок3, playeruseitemonblock/крок4, playerentityinteract) є однаковий шаблон трьох команд + окрема timing-пара — перевірено по коду окремо для кожної з 6 дій, канали й формат ідентичні у всіх шести:

| Шаблон команди | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld player<action>experiment add <name>` 🎛️ | Додано гравця в опт-ін набір (+ попередження, якщо відповідний master-флаг вимкнено) | Поетапне вмикання виконання дії на регіон-потоці для КОНКРЕТНОГО гравця, без ефекту без відповідного `-Dnestworld.player<Action>RegionExecution=true` | 📢 |
| `/nestworld player<action>experiment remove <name>` 🎛️ | Видалено з набору (або "не було в наборі", якщо гравця там не було) | Симетричний опт-аут — прибирає гравця з опт-ін набору; без ефекту, якщо гравця туди не додавали | 📢 |
| `/nestworld player<action>experiment list` 📊 | masterFlag ON/OFF, розмір набору, імена гравців у ньому | Перевірка стану опт-ін набору й master-флага без зміни | 💬 |
| `/nestworld player<action>timing` 📊 | Зведення timing-класу цієї дії (латентність same/cross-region тощо) | Вимірювальна діагностика, не змінює поведінку | 💬 |
| `/nestworld player<action>timingreset` ♻️ | Підтвердження скидання timing-класу цієї дії | Обнулити timing — новий вимірювальний період | 📢 |

Конкретні реалізовані дії: `playertick` (#31.3, найризикованіша — сам тік гравця), `playerattack` (#31.4), `playerinteraction` (крок2, взаємодія з блоком), `playeruseitem` (крок3), `playeruseitemonblock` (крок4), `playerentityinteract`/`entityinteract` (item-on-entity, іменується `entityinteracttiming`/`reset`, не `playerentityinteracttiming`).

Окремо (без experiment-трійки):
| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld playerinputstats` 📊 | Режим SHADOW-ON/OFF + звіт `PlayerInputDiagnostics` | #31.2 shadow-mode — вхідна поштова скринька команд гравця | 💬 |
| `/nestworld playerusecontroltiming` 📊 | Зведення `PlayerUseControlTiming` | #31.3/#31.4 hardening — їжа/зілля/лук | 💬 |
| `/nestworld playerusecontroltimingreset` ♻️ | Підтвердження скидання | Обнулення timing-класу "use control" (їжа/зілля/лук) — той самий reset-паттерн, що й для решти дій гравця | 📢 |
| `/nestworld interactionwaitbudget` 📊 | Зведення бюджету очікування + максимальна глибина поштової скриньки серед регіонів | Спільний per-tick бюджет очікування диспетчеризації взаємодії (після реального Watchdog-краху при 60-80 гравцях) | 💬 |
| `/nestworld interactionwaitbudgetreset` ♻️ | Підтвердження скидання | Обнулює накопичений бюджет очікування диспетчеризації взаємодії — почати новий вимірювальний період | 📢 |
| `/nestworld interactionlatencydist` 📊 | p50/p90/p95/p99/max round-trip латентності apply (same/cross-region) + calls-per-tick розподіл | Дані для вибору K/maxPerCall/порогів здоров'я регіону перед редизайном | 💬 |
| `/nestworld interactionwaitspikes` 📊 | Атрибуція сплесків: реальний wall-clock час перевищив очікуваний — ознака CPU oversubscription, не бага в таймаут-логіці | Відрізняє реальне перевантаження CPU боксу від помилки в логіці очікування диспетчеризації | 💬 |
| `/nestworld interactionslots <k>` 🎛️ | Нове значення K | Живий K (max wait slots/tick) для K-first-slots стратегії — без рестарту для swept-тестування | 📢 |
| `/nestworld interactionmaxwaitms <x>` 🎛️ | Нове значення X мс | Живий максимум очікування на один виклик | 📢 |

## 6. Спліт/мердж регіонів — журнал подій

| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld splitmergesummary` 📊 | Зведення `SplitMergeEventLog` + к-сть регіонів | Дослідження трешингу split/merge (2026-08-28) | 💬 |
| `/nestworld splitmergedump [lines]` 📊 | Останні N рядків журналу (за замовч. 60) | Ручний перегляд конкретних недавніх подій (не лише зведення) — для точного відтворення послідовності трешингу | 💬 |
| `/nestworld splitmergereset` ♻️ | Підтвердження скидання | Очищає журнал подій спліт/мердж — почати новий вимірювальний період | 📢 |

## 7. Мережа, з'єднання, пакети, admission control

| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld connectiondiag` 📊 | Зведення + in-flight + по-джерелах + композиція broadcast-пакетів | Connection/Netty fan-in діагностика | 💬 |
| `/nestworld connectiondiagreset` ♻️ | Підтвердження скидання | Обнулює лічильники Connection/Netty fan-in діагностики | 📢 |
| `/nestworld connectiondiagslow [lines]` 📊 | Найповільніші записи (за замовч. 100) | Пошук конкретних найповільніших вхідних з'єднань/пакетів для точкового аналізу (на відміну від сукупного connectiondiag) | 💬 |
| `/nestworld trackingreport` 📊 | Багаторядковий звіт `TrackingMetrics` (гравці, TPS, MSPT у контексті) | Entity Tracking Fan-out Attribution — хто кому й скільки пакетів шле | 💬 |
| `/nestworld trackingreset` ♻️ | Підтвердження скидання | Обнулює лічильники Entity Tracking Fan-out атрибуції — новий вимірювальний період | 📢 |
| `/nestworld recipientfanout <ticks>` 📊 | Звіт розміру виграшу від `ClientboundBundlePacket` | Наскільки батчинг пакетів по одержувачу вигідний | 💬 |
| `/nestworld outboundbatch on\|off` 🎛️ | ENABLED/disabled | Тумблер `OutboundBatchQueue` (за замовч. вимкнено як фіча-флаг тесту, у проді enabled=true) | 📢 |
| `/nestworld outboundbatchreport` 📊 | Звіт батчингу | Детальний звіт по роботі OutboundBatchQueue — скільки пакетів реально об'єднано в ClientboundBundlePacket | 💬 |
| `/nestworld outboundbatchreset` ♻️ | Підтвердження скидання | Обнулює лічильники батчингу вихідних пакетів | 📢 |
| `/nestworld outboundbatchmaxsize <n>` 🎛️ | Нове maxBundleSize | Живе налаштування максимального розміру одного ClientboundBundlePacket — тюнінг компромісу затримка/накладні витрати без рестарту | 📢 |
| `/nestworld joinburstreport` 📊 | Звіт `JoinBurstMetrics` | Setup-Burst / Player Join Pipeline — вартість масового заходу гравців | 💬 |
| `/nestworld joinburstreset` ♻️ | Підтвердження скидання | Обнулює метрики Setup-Burst/Player Join Pipeline | 📢 |
| `/nestworld chunkoutbound on\|off` 🎛️ | ENABLED/disabled | Тумблер `ChunkOutboundQueue` (бюджетована черга чанк-пакетів на приєднання) | 📢 |
| `/nestworld chunkoutboundreport` 📊 | Звіт | Детальний звіт по роботі ChunkOutboundQueue — бюджетованої черги чанк-пакетів на приєднання гравця | 💬 |
| `/nestworld chunkoutboundreset` ♻️ | Підтвердження скидання | Обнулює лічильники ChunkOutboundQueue | 📢 |
| `/nestworld chunkoutboundbudget <n>` 🎛️ | budgetPerTick | Живий ліміт кількості чанк-пакетів на тік для ChunkOutboundQueue — тюнінг без рестарту | 📢 |
| `/nestworld pairingbatch on\|off` 🎛️ | ENABLED/disabled | Тумблер `PairingOutboundQueue` (§21 addPairing-батчинг) | 📢 |
| `/nestworld pairingbatch status` 📊 | Статус | Поточний стан PairingOutboundQueue без скидання лічильників | 💬 |
| `/nestworld pairingbatchreset` ♻️ | Підтвердження скидання | Обнулює лічильники пейринг-батчингу | 📢 |
| `/nestworld pairingbatchbudget <n>` 🎛️ | budgetPerTick | Живий ліміт addPairing-операцій на тік — тюнінг без рестарту | 📢 |
| `/nestworld movedecisionreport` 📊 | Звіт `MoveDecisionMetrics` | Player Tick / Decision Fan-out атрибуція | 💬 |
| `/nestworld movedecisionreset` ♻️ | Підтвердження скидання | Обнулює метрики Player Tick/Decision Fan-out атрибуції | 📢 |
| `/nestworld naturalspawnerreport` 📊 | Звіт `NaturalSpawnerMetrics` | NaturalSpawner-атрибуція/бюджет | 💬 |
| `/nestworld naturalspawnerreset` ♻️ | Підтвердження скидання | Обнулює метрики NaturalSpawner-атрибуції | 📢 |
| `/nestworld burstreport` 📊 | Звіт `BurstAdmissionMetrics` | Burst Admission ТЗ — навантаження при масовому підключенні | 💬 |
| `/nestworld burstreset` ♻️ | Підтвердження скидання | Обнулює метрики Burst Admission ТЗ | 📢 |
| `/nestworld burstthreads` 📊 | Перепис потоків (`threadCensus`), включно з oversubscriptionRatio | Перевірка перенасичення потоків під час масового заходу гравців — джерело Netty worker A/B дослідження | 💬 |
| `/nestworld modcallbackreport` 📊 | Звіт `ModCallbackAttribution` | Атрибуція callback-ів сторонніх модів (FTB Quests тощо) | 💬 |
| `/nestworld modcallbackreset` ♻️ | Підтвердження скидання | Обнулює атрибуцію callback-ів сторонніх модів | 📢 |
| `/nestworld modcallbackrouterreport` 📊 | Звіт `ModCallbackRouter` | Ізоляція виконання мод-колбеків (ftbchunks-repro) | 💬 |
| `/nestworld modcallbackrouterreset` ♻️ | Підтвердження скидання | Обнулює метрики ModCallbackRouter (ізоляція виконання мод-колбеків) | 📢 |
| `/nestworld admission on\|off` 🎛️ | ENABLED/disabled | Тумблер загального `AdmissionController` | 📢 |
| `/nestworld admissionreport` 📊 | Звіт | Детальний звіт загального AdmissionController | 💬 |
| `/nestworld admissionreset` ♻️ | Підтвердження скидання | Обнулює лічильники загального AdmissionController | 📢 |
| `/nestworld admissionbudget <n>` 🎛️ | budgetPerTick | Живий бюджет допуску на тік для загального AdmissionController | 📢 |
| `/nestworld pairingadmission on\|off` 🎛️ | ENABLED/disabled | Тумблер `PairingAdmissionGate` (Step 3 — виявлений реальний ключ до n=200+ виживання) | 📢 |
| `/nestworld pairingadmissionreport` 📊 | Звіт | Детальний звіт PairingAdmissionGate — Step 3, справжній ключ до виживання n=200+ | 💬 |
| `/nestworld pairingadmissionreset` ♻️ | Підтвердження скидання | Обнулює лічильники PairingAdmissionGate | 📢 |
| `/nestworld pairingadmissionbudget <n>` 🎛️ | globalBudgetPerTick | Живий ГЛОБАЛЬНИЙ бюджет пейринг-допуску на тік | 📢 |
| `/nestworld pairingadmissionperplayer <n>` 🎛️ | perPlayerBudgetPerTick | Живий бюджет пейринг-допуску НА ОДНОГО гравця за тік | 📢 |
| `/nestworld connectionadmission on\|off` 🎛️ | ENABLED/disabled | Тумблер `ConnectionAdmissionGate` (виявлено — "неправильний важіль", 0/7 не вирішив проблему; справжній важіль — Netty worker threads) | 📢 |
| `/nestworld connectionadmissionreport` 📊 | Звіт | Детальний звіт ConnectionAdmissionGate | 💬 |
| `/nestworld connectionadmissionreset` ♻️ | Підтвердження скидання | Обнулює лічильники ConnectionAdmissionGate | 📢 |
| `/nestworld connectionadmissionbudget <n>` 🎛️ | maxNewConnectionsPerWindow | Живий ліміт нових з'єднань за вікно часу | 📢 |
| `/nestworld cpuplanner` 📊 | Повний звіт `CpuCapacityPlanner` | Container-aware CPU-кепасіті планувальник (nettyWorkerThreads=auto тощо) | 💬 |
| `/nestworld nearestplayerverify` 📊 | Звіт верифікації `NearestPlayerIndex` | Перевірка коректності просторового індексу найближчого гравця (заміна O(n) скану) | 💬 |
| `/nestworld nearestplayerverifyreset` ♻️ | Підтвердження скидання | Обнулює лічильники верифікації NearestPlayerIndex — новий вимірювальний період | 📢 |
| `/nestworld nearestplayermismatches` 📊 | Журнал розбіжностей | Перегляд конкретних розбіжностей (не лише лічильник) — виводить у чат, на відміну від dump у файл | 💬 |
| `/nestworld nearestplayermismatchesdump` 📊 | Шлях до файлу `nearestplayer_mismatches.txt` | Дамп журналу розбіжностей у файл (у корені каталогу сервера) | 💬 + 📁 (файл `nearestplayer_mismatches.txt`) |
| `/nestworld setmovethreshold <multiplier>` 🎛️ | Нове значення + effective | Живий override множника анти-спідхак перевірки "moved too quickly" (-1=default із server.properties) | 📢 |
| `/nestworld movethreshold` 📊 | effective/override/boot-default | Перевірка поточного живого override множника анти-спідхак перевірки без зміни значення | 💬 |
| `/nestworld setmovepacketcap <n>` 🎛️ | Нове значення + effective | Живий cap на кількість пакетів руху за тік (-1=default) | 📢 |
| `/nestworld movepacketcap` 📊 | effective/override/boot-default | Перевірка поточного живого override cap-у пакетів руху без зміни значення | 💬 |
| `/nestworld setfloatingkick <true\|false>` 🎛️ | ENABLED/DISABLED, діє одразу | Живий тумблер vanilla анти-флайхак кіку "floating too long" | 📢 |
| `/nestworld floatingkick` 📊 | effective/override/boot-default | Перевірка поточного стану анти-флайхак кіку без зміни значення | 💬 |

## 8. Крафт/рецепти

| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld recipecensus reset` ♻️ | Підтвердження старту нового вікна перепису | Перепис викликів `RecipeManager.byType()` (Recipe Lookup Lag ТЗ Phase 1) — старт вікна | 📢 |
| `/nestworld recipecensus stop` 🎛️ | Підтвердження зупинки перепису | Зупинка перепису | 📢 |
| `/nestworld recipecensus report` 📊 | Багаторядковий звіт | Детальний звіт по накопиченому вікну — скільки викликів RecipeManager.byType(), звідки | 💬 |
| `/nestworld recipecensus reportbythread` 📊 | Те саме, розбито по класу потоку | Атрибуція, чи виклики йдуть з очікуваних потоків (main/region/pool) | 💬 |
| `/nestworld recipetiming reset\|stop` ♻️ | Таймінг патчу BCLib bcl_sort single-pass | Валідація однопрохідного патчу через хуки `CraftingMenu`/`ResultSlot` | 📢 |
| `/nestworld recipetiming report` 📊 | Таймінг патчу BCLib bcl_sort single-pass | Валідація однопрохідного патчу через хуки `CraftingMenu`/`ResultSlot` | 💬 |
| `/nestworld craftingbatch reset` ♻️ | Звіт `CraftingBatchMetrics` | Transaction-Scoped Batching ТЗ | 📢 |
| `/nestworld craftingbatch report` 📊 | Звіт `CraftingBatchMetrics` | Transaction-Scoped Batching ТЗ | 💬 |
| `/nestworld recipematchprofiler reset\|stop` ♻️ | Per-candidate таймінг (працює лише з v2-profiling патчем BCLib) | Candidate Recipe Index ТЗ Phase 0 — додає реальний overhead, тільки для обмеженого вікна | 📢 |
| `/nestworld recipematchprofiler report` 📊 | Per-candidate таймінг (працює лише з v2-profiling патчем BCLib) | Candidate Recipe Index ТЗ Phase 0 — додає реальний overhead, тільки для обмеженого вікна | 💬 |
| `/nestworld candidateindex reset\|stop` ♻️ | Shadow-mode diff-статистика (лише з v3-shadow-index патчем BCLib) | Candidate Recipe Index — паралельна перевірка нового індексу проти старого | 📢 |
| `/nestworld candidateindex report` 📊 | Shadow-mode diff-статистика (лише з v3-shadow-index патчем BCLib) | Candidate Recipe Index — паралельна перевірка нового індексу проти старого | 💬 |
| `/nestworld candidateindex production on\|off` 🎛️ | ON/OFF | Вмикає РЕАЛЬНЕ використання кандидат-індексу в проді (не тільки shadow) | 📢 |
| `/nestworld lastrecipeaudit reset\|stop` ♻️ | Звіт коректності Last-Recipe кешу | Дослідницька перевірка, ніколи не змінює поведінку проду | 📢 |
| `/nestworld lastrecipeaudit report` 📊 | Звіт коректності Last-Recipe кешу | Дослідницька перевірка, ніколи не змінює поведінку проду | 💬 |

## 9. Redstone / позиційний спостерігач блоків

| Команда | Виводить | Призначення | Канал |
|---|---|---|---|
| `/nestworld blockwatch start <path>` 🎛️ | Підтвердження старту + к-сть відстежуваних позицій | Пише ОДИН рядок ЗА ТІК (не RCON, звичайний файл) з тіком/часом/main-ms/region-ms + повний стан кожної відстежуваної позиції — інструмент розслідування редстоун-десинку 2026-09-03 | 📢 (лише підтвердження старту) + 📁 (сам потік даних, окремий файл за шляхом `<path>`, по рядку за тік) |
| `/nestworld blockwatch stop` 🎛️ | Підтвердження | Зупиняє запис у blockwatch-файл | 📢 |
| `/nestworld blockwatch status` 📊 | Поточний статус | Перевірка, чи blockwatch активний і скільки позицій відстежується, без зупинки | 💬 |
| `/nestworld blockwatch clear` 🎛️ | Підтвердження | Очищає список позицій | 📢 |
| `/nestworld blockwatch addpos <x> <y> <z>` 🎛️ | Підтвердження + загальна к-сть | Додає позицію до списку спостереження | 📢 |

---

## Живі приклади виводу

Захоплено 2026-09-04 з живого сервера (версія 220, redstone-fix-only, 12+ год аптайм, 4 регіони, ~3.7-4.0 мс/тік, 20 TPS) через RCON (`python3 rcon.py`, порт 25579) — той самий канал, що й консоль/RCON-сесія. Онлайн гравців не було (`nearest player: none nearby`), тож 📢-команди в цьому конкретному прогоні не мали кому додатково транслюватись — сам механізм broadcast (див. легенду каналів на початку файлу) від цього не змінюється.

**Що виконано наживо (🟢), а що показано лише як формат виводу без запуску (🔒):** аргумент-вільні 📊/♻️ команди — виконано наживо, це реальний захоплений вивід. `/nestworld borders` (косметичний тумблер часток) — виконано двічі (увімкнути/вимкнути), стан повернуто як було. Усе, що змінює живий тюнінг (усі 🎛️ з обов'язковим аргументом: split/merge/pin/freerun/setX/toggle-параметри), генерує штучне навантаження (🧪: debugsync/stresschunks/gentest start/scheduledtickstress/blockmainprobe тощо) або потребує реальної цілі, якої зараз немає (`<uuid>`, `<name>` гравця) — НЕ виконувалось; замість цього нижче показано реальний рядок формату з коду (`String.format`/конкатенація), з `<...>` замість підставлених значень, позначено 🔒 і підписом "формат виводу (приклад, НЕ виконано на живому сервері)". Жодне поле тут не вигадане — усі формати взяті з реальних `sendSuccess`/`Component.literal` викликів у `NestworldCommand.java`, той самий стандарт, що й в основних таблицях вище.

Причина обережності: цей клас команд (жива зміна `chunkGenBudget`, pump-budget-клямпу, admission/pairing-бюджетів тощо) прямо пов'язаний із задокументованою історією крашів проєкту (інцидент 2026-08-20 — підняття `chunkGenBudget` без клямпу довело до watchdog-краху; K-first-slots/burst-admission race'и) — немає причини ризикувати стабільним білдом 220 заради прикладу в документації, коли реальний формат виводу й так видно з коду.

---
## 1. Регіони, розбиття/злиття, статус

### 🟢 `/nestworld status`
```
NestWorld: 4 active region(s), server 3.7 ms/tick (20.0 TPS)
  #5 chunks(-1875000,-1875000)-(1,6) cost=0.0ms entities=0
  #6 chunks(-1875000,7)-(1,7) cost=0.6ms entities=9 FREE-RUNNING(auto) localTick=908818 (-311253)
  #4 chunks(-1875000,8)-(1,1875000) cost=0.0ms entities=0
  #2 chunks(2,-1875000)-(1875000,1875000) cost=0.0ms entities=0

```

### 🟢 `/nestworld status full`
```
NestWorld: 4 active region(s), server 3.7 ms/tick (20.0 TPS)
  #5 chunks(-1875000,-1875000)-(1,6) cost=0.0ms entities=0
  #6 chunks(-1875000,7)-(1,7) cost=0.6ms entities=9 FREE-RUNNING(auto) localTick=908818 (-311253)
  #4 chunks(-1875000,8)-(1,1875000) cost=0.0ms entities=0
  #2 chunks(2,-1875000)-(1875000,1875000) cost=0.0ms entities=0

```

### 🟢 `/nestworld lag`
```
NestWorld lag report — server 3.7 ms/tick (20.0 TPS)
  slowest: region #6  cost=0.6ms  entities=9
  hottest chunk: (1,7) — 9 entities (block 16,112)
    minecraft:chicken ×5
    minecraft:pig ×2
    minecraft:cow ×1
    minecraft:item ×1
  nearest player: none nearby (chunks kept loaded by force-load/spawn?)

```

### 🟢 `/nestworld scheduler`
```
NestWorld scheduler: 4 region(s) — idle=3 running=1 delayed=0 overloaded=0
  #6 [RUNNING] avg=0.6ms deferredEntities=0 workDeferred=0

```

### 🟢 `/nestworld regionpool`
```
NW RegionThreadPool per-phase telemetry:
  ENTITY: rounds=202,342 avgWorkers=1.0 dispatch_avg=0.010ms wait_avg=0.640ms poll_avg=0.008ms poll_p95=0.010ms region_tick_avg=0.565ms region_tick_p95=0.723ms region_tick_max=58.974ms timeouts=0 avg_pending_at_timeout=0.0
  SCHEDULED_TICK: no rounds yet
  RANDOM_TICK: rounds=51,477 avgWorkers=1.0 dispatch_avg=0.012ms wait_avg=0.115ms poll_avg=0.002ms poll_p95=0.002ms region_tick_avg=0.015ms region_tick_p95=0.022ms region_tick_max=5.189ms timeouts=0 avg_pending_at_timeout=0.0
  BLOCK_ENTITY: no rounds yet
  BLOCK_EVENT: no rounds yet
  PLAYER_TICK_EXPERIMENT: no rounds yet
  PLAYER_ATTACK_EXPERIMENT: no rounds yet


```

### 🟢 `/nestworld regionslow`
```
NW per-region slowest-in-round ranking:
  region #6: slowest_in_round=150,434 ticks=150,434 avg=0.560ms max=48.368ms
  region #0: slowest_in_round=69,026 ticks=69,026 avg=0.299ms max=58.974ms
  region #1: slowest_in_round=33,928 ticks=33,928 avg=0.295ms max=12.851ms
  region #3: slowest_in_round=431 ticks=431 avg=0.573ms max=2.415ms


```

### 🟢 `/nestworld tickphases`
```
NW tick phases [minecraft:overworld] (cumulative since boot, 1,220,072 ticks, avg tick_wall=2.890ms):
  vanilla(+3-pool-phases)  avg=1.1616ms (40.2%)
  signals                  avg=0.0004ms (0.0%)
  entityXfer               avg=0.0045ms (0.2%)
  regionPool(entity)       avg=0.1106ms (3.8%)
  ghostZones+tracker       avg=1.6062ms (55.6%)
  splitMerge               avg=0.0070ms (0.2%)


```

### 🟢 `/nestworld entityfairness`
```
NW entity-fairness: budget=40ms globalOversub=1.30x (1 region(s) with owned entities)
  Region 6: entities=9 ticked=9 deferred=0 localTickDelta=-311253 | tickInterval p50=50ms p95=50ms p99=53ms max=97ms | maxCurrentAge=7ms

```

### 🟢 `/nestworld entityfairnessreset`
```
NW entity-fairness reset (all regions)

```

### 🟢 `/nestworld hotspots`
```
TOP HOTSPOTS
#1 region=#6 heat=9.0 (block/fluid=0.0, entities=9.0)
   dominant: minecraft:chicken x5, minecraft:pig x2, minecraft:cow x1
   at (27, 104, 122) — chunk (1, 7)
   region avg cost: 0.6ms


```

### 🟢 `/nestworld modload`
```
MOD LOAD (cumulative measured tick time since boot)
-- entities --
  minecraft                total=619289.65ms count=13,776,171 avg=0.0450ms
-- block entities --


```

### 🟢 `/nestworld tickchunks`
```
TOP TICK-COST CHUNKS (measured, current window)
#1 [minecraft:overworld] chunk (1, 7) — 11.68ms
Use /nestworld goto tickchunk <id> (overworld only) to teleport.


```

### 🟢 `/nestworld borders (увімкнути)`
```
Region borders: VISIBLE (END_ROD particles, range 96)
```

### 🟢 `/nestworld borders (вимкнути — повторний виклик)`
```
Region borders: hidden
```

### 🔒 `/nestworld split <id>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Split #<id> -> #<childId1> + #<childId2> (pinned — won't auto-merge; use /nestworld merge)
```

### 🔒 `/nestworld merge <a> <b>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Merged #<a> + #<b> -> #<mergedId>
```

### 🔒 `/nestworld freerun <id>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Region #<id> free-running: ON
(або: ... free-running: OFF)
(якщо перевищено безпечний ліміт: додається " -- WARNING: <N> free-running region(s) now active,
above the advisory safe cap of <cap> for this box's <cores> visible core(s) (2026-08-13 scaling
test: free-running oversubscribes and LOSES to the barrier model on constrained CPU -- fine on
8+ cores, not recommended here)")
```

### 🔒 `/nestworld goto hotspot <id>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Teleported to hotspot #<id> at <x, y, z>
(помилка, якщо RCON без гравця-джерела: teleportTo() викликає src.getPlayerOrException() ->
CommandSyntaxException "Command was not run by a player entity" — ця команда фізично не може
відпрацювати з консолі/RCON без реального гравця-виконавця)
```

### 🔒 `/nestworld goto tickchunk <id>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Teleported to chunk (<x>, <z>) at <x, y, z>
(та сама вимога реального гравця-джерела, що й goto hotspot)
```

### 🔒 `/nestworld goto region <id>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Teleported to region #<id> at <x, y, z>
(та сама вимога реального гравця-джерела)
```

### 🔒 `/nestworld goto chunk <x> <z>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Teleported to chunk (<x>, <z>) at <x, y, z>
(та сама вимога реального гравця-джерела)
```

### 🔒 `/nestworld goto entity <uuid>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Teleported to entity <uuid> at <x, y, z>
(та сама вимога реального гравця-джерела; ще одна гілка помилки: "No live entity with UUID <uuid>")
```

### 🔒 `/nestworld testmutate kill|discard <uuid>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Dispatched Kill for <uuid>
(або: Dispatched Discard for <uuid>; помилка, якщо ентіті офлайн: "No live entity with UUID <uuid>")
```


## 2. Чанки — генерація, промоушн, фастпас-шлях

### 🟢 `/nestworld chunkstats`
```
NW Chunk FastPath (main-thread getChunk, cumulative)
  calls: 44,647,421   fast-path hits: 44,190,050 (99.0%)   blocking: 455,621
  total wait: 364 ms   avg blocking: 0.001 ms   proxy creations: 0
  region-thread getChunk timeouts: 0
NW FastPath refresh (nestworldRefreshLoadedChunks, cumulative)
  calls: 1,220,073   avg: 0.472 ms   last: 0.985 ms (scanned=2,209 loaded=529)
  max: 45.236 ms (scanned=2,209)

```

### 🟢 `/nestworld chunkpromotion`
```
NW Chunk Promotion (main-thread updateFutures(), cumulative)
  pending: 0   applied: 2,209   avg: 0.228 ms
  p95: 0.061 ms   p99: 0.343 ms   max: 7.249 ms
  player: 0   bulk: 0   validator: 100.0% mismatches=0 batch_max=1.037ms
  snapshot: size=0 build=0.797ms build_ticks=1 partial_rebuilds=0
  requested(predictive): 0   admitted(tier1b+2): 0   pump_deadline_hits: 0

```

### 🟢 `/nestworld chunkscheduler`
```
NW Chunk Scheduler: shadow mode is OFF (-Dnestworld.chunkSchedulerShadowMode=true to enable)

```

### 🟢 `/nestworld tickets`
```
NW Ticket Distribution
  positions=6 avg_tickets=2.00 max_tickets=4 over5=0 over20=0 over100=0
  #1 chunk(-1,7) tickets=4
  #2 chunk(0,7) tickets=2
  #3 chunk(-1,6) tickets=2
  #4 chunk(0,6) tickets=2
  #5 chunk(-2,7) tickets=1
  #6 chunk(-2,6) tickets=1

```

### 🟢 `/nestworld ghostzones`
```
NW Ghost Zone Sync: calls=1,220,073 avg=1.587ms max=1761.120ms nbt_avg=1.464ms last_scanned=529 last_serialized_chunks=8 last_serialized_bes=984

```

### 🟢 `/nestworld polltask`
```
NW pollTask() attribution:
[minecraft:overworld] poll_total: calls=6,905,185,640 total=969503.811ms
  ADMISSION_DRAIN    calls=2,758,165,332 total=133818.385ms ( 13.8%) avg=0.0000ms max=12.100ms
  DISTANCE_MANAGER   calls=1,383,963,770 total=648843.282ms ( 66.9%) avg=0.0005ms max=542.484ms
  CHUNK_MAP_PROMOTE  calls=1,383,963,770 total=57822.760ms (  6.0%) avg=0.0000ms max=7.154ms
  LIGHTING           calls=1,379,082,665 total=127359.124ms ( 13.1%) avg=0.0001ms max=13.252ms
  GENERIC_CHUNK_MAP  calls=529      total=    9.576ms (  0.0%) avg=0.0181ms max=0.782ms
  GENERIC_OTHER      calls=9,574    total= 1650.684ms (  0.2%) avg=0.1724ms max=186.093ms
  GENERIC_OTHER breakdown by class (top by total time, cap=60 distinct names seen):
    java.util.concurrent.CompletableFuture$UniApply                        calls=2,827    total= 1346.312ms avg=0.4762ms max=186.093ms
    net.minecraft.server.level.ChunkTaskPriorityQueueSorter$$Lambda/0x0000782d7f41fae8 calls=1,940    total=  250.810ms avg=0.1293ms max=82.131ms
    java.util.concurrent.CompletableFuture$UniRun                          calls=529      total=   38.591ms avg=0.0730ms max=7.748ms
    java.util.concurrent.CompletableFuture$UniExceptionally                calls=2,025    total=   13.020ms avg=0.0064ms max=1.906ms
    net.minecraft.server.level.ServerChunkCache$$Lambda/0x0000782d7f434000 calls=2,253    total=    1.951ms avg=0.0009ms max=0.094ms

tick correlation over 1,220,073 ticks (avg ms, tick_wall max=1762.158ms):
  tick_wall=2.891
   +-- pollTask_total     =0.001
   +-- region_tick_total  =0.094 (max single region, summed across this tick's rounds)
   +-- actual_latch_wait  =0.110 (idle park, NOT doing pollTask work)
... [обрізано в цьому документі — повний вивід довший]
```

### 🟢 `/nestworld worldgenboundary`
```
NW P1.0 worldgen/main-thread boundary (GENERIC_OTHER only, class-name heuristic): calls=10,103 total=1660.260ms
  WORLDGEN         calls=7,321    total= 1648.733ms ( 99.3%) avg=0.2252ms max=186.093ms
  CHUNK_PROMOTION  calls=529      total=    9.576ms (  0.6%) avg=0.0181ms max=0.782ms
  NON_WORLDGEN     calls=2,253    total=    1.951ms (  0.1%) avg=0.0009ms max=0.094ms
  WORLDGEN percentiles (n=4,096 samples): p50=0.0092ms p95=0.0946ms p99=0.2476ms


```

### 🟢 `/nestworld worldgenglue`
```
NW P1.2/P1.4 worldgen main-thread glue (bucket A + C, exact hook points, not heuristic):
  A main_glue        calls=529      total=   34.133ms avg=0.0645ms max=7.709ms p50=0.0340ms p95=0.0939ms p99=0.3584ms
  C chunk_finalize    calls=529      total=  201.018ms avg=0.3800ms max=81.002ms p50=0.0256ms p95=0.1251ms p99=3.5596ms
  commonpool_calls    0 (confirmed: no ForkJoinPool.commonPool() usage found in the audited chain -- see memory p1-1-protochunktofullchunk-commonpool-finding)
  B tier_update (from DistanceManagerAttribution TIER1*/TIER2*/VALIDATOR_BATCH -- see /nestworld polltask)
  B promotion (updateFutures) applied=2,209 avg=0.2277ms p50=0.0011ms p95=0.0609ms p99=0.3429ms max=7.2486ms


```

### 🟢 `/nestworld featurefails`
```
NW Feature-placement resilience: 0 skipped features/structures since boot.

```

### 🟢 `/nestworld gentest status`
```
NW no benchmark dispatched yet -- use /nestworld gentest start <count> [baseX] [baseZ]

```

### 🟢 `/nestworld genconcurrency`
```
NW gen-pool concurrency: 0   queued-in-pool: 0

```

### 🟢 `/nestworld genbudget`
```
NW chunkGenBudget effective=0 (override=-1, boot-default=0)

```

### 🟢 `/nestworld chunkprefetchstats`
```
NW ChunkPrefetch (cumulative since boot): prefetched=0 failed=0 misses(no chunk on disk)=0

```

### 🟢 `/nestworld mspt`
```
NW MSPT (last 100 ticks): avg=3.76 p50=3.52 p95=5.00 p99=7.35 max=7.35

```

### 🟢 `/nestworld pumpbudget`
```
NW chunkPromotionPumpBudgetMs effective=250ms (override=-1, boot-default=250ms)

```

### 🔒 `/nestworld debugsync <x> <z>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
debugsync: Level.getChunk(<chunkX>, <chunkZ>) [block <x>,<z>] took <N> ms
```

### 🔒 `/nestworld debugsyncburst <x> <z> <count>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
debugsyncburst: <count> calls in ONE tick, per-call ms=[<n1>, <n2>, ...], TOTAL=<N> ms
```

### 🔒 `/nestworld stresschunks <count> <baseX> <baseZ>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
stresschunks: <count> getBlockState() calls around (<baseX>,<baseZ>) took <N> ms wall-clock
(chunkstats delta: lookups=<N> fast=<N> blocking=<N> wait=<N>ms)
```

### 🔒 `/nestworld gentest start <count> [baseX baseZ]`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW gentest: dispatched <count> chunk tickets around chunk (<chunkX>,<chunkZ>) -- poll with
/nestworld gentest status
```

### 🔒 `/nestworld setgenconcurrency <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW gen-pool concurrency set to <n>
```

### 🔒 `/nestworld setgenbudget <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW chunkGenBudget set to <n> (effective=<n>)
```

### 🔒 `/nestworld chunkprefetch <radius>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW chunkprefetch: dispatched <N> positions (radius <radius>). Before: prefetched=<N> failed=<N>
misses=<N> — run /nestworld chunkprefetchstats shortly to see the delta.
```

### 🔒 `/nestworld setpumpbudget <ms>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW chunkPromotionPumpBudgetMs set to <ms> (effective=<ms>)
```

### 🔒 `/nestworld teststage3write <x> <y> <z>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
teststage3write: posted BLOCK_WRITE at (<x>,<y>,<z>), source=region#<N> dest=region#<N>,
before=<BlockName>, computed-return=<true|false> (will apply on main next tick's step 4c)
```


## 3. Володіння потоками, безпека, планувальник тіків

### 🟢 `/nestworld leveticksownership`
```
NW LevelTicks ownership: violations=0 mode=DIAGNOSTIC | mailbox: sent=0 applied=0 pending=0 duplicates=0 misrouted=0

```

### 🟢 `/nestworld ownershipassertions`
```
NW ownership assertions: violations=0 mode=DISABLED

```

### 🟢 `/nestworld mailboxaudit`
```
Mailbox audit is disabled — set NESTWORLD_MAILBOX_AUDIT=1 (or -Dnestworld.mailboxAudit=true) and restart

```

### 🟢 `/nestworld pressure`
```
Batch-apply: batchesFormed=0 applied=0 timedOut=0 messages=0 avgBatchSize=0.0 avgLockSetPerBatch=0.00 locksPerMessage=0.000 messagesInTimedOutBatches=0 | dupProbe: positions=0 duplicates=0 dupRate=0.00% batchesWithDup=0
Mailbox audit: sent=0 applied=0 pending=0 duplicates=0 misrouted=0

```

### 🟢 `/nestworld entityguard`
```
Entity ownership guard is disabled — set NESTWORLD_ENTITY_GUARD=1 (or -Dnestworld.entityGuard=true) and restart

```

### 🟢 `/nestworld entityrecheck`
```
Entity ownership recheck: 0 skip(s) (stale-snapshot ticks avoided)

```

### 🟢 `/nestworld scheduledtickloss`
```
NW scheduled-tick handoff loss: 1 free-running region(s)
  Region 6: entries produced=0 consumed=0 OVERWRITTEN=0
    block produced=0 consumed=0 OVERWRITTEN=0 | fluid produced=0 consumed=0 OVERWRITTEN=0 | other produced=0 consumed=0 OVERWRITTEN=0
    batches posted=0 overwrittenUnread=0 | lastProducerTick=-1 lastConsumerTick=-1 lastPostedBatch=0 lastOverwrittenBatch=0
  Recent overwritten entries (last 64, newest last):
    region=1 kind=OTHER pos=(0,0,0) producerTick=310965 gameTime=314032921
    region=1 kind=OTHER pos=(0,0,0) producerTick=310965 gameTime=314032921
    region=1 kind=OTHER pos=(0,0,0) producerTick=310965 gameTime=314032921
    region=1 kind=OTHER pos=(0,0,0) producerTick=310965 gameTime=314032921
    region=1 kind=OTHER pos=(0,0,0) producerTick=310966 gameTime=314032922
    region=1 kind=OTHER pos=(0,0,0) producerTick=310966 gameTime=314032922
    region=1 kind=OTHER pos=(0,0,0) producerTick=310966 gameTime=314032922
    region=1 kind=OTHER pos=(0,0,0) producerTick=310966 gameTime=314032922
    region=1 kind=OTHER pos=(0,0,0) producerTick=310967 gameTime=314032923
    region=1 kind=OTHER pos=(0,0,0) producerTick=310967 gameTime=314032923
    region=1 kind=OTHER pos=(0,0,0) producerTick=310967 gameTime=314032923
    region=1 kind=OTHER pos=(0,0,0) producerTick=310967 gameTime=314032923
    region=1 kind=OTHER pos=(0,0,0) producerTick=310968 gameTime=314032924
    region=1 kind=OTHER pos=(0,0,0) producerTick=310968 gameTime=314032924
    region=1 kind=OTHER pos=(0,0,0) producerTick=310968 gameTime=314032924
... [обрізано в цьому документі — повний вивід довший]
```

### 🟢 `/nestworld scheduledticklossreset`
```
NW scheduled-tick handoff loss reset (all regions)

```

### 🟢 `/nestworld dumpregistries`
```
Registry snapshot written to /opt/minecraft/./registry-snapshot.txt

```

### 🔒 `/nestworld scheduledtickstress <x> <y> <z> <count>`
```
формат виводу (приклад, НЕ виконано на живому сервері), ДВА рядки:
NW scheduledtickstress: base=(<x>,<y>,<z>) chunk=(<cx>,<cz>) owningRegion=<id|none>
interior=<id|NO (border-band or unowned)> freeRunning=<true|false|n/a>
NW scheduledtickstress: scheduled <count> block ticks along +X from (<x>,<y>,<z>) |
hasScheduledTick(base)=<true|false> (checked same-command, zero round-trip)
```

### 🔒 `/nestworld scheduledtickdedupcheck <x> <y> <z>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW scheduledtickdedupcheck: pos=(<x>,<y>,<z>) type=REPEATER hasScheduledTick=<true|false>
willTickThisTick=<true|false> | total dedupHits so far=<N>
```

### 🔒 `/nestworld blockmainprobe <ms> <region>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW blockMainProbe: blocked main for <ms>ms (region <id>): tick count <before> -> <after>
(delta=<N>, expected~<N> @ 20Hz)
```
(УВАГА: ця команда РЕАЛЬНО блокує головний потік на <ms> мс через Thread.sleep — саме тому
не запущено наживо, навіть попри те, що формат виводу тривіальний)


## 4. Пінінг

### 🟢 `/nestworld pins`
```
No pins (all entities and block entities tick on region threads).

```

### 🔒 `/nestworld pin <type>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Pinned <type> to main-thread ticking (takes effect next tick).
(помилка при невідомому типі: конкретний текст з NestworldPins.pin(), не вигадується тут)
```

### 🔒 `/nestworld unpin <type>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Unpinned <type> (resumes region-thread ticking next boundary scan).
(або помилка: "<type> was not pinned")
```

### 🔒 `/nestworld pinmod <namespace>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Pinned mod '<namespace>' — all its entities and block entities tick on main (takes effect next tick).
```

### 🔒 `/nestworld unpinmod <namespace>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
Unpinned mod '<namespace>' (resumes region-thread ticking).
(або помилка: "Mod '<namespace>' was not pinned")
```


## 5. Виконання дій гравця поза головним потоком

### 🟢 `/nestworld playertickexperiment list`
```
NW #31.3 player-tick experiment: masterFlag=OFF size=0

```

### 🟢 `/nestworld playerattackexperiment list`
```
NW #31.4 player-attack experiment: masterFlag=OFF size=0

```

### 🟢 `/nestworld playerinteractionexperiment list`
```
NW #31.4 step2 player-interaction experiment: masterFlag=OFF size=0

```

### 🟢 `/nestworld playeruseitemexperiment list`
```
NW #31.4 step3 player-useitem experiment: masterFlag=OFF size=0

```

### 🟢 `/nestworld playeruseitemonblockexperiment list`
```
NW #31.4 step4 player-useitemonblock experiment: masterFlag=OFF size=0

```

### 🟢 `/nestworld playerentityinteractexperiment list`
```
NW #31.4 item-on-entity experiment: masterFlag=OFF size=0

```

### 🟢 `/nestworld playerattacktiming`
```
NW #31.4 attack timing: main: count=0 totalMs=0.00 avgUs=0.0 | region: count=0 totalMs=0.00 avgUs=0.0

```

### 🟢 `/nestworld playerattacktimingreset`
```
NW #31.4 attack timing reset

```

### 🟢 `/nestworld playerinteractiontiming`
```
NW #31.4 step2 interaction timing: main: count=0 totalMs=0.00 avgUs=0.0 | region: count=0 totalMs=0.00 avgUs=0.0

```

### 🟢 `/nestworld playerinteractiontimingreset`
```
NW #31.4 step2 interaction timing reset

```

### 🟢 `/nestworld playeruseitemtiming`
```
NW #31.4 step3 useitem timing: main: count=0 totalMs=0.00 avgUs=0.0 | region: count=0 totalMs=0.00 avgUs=0.0

```

### 🟢 `/nestworld playeruseitemtimingreset`
```
NW #31.4 step3 useitem timing reset

```

### 🟢 `/nestworld playeruseitemonblocktiming`
```
NW #31.4 step4 useitemonblock timing: main: count=0 totalMs=0.00 avgUs=0.0 | region: count=0 totalMs=0.00 avgUs=0.0

```

### 🟢 `/nestworld playeruseitemonblocktimingreset`
```
NW #31.4 step4 useitemonblock timing reset

```

### 🟢 `/nestworld entityinteracttiming`
```
NW item-on-entity timing: main: count=0 totalMs=0.00 avgUs=0.0 | region: count=0 totalMs=0.00 avgUs=0.0

```

### 🟢 `/nestworld entityinteracttimingreset`
```
NW item-on-entity timing reset

```

### 🟢 `/nestworld playerinputstats`
```
NW player-input (mode=OFF): sent=0 applied=0 rerouted=0 dropped(rerouteLimit=0 queueFull=0 staleOrDup=0 playerGone=0) gaps=0 trackedPlayers=0 queueDepthCurrent=0 queueDepthMax=0

```

### 🟢 `/nestworld playerusecontroltiming`
```
NW player-use-control timing: enqueued=0 executed=0 queueLatencyAvgUs=0.0 queueLatencyMaxUs=0.0 rerouted=0 dropped=0

```

### 🟢 `/nestworld playerusecontroltimingreset`
```
NW player-use-control timing reset

```

### 🟢 `/nestworld interactionwaitbudget`
```
NW interaction-wait-budget: mainThreadInteractionWaitTotal=0.00ms mainThreadInteractionWaitAvgUs=0.0 mainThreadInteractionWaitMaxUs=0.0 waitCount=0 mainThreadInteractionFallbackCount=0 (budgetExhausted=0 timeout=0) K=1 X=50ms | realRoundTripAvgUs=0.0 realRoundTripMaxUs=0.0 realRoundTripCount=0 regionQueueDepthMax=0

```

### 🟢 `/nestworld interactionwaitbudgetreset`
```
NW interaction-wait-budget reset

```

### 🟢 `/nestworld interactionlatencydist`
```
NW interaction-latency-dist: roundTripSameRegionMs[n=0] p50=0.00 p90=0.00 p95=0.00 p99=0.00 max=0.00 | roundTripCrossRegionMs[n=0] p50=0.00 p90=0.00 p95=0.00 p99=0.00 max=0.00 | mainWaitMs[n=0] p50=0.000 p90=0.000 p95=0.000 p99=0.000 max=0.000 | callsPerTick[n=0] p50=0 p95=0 max=0

```

### 🟢 `/nestworld interactionwaitspikes`
```
NW interaction-wait spikes: count=0 (none recorded/none since reset)

```

### 🔒 `/nestworld player<action>experiment add <name>` (усі 6: playertick, playerattack, playerinteraction, playeruseitem, playeruseitemonblock, playerentityinteract)
```
формат виводу (приклад, НЕ виконано на живому сервері — потребує ІМ'Я РЕАЛЬНОГО ОНЛАЙН гравця):
Added <name> to #31.X <action> experiment
(або, якщо вже в наборі: "Already in set: <name> to #31.X <action> experiment")
(додатково, якщо master-флаг вимкнено: " (WARNING: -Dnestworld.player<Action>RegionExecution is
OFF -- this player's <action> will still run on main)")
(помилка, якщо гравець офлайн: "No online player named <name>")
```

### 🔒 `/nestworld player<action>experiment remove <name>` (усі 6)
```
формат виводу (приклад, НЕ виконано на живому сервері):
Removed <name> from #31.X <action> experiment
(або: "Was not in set: <name> from #31.X <action> experiment")
(помилка, якщо гравець офлайн: "No online player named <name> (nothing removed)")
```

### 🔒 `/nestworld interactionslots <k>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW interaction K (max wait slots/tick) set to <k>
```

### 🔒 `/nestworld interactionmaxwaitms <x>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW interaction X (max wait per call) set to <x>ms
```


## 6. Спліт/мердж регіонів — журнал подій

### 🟢 `/nestworld splitmergesummary`
```
NW split/merge: splits=3 (failed=0) merges=0 (failed=0) lockTimeouts=0 rapidSplitThenMerge(<2s)=0 splitsPerMin=0.0 mergesPerMin=0.0 spanMin=87.35 eventLogSize=3 | mergeCandidatesGenerated=137357 mergeCandidatesRejectedBeforeLock=137357 mergeCandidatesLockAttempted=0 mergeCandidatesSucceeded=0 mergeCandidatesFailedAfterLock=0 | regionCount[n=4096] min=4 p50=4 p95=4 p99=4 max=4 mean=4.0 stddev=0.00 oscillationRange=0

```

### 🟢 `/nestworld splitmergedump`
```
[01:13:24.835] SPLIT  split R0 -> [R1, R2]                     reason=fill-cores(spare)        avgTickMs=12.9 entities=11 opMs=2.89
[02:38:54.577] SPLIT  split R1 -> [R3, R4]                     reason=fill-cores(spare)        avgTickMs=14.1 entities=10 opMs=0.88
[02:40:45.677] SPLIT  split R3 -> [R5, R6]                     reason=fill-cores(spare)        avgTickMs=18.2 entities=12 opMs=0.81

```

### 🟢 `/nestworld splitmergereset`
```
NW split/merge event log reset

```


## 7. Мережа, з'єднання, пакети, admission control

### 🟢 `/nestworld connectiondiag`
```
NW connection-diag: maxConcurrentSendersEverOnOneConnection=0 | sendCalls=0 sendsPerSec=0.0 sendsPerTick~=0.0 sendAvgUs=0.0 sendMaxUs=0.0 | queueLockWaits=0 queueLockAvgUs=0.0 queueLockMaxUs=0.0 | eventLoopExecuteCalls=0 eventLoopExecuteAvgUs=0.0 eventLoopExecuteMaxUs=0.0 | windowSec=0.0 | inFlight tracking removed (lean rewrite -- see class javadoc) | perSource: main[n=0] | region[n=0] | forkjoin[n=0] | other[n=0] | broadcastComposition: playerTracked: broadcastCalls=0 fanOutSum=0 (0.0% of total) | otherEntityTracked: broadcastCalls=0 fanOutSum=0 (100.0% of total)

```

### 🟢 `/nestworld connectiondiagreset`
```
NW connection-diag reset

```

### 🟢 `/nestworld connectiondiagslow`
```
slow-call log removed (lean rewrite -- see class javadoc; use a dedicated JFR pass instead)

```

### 🟢 `/nestworld trackingreport`
```
=== ENTITY TRACKING REPORT ===
players: 0  TPS: 20.0  MSPT: 4.0
totalBroadcastCalls: 921,861  totalFanOutSends: 0
ENTITY (calls / fanOut / % of fanOut)
  PLAYER       calls=         0 fanOut=         0 (0.0%)
  ARMOR_STAND  calls=         0 fanOut=         0 (0.0%)
  MOB          calls=   506,436 fanOut=         0 (0.0%)
  ITEM         calls=   415,425 fanOut=         0 (0.0%)
  PROJECTILE   calls=         0 fanOut=         0 (0.0%)
  OTHER        calls=         0 fanOut=         0 (0.0%)
REASON (calls / fanOut / % of fanOut)
  SPAWN        calls=         0 fanOut=         0 (0.0%)
  REMOVE       calls=         0 fanOut=         0 (0.0%)
  MOVE         calls=   245,677 fanOut=         0 (0.0%)
  ROTATION     calls=   334,460 fanOut=         0 (0.0%)
  METADATA     calls=       679 fanOut=         0 (0.0%)
  EQUIPMENT    calls=         0 fanOut=         0 (0.0%)
  VELOCITY     calls=   341,037 fanOut=         0 (0.0%)
  EFFECT       calls=         0 fanOut=         0 (0.0%)
  PASSENGER    calls=         0 fanOut=         0 (0.0%)
... [обрізано в цьому документі — повний вивід довший]
```

### 🟢 `/nestworld trackingreset`
```
NW tracking metrics reset

```

### 🟢 `/nestworld outboundbatchreport`
```
NW outbound-batch: enabled=true maxBundleSize=512 flushCalls=1,220,081 | bundlesSent=0 packetsBundled=0 avgPacketsPerBundle=0.0 | rawSinglePacketSends=0 | totalConnectionSendCalls=0 | maxQueueDepthAtFlush=0 | flushTimeAvgUs=0.3 flushTimeMaxUs=3263.4 | bundleSendFailures=0

```

### 🟢 `/nestworld outboundbatchreset`
```
NW outbound-batch metrics reset

```

### 🟢 `/nestworld joinburstreport`
```
NW join-burst: chunkPacketsSent=0 | addPairingCalls=0 pairingPacketsSent=0 avgPacketsPerPairing=0.00
  PAIRING BY SOURCE ENTITY (calls / packets)
  PAIRING PACKET TYPES (calls)
  PAIRING REGION: same-region=0 (0.0%) cross-region=0 (100.0%)

```

### 🟢 `/nestworld joinburstreset`
```
NW join-burst metrics reset

```

### 🟢 `/nestworld chunkoutboundreport`
```
NW chunk-outbound: enabled=false budgetPerTick=200 | enqueued=0 drained=0 cancelled=0 droppedNotReady=0 | avgAgeAtDrainMs=0.0 maxObservedQueueSize=0 | pendingNow=0

```

### 🟢 `/nestworld chunkoutboundreset`
```
NW chunk-outbound metrics reset

```

### 🟢 `/nestworld pairingbatch status`
```
NW pairing-batch: enabled=false budgetPerTick=200 | addEnqueued=0 removeEnqueued=0 | addDrained=0 removeDrained=0 | cancelled=0 stale=0 sendFailures=0 | maxObservedQueueSize=0 pendingNow=0 | drainTimeTotalMs=0.0 avgDrainUsPerItem=0.00

```

### 🟢 `/nestworld pairingbatchreset`
```
NW pairing-batch metrics reset

```

### 🟢 `/nestworld movedecisionreport`
```
NW move-decision: moveInvocations=0 throttled=0 | spatialCull calls=0 candidates=0 | fullScan calls=0 candidates=0 | totalDecisions(candidates)=0

```

### 🟢 `/nestworld movedecisionreset`
```
NW move-decision metrics reset

```

### 🟢 `/nestworld naturalspawnerreport`
```
NW natural-spawner: tickChunksCalls=4,880,324 | avgTickChunksMs=0.07 maxTickChunksMs=1334.94 | avgCreateStateMs=0.008 maxCreateStateMs=1334.929 | avgTickingChunks=110.3 avgSpawnEligibleChunks=1.5 | spawnForChunkCalls=7,320,486 spawnCategoryCalls=0 successfulSpawns=0

```

### 🟢 `/nestworld naturalspawnerreset`
```
NW natural-spawner metrics reset

```

### 🟢 `/nestworld burstreport`
```
NW burst-admission: connectionsAccepted=0 loginStarted=0 playersCreated=0 playersTeleported=0 | chunkTrackingSetupCalls=0 avgSetupMs=0.000 maxSetupMs=0.000

```

### 🟢 `/nestworld burstreset`
```
NW burst-admission metrics reset

```

### 🟢 `/nestworld burstthreads`
```
NW thread-census: availableProcessors=20 | netty=1(runnable=1) region=4(runnable=0) main(runnable=1) other=53(runnable=24) | totalRunnable=26 oversubscriptionRatio=1.30

```

### 🟢 `/nestworld modcallbackreport`
```
NW mod-callback-attribution: enabled=false -- no third-party callbacks recorded yet

```

### 🟢 `/nestworld modcallbackreset`
```
NW mod-callback-attribution reset

```

### 🟢 `/nestworld modcallbackrouterreport`
```
NW mod-callback-router: enabled=false routedToMain=0 ranInline=0 pendingNow=0 maxObservedQueueSize=0 drainExceptions=0

```

### 🟢 `/nestworld modcallbackrouterreset`
```
NW mod-callback-router reset

```

### 🟢 `/nestworld admissionreport`
```
NW admission: enabled=false budgetPerTick=8 | enqueued=0 admitted=0 (immediate=0 queued=0) | pendingNow=0 maxQueueDepth=0 | avgWaitTicks=0.00 maxWaitTicks=0

```

### 🟢 `/nestworld admissionreset`
```
NW admission metrics reset

```

### 🟢 `/nestworld pairingadmissionreport`
```
NW pairing-admission: enabled=false globalBudget=64 perPlayerBudget=8 | admitted=0 denied=0 (globalCap=0 perPlayerCap=0) | maxGlobalUsedInOneTick=0

```

### 🟢 `/nestworld pairingadmissionreset`
```
NW pairing-admission metrics reset

```

### 🟢 `/nestworld connectionadmissionreport`
```
NW connection-admission: enabled=false maxPerWindow=8(~50ms) | admittedImmediate=0 queued=0 releasedFromQueue=0 droppedDeadInQueue=0 | queueDepthNow=0 maxQueueDepth=0

```

### 🟢 `/nestworld connectionadmissionreset`
```
NW connection-admission metrics reset

```

### 🟢 `/nestworld cpuplanner`
```
NestWorldCore CPU Capacity Report
CPU: model=Intel(R) Xeon(R) CPU E5-2699 v3 @ 2.30GHz physicalCores=14 logicalCpus=20 smtDetected=true detectionReliable=true
Benchmark: singleCoreScore=34,116,928 parallelScore=392,184,432 parallelEfficiency=0.82
Configured parameters:
  nettyWorkerThreads               type=NETWORK_WORK     value=40.0 effectiveDemand=40.00
  regionThreads(targetParallelism) type=REGION_WORK      value=19.0 effectiveDemand=19.00
  worldgenShards                   type=CPU_CONCURRENCY  value=1.0 effectiveDemand=1.00
  chunkGenBudget                   type=CPU_WORK_BUDGET  value=0.0 effectiveDemand=0.00
  pairingAdmissionGlobalBudget     type=CPU_WORK_BUDGET  value=64.0 effectiveDemand=0.64
  chunkOutboundBudget              type=CPU_WORK_BUDGET  value=200.0 effectiveDemand=2.00
  pairingOutboundBudget            type=CPU_WORK_BUDGET  value=200.0 effectiveDemand=2.00
  outboundBatchMaxBundleSize       type=QUEUE_LIMIT      value=512.0 effectiveDemand=0.00
  joinAdmissionBudget              type=CPU_WORK_BUDGET  value=8.0 effectiveDemand=0.16
  connectionAdmissionMaxPerWindow  type=NETWORK_WORK     value=8.0 effectiveDemand=0.40
Planner: totalEffectiveDemand=65.20 availableCapacity=16.00 oversubscriptionRatio=4.08
Status: CRITICAL -- configured workload may exceed available CPU capacity (estimated demand 65.2 effective cores vs 16.0 available, 4.08x oversubscription). Potential symptoms: high MSPT, admission backlog, reduced TPS, Netty contention, watchdog risk.

```

### 🟢 `/nestworld nearestplayerverify`
```
NW nearest-player-index verify: enabled=false checks=0 mismatches=0

```

### 🟢 `/nestworld nearestplayerverifyreset`
```
NW nearest-player-index verify metrics reset

```

### 🟢 `/nestworld nearestplayermismatches`
```
NW nearest-player-index mismatch log (0 entries):

```

### 🟢 `/nestworld movethreshold`
```
NW moveTooQuicklyMultiplier effective=1.0 (override=-1.0, boot-default(server.properties)=1.0)

```

### 🟢 `/nestworld movepacketcap`
```
NW maxMovementPacketsPerTick effective=20 (override=-1, boot-default(server.properties)=20)

```

### 🟢 `/nestworld floatingkick`
```
NW floating-too-long kick effective=true (override=0, boot-default(server.properties)=true)

```

### 🟢 `/nestworld nearestplayermismatchesdump`
```
Dumped 0 mismatch entries to /opt/minecraft/nearestplayer_mismatches.txt
```

### 🔒 `/nestworld recipientfanout <ticks>`
```
формат виводу (приклад, НЕ виконано на живому сервері) — багаторядковий звіт з
TrackingMetrics.recipientFanoutReport(), формат рядків залежить від накопичених даних,
не є єдиним фіксованим рядком — не показано конкретного шаблону, щоб не вигадувати вміст.
```

### 🔒 `/nestworld outboundbatch on|off`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW outbound-batch ENABLED
(або: NW outbound-batch disabled)
```

### 🔒 `/nestworld outboundbatchmaxsize <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW outbound-batch maxBundleSize=<n>
```

### 🔒 `/nestworld chunkoutbound on|off`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW chunk-outbound ENABLED
(або: NW chunk-outbound disabled)
```

### 🔒 `/nestworld chunkoutboundbudget <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW chunk-outbound budgetPerTick=<n>
```

### 🔒 `/nestworld pairingbatch on|off`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW pairing-batch ENABLED
(або: NW pairing-batch disabled)
```

### 🔒 `/nestworld pairingbatchbudget <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW pairing-batch budgetPerTick=<n>
```

### 🔒 `/nestworld admission on|off`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW admission controller ENABLED
(або: NW admission controller disabled)
```

### 🔒 `/nestworld admissionbudget <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW admission budgetPerTick=<n>
```

### 🔒 `/nestworld pairingadmission on|off`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW pairing-admission gate ENABLED
(або: NW pairing-admission gate disabled)
```

### 🔒 `/nestworld pairingadmissionbudget <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW pairing-admission globalBudgetPerTick=<n>
```

### 🔒 `/nestworld pairingadmissionperplayer <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW pairing-admission perPlayerBudgetPerTick=<n>
```

### 🔒 `/nestworld connectionadmission on|off`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW connection-admission gate ENABLED
(або: NW connection-admission gate disabled)
```

### 🔒 `/nestworld connectionadmissionbudget <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW connection-admission maxPerWindow=<n>
```

### 🔒 `/nestworld setmovethreshold <multiplier>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW moveTooQuicklyMultiplier set to <multiplier> (effective=<multiplier>)
```

### 🔒 `/nestworld setmovepacketcap <n>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW maxMovementPacketsPerTick set to <n> (effective=<n>)
```

### 🔒 `/nestworld setfloatingkick <true|false>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW floating-too-long kick ENABLED (live, takes effect immediately)
(або: NW floating-too-long kick DISABLED (live, takes effect immediately))
(НЕ виконано: живо мутує реальну анти-флайхак поведінку для всіх онлайн-гравців негайно)
```


## 8. Крафт/рецепти

### 🟢 `/nestworld recipecensus reset`
```
NW recipe-census reset, window started

```

### 🟢 `/nestworld recipecensus report`
```
RecipeLookupCensus: window=0.0s enabled=true totalCalls=0 callsPerSec=0.00

```

### 🟢 `/nestworld recipecensus reportbythread`
```
RecipeLookupCensus by thread class (window=0.0s):

```

### 🟢 `/nestworld recipecensus stop`
```
NW recipe-census stopped

```

### 🟢 `/nestworld recipetiming report`
```
RecipeLookupTiming: window=3082910.9s enabled=false calls=0 callsPerSec=0.00
  stalls: >100ms=0  >500ms=0  >1s=0  >5s=0  >10s=0

```

### 🟢 `/nestworld recipetiming stop`
```
NW recipe-timing stopped

```

### 🟢 `/nestworld craftingbatch reset`
```
NW crafting-batch metrics reset

```

### 🟢 `/nestworld craftingbatch report`
```
CraftingBatchMetrics: batchesStarted=0 batchesCompleted=0
  slotsChanged: unbatched=0  suppressed(batched)=0  actualRecipeLookups=0

```

### 🟢 `/nestworld recipematchprofiler report`
```
RecipeMatchProfiler: window=3082910.9s enabled=false
  total candidates checked=0  cheap(<50us)=0  expensive(>=50us)=0
  top contributors by total time (of 0 distinct recipes seen):

```

### 🟢 `/nestworld recipematchprofiler stop`
```
NW recipe-match-profiler stopped

```

### 🟢 `/nestworld candidateindex report`
```
RecipeCandidateIndexShadowStats: window=3082910.9s enabled=false lookups=0
  FALSE NEGATIVES=0  COMPARATOR MISMATCHES=0  (both MUST be 0)

```

### 🟢 `/nestworld candidateindex stop`
```
NW candidate-index shadow stats stopped

```

### 🟢 `/nestworld lastrecipeaudit report`
```
LastRecipeCacheAudit: window=3082910.9s enabled=false lookups=0
  simulated cache would-hit=0 (0.0% of lookups) -- lookups a lastRecipe fast-path would have skipped entirely
  WRONG cache hits (matches()==true but real BCLib winner differs)=0  (MUST be 0 for a universal lastRecipe fast-path to be provably safe)

```

### 🟢 `/nestworld lastrecipeaudit stop`
```
NW last-recipe cache audit stopped

```

### 🔒 `/nestworld candidateindex production on|off`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW candidate-index production mode: ON
(або: NW candidate-index production mode: OFF)
(НЕ виконано: вмикає РЕАЛЬНЕ використання кандидат-індексу в проді, не тільки shadow-режим —
пряма зміна поведінки крафту, а не діагностика)
```


## 9. Redstone / позиційний спостерігач блоків

### 🟢 `/nestworld blockwatch status`
```
BlockTickWatcher: enabled=false dimension=minecraft:overworld positions=0 linesWritten=0

```

### 🟢 `/nestworld blockwatch stop`
```
NW blockwatch stopped

```

### 🟢 `/nestworld blockwatch clear`
```
NW blockwatch positions cleared

```

### 🔒 `/nestworld blockwatch start <path>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW blockwatch started -> <path> (<N> positions watched)
(або, якщо файл не відкрився: NW blockwatch FAILED to open <path>)
(НЕ виконано: запускає безперервний запис по рядку ЗА ТІК у файл, доки не зупинено — мутує стан,
а не разова діагностика)
```

### 🔒 `/nestworld blockwatch addpos <x> <y> <z>`
```
формат виводу (приклад, НЕ виконано на живому сервері):
NW blockwatch added position <x> <y> <z> (<N> total)
```

---

## Суміжне: `/spark`

Якщо реальний мод `spark` НЕ завантажений (лише в dev-середовищі), NestWorldCore реєструє власний `/spark [args]` як міст до вбудованого standalone spark-агента (`SparkBridge`) — у проді, де мод spark присутній, ця команда НЕ реєструється (щоб не затуляти справжню). Канал виводу залежить від самого spark-агента, не від `sendSuccess`-механіки вище — не задокументовано в цьому файлі.

---

*Документ згенеровано прямим читанням `NestworldCommand.java` (усі 3008 рядків, включно з тілами всіх хендлерів для колонки "Канал") — жодне поле виводу чи канал не вигадані, усе взято з реальних `Component.literal`/`String.format`/`sendSuccess(...)` викликів у коді станом на 2026-09-04.*
