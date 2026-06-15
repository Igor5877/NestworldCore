# Стрес-тест: join/leave-шторм (конект-DoS), 2026-06-15

Сценарій: рій mineflayer-ботів (`/root/bots/churn.js`) рампиться 50→550 одночасних,
кожен живе 4-12с потім виходить → постійний join/leave (як бот-DoS на відкритих
серверах). Сервер: canary-test, білд 47.4.77, 16ГБ heap, суперфлет, max-players=3000,
view/sim-distance=4, усі боти з 127.0.0.1.

## Результат: ядро вистояло, межа — конект-шар

| target | реально онлайн | tick (mean) | TPS | errs | srv RAM |
|---|---|---|---|---|---|
| 100 | 95 | 35мс | 20.0 | 0 | 4.8G |
| 200 | 200 | 35мс | 20.0 | 11 | 5.0G |
| 300 | 300 | 35мс | 20.0 | 36 | 5.6G |
| 400 | 400 | 41мс | 20.0 | 50 | 5.8G |
| 500 | ~450 | 48мс | 20.0 | 327 | 6.3G |
| 550 | **~227** | 43мс | 20.0 | **388** | 6.3G |

- **20 TPS (mean) трималось весь рамп.** Тік 31-48мс (під бюджетом 50мс).
- NestworldCore **розклав гравців на 12 регіонів** (fill-split) — entity-tick шардився.
- **Межа = vanilla конект/login-шар:** при target>400 онлайн ≪ target, errs росли 0→388 —
  сервер відбивав конекти (login-обробка + chunk-load на main + same-IP throttle).
- RAM стабільний (4.8→6.3ГБ, без витоку).
- **Лаг = моментні 2-сек «Can't keep up» на пачках join'ів** (синхронний chunk-load на main),
  НЕ колапс — середнє лишалось 20 TPS.

## Висновок
**Шардинг захищає ГЕЙМПЛЕЙ (ентіті/редстоун/блоки/натовп) — і добре тримає під штормом.**
Конект-флуд б'є **головний потік: мережу + завантаження чанків + обробку join/leave**, які
шардинг НЕ покриває (і структурно не може дешево — це Folia-клас).

Захист від конект-DoS — **окремий шар, не ядро:**
- **проксі** (Velocity/BungeeCord) з anti-bot,
- **connection-throttle** (vanilla `network-compression`, server connection limits),
- **firewall rate-limit** на нові TCP-конекти з IP,
- **login-queue / anti-VPN** плагін.

## Інструменти тесту (для повтору)
- node18 static: `/root/node-v18.20.4-linux-x64/bin`
- боти: `/root/bots/` (mineflayer). `churn.js <maxTarget> <host> <port> <step>` — рамп join/leave.
  `swarm.js <N> <host> <port>` — N стабільних ботів (для tracking/entity тестів).
- вимір без spark: фазовий лог сервера `vanilla=Xмс` (main) vs `regionPool=Xмс` (region-потоки).
- запускати node з `--max-old-space-size=40000` для багатьох ботів.
