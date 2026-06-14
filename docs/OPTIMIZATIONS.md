# NestworldCore — оптимізації (як працюють)

Філософія: ваніла крутить усе на одному ядрі; NestworldCore **розкидає
найдорожче (AI мобів, редстоун, block-ticks) по region-потоках**, тримає
головний потік вільним, а нероздільні хотспоти throttle-ить бюджетом локально.

## 1. Шардинг по потоках (головна)
Світ → прямокутні чанк-регіони, кожен тікає власним потоком паралельно.
Протокол: main будить region-потоки (CountDownLatch-бар'єр) → вони тікають
ентіті/блоки → main чекає. Spark-доказ (ZombieCraft): ваніл `Mob.tick` 116с +
`LivingEntity.tick` 117с на одному потоці → у нас винесено на region-потоки,
main паркується (`Unsafe.park`). Той самий світ: ваніл 18.8 TPS/50мс → core 20 TPS/37мс.

## 2. Адаптивний спліт/мердж
Спліт коли регіон >25мс ×5 оцінок; мердж <10мс ×25. Load-aware розріз скориться
за щільністю block-tick heat — НЕ ріже редстоун-машину (band-veto тільки по heat),
але вільно ріже натовп ентіті.

## 3. Паралельні фази (work-раунди)
scheduled block/fluid ticks, block entities, block events (поршні), random ticks —
бакетяться по регіонах, виконуються паралельно. Прикордонна смуга 2 чанки → на main
ДО раунду (каскади можуть перетнути межу).

## 4. Бюджет + deferral
40мс/тік на регіон, надлишок → round-robin на наступний тік. Хотспот гальмує
локально, не lockstep усього сервера. 500 TNT-вагонеток: 15.8→20 TPS.

## 5. Lock-free читання чанків
Region-потоки читають FULL-чанки без round-trip на main (ванільний шлях = 10× perf-баг)
+ 16-слотовий per-thread кеш.

## 6. Alternate Current (редстоун)
O(n) wire-алгоритм замість ванільного каскаду: 35→19мс. Per-region bounded WireHandler
(мережа за межі → відкат на main).

## 7. Cap колізій (точкові хотспоти)
N ентіті в 1 блоці = O(n²), шардинг не паралелить. Cap 8 сусідів/тік
(`maxMinecartPush`/`maxEntityPush`). 500 вагонеток: 57.5→5мс.
⚠️ Цей патч (`LivingEntity.getOtherPushableEntities`) КОНФЛІКТУЄ з Lithium-родиною
(Canary/radium/lithium) — вони мікшать той самий метод. Прибрати один із них.

## 8. Thread-safety (умова для решти)
ConcurrentEntitySectionStorage · ClassInstanceMultiMap→concurrent · EntityLookup→concurrent ·
per-thread CollectingNeighborUpdater · PalettedContainer lock-фікс · **deferred off-main
spawns** (черга → реєстрація на main, бо ChunkMap не thread-safe).

## Мод-сумісність (з тестів)
- Forge-нативні моди працюють і **прискорюються** (Create: BE у region-бакети).
- Моди з власним ServerTickEvent (AE2) — сумісні, але **НЕ прискорюються** (self-tick на main).
- FFAPI/Connector — працюють ПІСЛЯ фіксу (іменований предикат у LivingEntity замість лямбди).
- Lithium-родина (Canary/radium) — конфлікт з cap колізій (п.7). Auto-pin/pinmod для проблемних типів.
