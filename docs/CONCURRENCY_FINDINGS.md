# Concurrency-баги під навантаженням — знахідки стрес-тесту (2026-06-15)

Спільний стрес-тест (mineflayer-боти + RCON-спавн натовпів мобів) на білді 47.4.85→87
знайшов 3 проблеми. Інструменти: `/root/bots/{churn,swarm}.js`, node18, фазовий лог
`vanilla=Xмс`(main) vs `regionPool=Xмс`(region-потоки).

## #1 — гонка ClassInstanceMultiMap (Canary-specific) — ВИПРАВЛЕНО
**Симптом:** натовп мобів цілить гравця → `NearestAttackableTargetGoal` →
`getEntitiesOfClass` з багатьох region-потоків → `ArrayIndexOutOfBoundsException` у
`Reference2ReferenceOpenHashMap.rehash` → зависання 51с.
**Корінь:** наш патч робить `ClassInstanceMultiMap` потокобезпечним
(ConcurrentHashMap+CopyOnWriteArrayList), АЛЕ Canary mixin'ами
`collections.entity_by_type`/`entity_filtering`/`chunk.entity_class_groups`
ПІДМІНЯЄ її непотокобезпечним fastutil-мапом → наша безпека анульована.
**Фікс:** `NestworldCompat` (src/main/java/net/nestworld/region/NestworldCompat.java) —
на старті детектить Canary/radium/lithium без disable-конфіга → гучний лог
(«мод THREAD-UNSAFE, функції в ядрі потокобезпечно, RESTART») + авто-вписує
`mixin.collections.entity_by_type=false` тощо в `canary.properties`.
`-Dnestworld.strictCompat=true` → hard-stop. Валідовано: рестарт → Canary «3
override» → гонка зникла (AIOOBE=0).

## #3 — over-split на щільному натовпі — ВИПРАВЛЕНО
**Симптом:** ~500 зомбі купою → hard-split у 30 дрібних регіонів на 8 ядрах →
2-3 TPS (regionPool 200мс+) + надмірний chunk-churn.
**Корінь:** щільний натовп номінально роздільний (~3 чанки), тож постійно клірить
поріг 25мс і фрагментується; >2× ядер регіонів = лише накладні бар'єру, не паралелізм.
**Фікс:** `RegionSplitManager.MAX_REGIONS_TOTAL = max(4, nestworld.maxRegions, cores*2)`;
при active>=cap не ділимо (бюджет поглинає). Валідовано: 500 зомбі → кап 16 (не 30),
regionPool 208→60мс, сервер вижив (раніше падав).

## #2 — гонка PoiManager DistanceTracker (vanilla) — МІТИГОВАНО, не виправлено
**Симптом:** під важким натовпом → `ArrayIndexOutOfBoundsException` length 33 у
`LongLinkedOpenHashSet.removeFirstLong` → `LeveledPriorityQueue` →
`PoiManager$DistanceTracker` → `ChunkMap.tickChunks` → `ServerChunkCache.tick` →
`NestworldRegionSystem.tickAllRegions:345`.
**Корінь:** у фазі-1 `overworld.tick()` main обробляє POI/chunk DistanceTracker-чергу,
а region-потоки (scheduled block-тіки через колбек + їхні `getChunk`) одночасно
змінюють ticket-рівні chunk-source → `DistanceTracker.update()` з region-потоку
паралельно з main'овим drain черги → корупція непотокобезпечної `LeveledPriorityQueue`.
**Статус:** кап #3 знизив chunk-churn → у тестах не стрельнув. Під екстремальним
навантаженням теоретично може.
**Дизайн фіксу (TODO):** зробити DistanceTracker-оновлення з region-потоків
потокобезпечними АБО відкласти chunk-ticket-зміни region-потоків на main (як
deferred-spawn). Тобто region-потоки не мають мутувати chunk-source POI/lighting
черги під час main'ового `getChunkSource().tick()`. Кандидат: відкладена черга
chunk-load-запитів від region-потоків, що дренажиться на main між фазами.

## Загальний висновок
- NestworldCore САМ потокобезпечний; #1 — Canary-конфлікт (заблоковано guard'ом).
- #3 — внутрішня тюнінг-проблема (виправлено капом).
- #2 — реальний внутрішній gap (vanilla POI-черга), мітиговано, треба глибокий фікс.
- Під ЗВИЧАЙНИМ навантаженням (churn-тест 550 гравців) — 20 TPS, без цих крашів;
  вони лише під ЕКСТРЕМАЛЬНИМИ натовпами мобів (точкові хотспоти).

## Спроба Folia-style non-blocking chunk reads (2026-06-15) — ПРОВАЛ, відкочено
Профіль dense-crowd: ~25% region-потоків стоять на синхронному завантаженні чанків
(`Entity.isInsideWall`→getBlockState→getChunk(load=true)→CompletableFuture.join на main).
Спроба (за прапором nonBlockingChunkReads): незавантажене читання region-потоку →
shared `EmptyLevelChunk` (void-air) замість блоку. Прапор ПРИБРАВ stall (getChunkOffThread=0),
АЛЕ сервер крашить — AIOOBE «length 65» ×42 + fatal: EmptyLevelChunk ламається в code-path'ах,
що очікують справжній чанк (+ гонка на спільному екземплярі). Відкочено.
ВИСНОВОК: shared-empty-chunk хак не працює. Справжня Folia = повна регіоналізація chunk-системи
(кожен регіон вантажить свої чанки async без блоку на main) — місяці роботи, окремий великий проєкт.
Dense-crowd залишається обмеженим Mob.tick AI (Canary-AI допомагає) + цією chunk-серіалізацією.
