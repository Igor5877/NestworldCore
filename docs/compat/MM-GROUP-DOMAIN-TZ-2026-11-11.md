# ТЗ: MM-GROUP-DOMAIN-001 — мультиблок MasterfulMachinery як один домен виконання

Статус: ЧЕРНЕТКА для перегляду власником. Нічого не реалізовано, на live нічого не змінено.
Дата: 2026-11-11. Мод: Masterful Machinery (modId `mm`), live `MasterfulMachinery-3-1.20.1-0.1.35.10.jar`,
джерело https://github.com/BOLTMAGIC/MasterfulMachinery (аудит на коміті `aff2a7e0^` = 0.1.35.10).

## 1. Проблема (evidence)

- **CONFIRMED (live стек):** `ConcurrentModificationException` у `ItemPortHandler.reindexSlot:129` (`HashMap.computeIfAbsent`)
  під AE2 `ExportBusPart` → `StorageExportStrategy` → `ItemPortHandler.insertItem` на `Server thread`
  (10.11 08:15, 11.11 14:02, 11.11 17:16; tick-skip). Mapping-функція мапу не змінює → паралельний запис з іншого потоку.
- **CONFIRMED (код):** `ItemPortHandler` повністю несинхронізований (`partialSlots`/`fullSlots`/`itemTotals` HashMap, BitSet).
  Порт пасивний; в нього пишуть: (a) **контролер** `MachineControllerBlockEntity.tick` (входи/виходи рецептів, порти можуть
  бути в інших чанках), (b) власний тік порту `PortAutoIO`, (c) AE2 шини, `Ae2ItemPortStorage`, gateway, труби.
- **STRONGLY INDICATED:** другий записувач — контролер у REGION. AE2-аддон `NeighborAffinity` (CORE-PINZONE-001) пінить до MAIN
  лише **позицію порту**, а контролер лишається в REGION → мультиблок розірваний між доменами, CME тривають і після 0.9.1.
- **CONFIRMED (mining-тест 0.9.4, 19:46):** ~47% bus-операцій AE2 — порти MM (`colossal_item_port_output` 22 670 import,
  `colossal_item_port_input` 10 933 + `titanic_item_port_input` 6 168 export); `linkedSameDomain=0`.
  Overworld і jamd:mining мають по одному регіону #0, тож причина — не межі регіонів, а position-pin'и до MAIN у
  радіусі 32 (`BusTransit.linkedSameDomain` перевіряє весь квадрат ±32, бо порт не знає свого контролера).
- **CONFIRMED (знімок світу 11.11):** 151 контролер (117 overworld, 34 mining), **0** з `NetworkLink` → шлях
  `NetworkLink.tickController → PortDrainer → AE2 MEStorage.insert` з тіку контролера зараз не використовується.

## 2. Ціль

Контролер MM і всі його порти завжди виконуються **в одному домені** (домен контролера), а AE2 шини працюють з портом
через transit у цьому домені. Наслідки: зникає клас CME, ~47% bus-трафіку можна винести з MAIN.
GAMEPLAY_EQUIVALENCE: жодних throttle/пропуску тіків; dup 0 / loss 0.

## 3. Межі (правило власника: аддони без взаємних залежностей)

| Шар | Що робить | Чого НЕ знає |
|---|---|---|
| **Core** | Загальний механізм «група BE»: anchor-позиція + множина членів → усі члени резолвляться в домен anchor'а. Ref-counted, runtime-only, як CORE-PINZONE-001. Запит `groupDomain(level, pos)`. | класів MM чи AE2 |
| **nestworld-compat-mm** (новий) | Аудит байткоду MM (fail-closed), реєстрація групи «контролер → порти» при формуванні/зміні структури, зняття при розформуванні/вивантаженні. | AE2-аддона |
| **nestworld-compat-ae2** | `linkedSameDomain` замість радіуса 32 питає Core про групу позиції; є група → transit у домен групи; нема → як зараз (MAIN). | MM-аддона |

Жоден аддон не імпортує інший і не має його в `mods.toml`. Без MM-аддона все працює як зараз.

## 4. Core: BE-GROUP-AFFINITY-001 (мінімально)

- `registerGroup(level, anchorPos, long[] members) → token`, `release(token)`; членство ref-counted.
- `resolveBlockEntityRegion(pos, type)`: якщо pos — член групи → результат резолву **anchor**'а (з його pin'ами/border band).
  Пріоритет: type-pin (MAIN) > група > position-pin > геометрія. Якщо будь-який член position-pinned до MAIN →
  уся група MAIN (не розривати групу; fail-closed).
- Зміна домену групи (split/merge/pin) переводить усіх членів атомарно в межах одного тіку (той самий механізм handoff,
  що `BlockEntityDomainHandoff`).
- Група не може охоплювати незавантажений чанк: член у незавантаженому чанку → група MAIN до завантаження.
- Діагностика: `nestworld begroup stats|at <pos>`.

## 5. nestworld-compat-mm

- Аудит SHA-256 класів: `MachineControllerBlockEntity`, `ItemPortBlockEntity`, `ItemPortStorage`, `ItemPortHandler`,
  `AbstractPortBlockEntity`, `PortAutoIO`, `MultiblockLookup`, `Ae2ItemPortStorage`, `NetworkLink`. Інша версія MM → вимкнено, лог.
- Хук на точку, де контролер збирає `portBlockEntities` (`MachineControllerBlockEntity` ~ l.462) і на розформування/
  `setRemoved`/chunk unload → register/release групи (anchor = контролер, члени = усі порти + gateway).
- `NetworkLink.tickController` (MM пише в AE2 grid з тіку контролера): виконувати лише на MAIN (handoff), інакше
  GRID_ISOLATION порушується. Зараз 0 linked-машин, але захист потрібен до вмикання.
- Ніякої зміни геймплею MM.

## 6. Proof obligations (фікстури, до будь-якого live)

1. Повторення CME: контролер REGION + export bus на порт MAIN (поточний стан) → CME відтворюється (negative control).
2. З групою: той самий сценарій 0 CME, dup 0 / loss 0 (облік предметів: вхід мережі − спожито рецептами + вихід = const).
3. Розформування/злам порту/злам контролера/unload чанку посеред transit → утримані предмети повертаються, витоків lease 0.
4. Група через межу регіонів (rig з 2+ регіонами) → усі члени в домені anchor'а; split/merge під навантаженням → 0 CME.
5. Без MM-аддона: поведінка ідентична 0.9.4 (лічильники, TPS у межах шуму).
6. Інша версія MM (змінений хеш) → аддон вимкнено, fail-closed.
7. Продуктивність: ON-only mining (66 чанків, 90/120 s, JFR), метрика — частка bus-операцій поза MAIN і MAIN ms/tick.

## 7. Не входить

Mod API; зміни в інших модах; оновлення MM до 0.1.36.x (його оптимізації гонку не прибирають);
live-деплой (тільки після PASS і рішення власника).

## 8. Питання власнику

1. Core-механізм групи (п.4) — ок як вузький внутрішній, не Mod API?
2. Якщо член групи position-pinned до MAIN → вся група на MAIN (безпечно, але менше виграшу). Ок?
