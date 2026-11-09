# Документація NestWorldCore

Навігацію звірено 2026-11-08. [Огляд проєкту](../README.md) · [Стан і roadmap](../ROADMAP.md).

## З чого почати

| Питання | Документи |
| --- | --- |
| Як влаштоване ядро | [Архітектура](ARCHITECTURE.md), [локальні тіки](LOCAL_TICK_STAGE4.md) |
| Як керувати сервером | [Admin](NESTWORLD-ADMIN.md), [команди](NESTWORLD_COMMANDS.md) |
| API для модів | [Архітектура API](NESTWORLD_API_ARCHITECTURE.md), [актуальне пакування](PACKAGING.md), [API-1B closure](contracts/API-1B-CLOSURE-lifecycle.md) |
| Зовнішні модулі | [Module SPI / loader](MODULES.md) |
| Сумісність модів | [Політика виправлень](MOD_COMPATIBILITY_FIXES.md), [AE2 matrix](compat/AE2-COMPATIBILITY-MATRIX.md), [AE2 work packages](compat/AE2-COMPAT-ARCHITECTURE.md) |
| Масштабування | [Чинний W1 R1](performance/PERF-1A-R1-results.md), [W2 і закриття PERF-1A](performance/PERF-1A-W2-results.md) |
| Порт на NeoForge | [Журнал реалізації та повторних перевірок](B3_MIXIN_VERTICAL_SLICE.md), [Core / Adapter](CORE_ADAPTER_CLASSIFICATION.md) |

## Як читати статуси

Ця папка містить контракти, плани, аудити, інциденти та результати різних етапів.
Дата файла або слово `PASS` не є універсальною гарантією готовності.

- У великих журналах перевіряйте пізніші поправки та явно зазначене scope:
  початковий статус може бути переглянутий у наступному розділі.
- Для PERF-1A первісний `PERF-1A-results.md` невалідний; чинний W1 — R1.
- Для API distribution рішення PKG-002 уточнює старі рекомендації щодо embedding.
- Для NeoForge старий portability scoping не відображає пізніші виконані етапи;
  ранні shell-only перевірки також не замінюють повторні тести з активним core tick.
- Для AE2 поточні architecture/matrix враховують пізніші етапи, ніж початкові
  closure/gate таблиці. Engineering verification та production verification різні.
- Реалізацію перевіряйте в коді: наприклад, ранній окремий design gate PINZONE
  не означає відсутності пізніше доданих адресних position pins.

[README Forge](README.md), [Contributing Forge](CONTRIBUTING.md) та
[Support Forge](SUPPORT.md) збережені як матеріали базового проєкту.
Ця сторінка є точкою входу в документацію NestWorldCore.

## Каталог документації ядра

Назви нижче — посилання на матеріали, а не позначки їхньої актуальності чи готовності.

### Основні документи

- [API_WORLD_INTEGRATION.md](API_WORLD_INTEGRATION.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
- [B2_NEOFORGE_INJECTION_ARCHITECTURE.md](B2_NEOFORGE_INJECTION_ARCHITECTURE.md)
- [B3_MIXIN_VERTICAL_SLICE.md](B3_MIXIN_VERTICAL_SLICE.md)
- [BATCH_APPLY_COALESCING_DESIGN.md](BATCH_APPLY_COALESCING_DESIGN.md)
- [CHUNK_GEN_PIPELINE_OBSERVABILITY_SPEC.md](CHUNK_GEN_PIPELINE_OBSERVABILITY_SPEC.md)
- [CONCURRENCY_FINDINGS.md](CONCURRENCY_FINDINGS.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
- [CORE_ADAPTER_CLASSIFICATION.md](CORE_ADAPTER_CLASSIFICATION.md)
- [DEPENDENCY_DIRECTION_AUDIT.md](DEPENDENCY_DIRECTION_AUDIT.md)
- [GEN_SPIKE.md](GEN_SPIKE.md)
- [ITEM_VISIBILITY_AND_CORE_COMPAT.md](ITEM_VISIBILITY_AND_CORE_COMPAT.md)
- [LOCAL_TICK_STAGE4.md](LOCAL_TICK_STAGE4.md)
- [MODULE0.5_LIFECYCLE_CONTRACT.md](MODULE0.5_LIFECYCLE_CONTRACT.md)
- [MODULE0_AUDIT.md](MODULE0_AUDIT.md)
- [MODULES.md](MODULES.md)
- [MOD_COMPATIBILITY_FIXES.md](MOD_COMPATIBILITY_FIXES.md)
- [NEOFORGE_1211_DIFF_AUDIT.md](NEOFORGE_1211_DIFF_AUDIT.md)
- [NEOFORGE_1211_PORTABILITY_SCOPING.md](NEOFORGE_1211_PORTABILITY_SCOPING.md)
- [NESTWORLD-ADMIN.md](NESTWORLD-ADMIN.md)
- [NESTWORLD_API_ARCHITECTURE.md](NESTWORLD_API_ARCHITECTURE.md)
- [NESTWORLD_COMMANDS.md](NESTWORLD_COMMANDS.md)
- [NESTWORLD_INSPECTOR_SPEC.md](NESTWORLD_INSPECTOR_SPEC.md)
- [OPTIMIZATIONS.md](OPTIMIZATIONS.md)
- [OPTIMIZATION_PACKAGE.md](OPTIMIZATION_PACKAGE.md)
- [P0_3_AUTONOMOUS_BASELINE_SPEC.md](P0_3_AUTONOMOUS_BASELINE_SPEC.md)
- [P0_REGIONTHREADPOOL_REDESIGN_SPEC.md](P0_REGIONTHREADPOOL_REDESIGN_SPEC.md)
- [P1_5_ACCEPTANCE_MATRIX.md](P1_5_ACCEPTANCE_MATRIX.md)
- [P1_5_RESULTS.md](P1_5_RESULTS.md)
- [P1_WORLDGEN_MAINTHREAD_DECOUPLING_SPEC.md](P1_WORLDGEN_MAINTHREAD_DECOUPLING_SPEC.md)
- [P2_AUDIT_RESULTS.md](P2_AUDIT_RESULTS.md)
- [P2_REGIONTHREADPOOL_BARRIER_AUDIT.md](P2_REGIONTHREADPOOL_BARRIER_AUDIT.md)
- [PACKAGING.md](PACKAGING.md)
- [PARALLEL_TRACKING_DESIGN.md](PARALLEL_TRACKING_DESIGN.md)
- [PERFORMANCE_STRESS_TEST_SPEC.md](PERFORMANCE_STRESS_TEST_SPEC.md)
- [PHASE10_GLOBAL_STATE_AUDIT.md](PHASE10_GLOBAL_STATE_AUDIT.md)
- [PHASE10_GLOBAL_STATE_AUDIT_SPEC.md](PHASE10_GLOBAL_STATE_AUDIT_SPEC.md)
- [PHASE11_1_PLAYER_EXECUTION_CONTRACT.md](PHASE11_1_PLAYER_EXECUTION_CONTRACT.md)
- [PHASE11_PLAYER_REGION_EXECUTION_SPEC.md](PHASE11_PLAYER_REGION_EXECUTION_SPEC.md)
- [PHASE9_1_BLOCK_MUTATION_AUDIT.md](PHASE9_1_BLOCK_MUTATION_AUDIT.md)
- [PHASE9_1_ENTITY_MUTATION_AUDIT.md](PHASE9_1_ENTITY_MUTATION_AUDIT.md)
- [PHASE9_28_CROSSREGION_BLOCKENTITY_CATALOG.md](PHASE9_28_CROSSREGION_BLOCKENTITY_CATALOG.md)
- [PLAYER_REGION_OWNERSHIP_ARCHITECTURE.md](PLAYER_REGION_OWNERSHIP_ARCHITECTURE.md)
- [RACE_CLASSIFICATION.md](RACE_CLASSIFICATION.md)
- [README.md](README.md)
- [REGION_CHUNK_SCHEDULER_SPEC.md](REGION_CHUNK_SCHEDULER_SPEC.md)
- [REGION_OWNERSHIP_CROSS_REGION_SPEC.md](REGION_OWNERSHIP_CROSS_REGION_SPEC.md)
- [REGION_SCALING_TEST_SPEC.md](REGION_SCALING_TEST_SPEC.md)
- [SCHEDULED_TICK_COMPATIBILITY_CONTRACT.md](SCHEDULED_TICK_COMPATIBILITY_CONTRACT.md)
- [SCHEDULED_TICK_LOSSLESS_HANDOFF_SPEC.md](SCHEDULED_TICK_LOSSLESS_HANDOFF_SPEC.md)
- [STARTUP_OPTIMIZATION_SPEC.md](STARTUP_OPTIMIZATION_SPEC.md)
- [STRESS_TEST_CHURN.md](STRESS_TEST_CHURN.md)
- [SUPPORT.md](SUPPORT.md)
- [THREAD_SAFETY_PASSES.md](THREAD_SAFETY_PASSES.md)
- [VERSION_MIGRATION_1211.md](VERSION_MIGRATION_1211.md)

### assets

- [Forge_logo.svg](assets/Forge_logo.svg)

### compat

- [AE2-CANARY-READINESS-2026-10-23.md](compat/AE2-CANARY-READINESS-2026-10-23.md)
- [AE2-COMPAT-001.md](compat/AE2-COMPAT-001.md)
- [AE2-COMPAT-ALLOWLIST-001.md](compat/AE2-COMPAT-ALLOWLIST-001.md)
- [AE2-COMPAT-ALLOWLIST-002.md](compat/AE2-COMPAT-ALLOWLIST-002.md)
- [AE2-COMPAT-ARCHITECTURE.md](compat/AE2-COMPAT-ARCHITECTURE.md)
- [AE2-COMPAT-CALLBACK-UNDER-P-001.md](compat/AE2-COMPAT-CALLBACK-UNDER-P-001.md)
- [AE2-COMPAT-CLOSURE-001.md](compat/AE2-COMPAT-CLOSURE-001.md)
- [AE2-COMPAT-DESIGN-001.1.md](compat/AE2-COMPAT-DESIGN-001.1.md)
- [AE2-COMPAT-DESIGN-001.2.md](compat/AE2-COMPAT-DESIGN-001.2.md)
- [AE2-COMPAT-DESIGN-001.3.md](compat/AE2-COMPAT-DESIGN-001.3.md)
- [AE2-COMPAT-DESIGN-001.4.md](compat/AE2-COMPAT-DESIGN-001.4.md)
- [AE2-COMPAT-DESIGN-001.5.md](compat/AE2-COMPAT-DESIGN-001.5.md)
- [AE2-COMPAT-DESIGN-001.md](compat/AE2-COMPAT-DESIGN-001.md)
- [AE2-COMPAT-ENERGY-001-GLUE-RESULTS.md](compat/AE2-COMPAT-ENERGY-001-GLUE-RESULTS.md)
- [AE2-COMPAT-GATE-STATUS.md](compat/AE2-COMPAT-GATE-STATUS.md)
- [AE2-COMPAT-GRID-PHASE-001-RESULTS.md](compat/AE2-COMPAT-GRID-PHASE-001-RESULTS.md)
- [AE2-COMPAT-GRID-PHASE-001.1.md](compat/AE2-COMPAT-GRID-PHASE-001.1.md)
- [AE2-COMPAT-GRID-PHASE-001.2.md](compat/AE2-COMPAT-GRID-PHASE-001.2.md)
- [AE2-COMPAT-GRID-PHASE-001.md](compat/AE2-COMPAT-GRID-PHASE-001.md)
- [AE2-COMPAT-LEGACY-ORIENTATION-001.1.md](compat/AE2-COMPAT-LEGACY-ORIENTATION-001.1.md)
- [AE2-COMPAT-LEGACY-ORIENTATION-001.md](compat/AE2-COMPAT-LEGACY-ORIENTATION-001.md)
- [AE2-COMPAT-P2P-001-AUDIT.md](compat/AE2-COMPAT-P2P-001-AUDIT.md)
- [AE2-COMPAT-P2P-001-EVIDENCE.md](compat/AE2-COMPAT-P2P-001-EVIDENCE.md)
- [AE2-COMPAT-P2P-001.md](compat/AE2-COMPAT-P2P-001.md)
- [AE2-COMPAT-P2P-ACCESS-DEPTH-001-RESULTS.md](compat/AE2-COMPAT-P2P-ACCESS-DEPTH-001-RESULTS.md)
- [AE2-COMPAT-P2P-FORWARDING-FAIL-CLOSED-001-RESULTS.md](compat/AE2-COMPAT-P2P-FORWARDING-FAIL-CLOSED-001-RESULTS.md)
- [AE2-COMPAT-P2P-MAP-STATE-001-RESULTS.md](compat/AE2-COMPAT-P2P-MAP-STATE-001-RESULTS.md)
- [AE2-COMPAT-QUEUE-001-RESULTS.md](compat/AE2-COMPAT-QUEUE-001-RESULTS.md)
- [AE2-COMPAT-QUEUE-001.1.md](compat/AE2-COMPAT-QUEUE-001.1.md)
- [AE2-COMPAT-QUEUE-001.md](compat/AE2-COMPAT-QUEUE-001.md)
- [AE2-COMPAT-RC-PLAN.md](compat/AE2-COMPAT-RC-PLAN.md)
- [AE2-COMPAT-SERVICE-MUTATION-001.md](compat/AE2-COMPAT-SERVICE-MUTATION-001.md)
- [AE2-COMPAT-TOPOLOGY-CALLBACK-001.md](compat/AE2-COMPAT-TOPOLOGY-CALLBACK-001.md)
- [AE2-COMPATIBILITY-MATRIX.md](compat/AE2-COMPATIBILITY-MATRIX.md)
- [AE2-MACHINE-TICK-DOMAIN-001-audit.md](compat/AE2-MACHINE-TICK-DOMAIN-001-audit.md)
- [AE2-NODE-LISTENER-ENDTICK-001-audit.md](compat/AE2-NODE-LISTENER-ENDTICK-001-audit.md)
- [AE2-PIN-LEAK-001-D1-lock-proof.md](compat/AE2-PIN-LEAK-001-D1-lock-proof.md)
- [AE2-PIN-LEAK-001-D4a-audit.md](compat/AE2-PIN-LEAK-001-D4a-audit.md)
- [AE2-PIN-LEAK-001-audit.md](compat/AE2-PIN-LEAK-001-audit.md)
- [AE2-RELEASE-EQUIV-001.md](compat/AE2-RELEASE-EQUIV-001.md)
- [AIRIS-REPRO-2026-10-26.md](compat/AIRIS-REPRO-2026-10-26.md)
- [AUTO-PIN-2.md](compat/AUTO-PIN-2.md)
- [COMPAT-THREAD-IDENTITY-001.md](compat/COMPAT-THREAD-IDENTITY-001.md)
- [PIPEZ-COMPAT-001.md](compat/PIPEZ-COMPAT-001.md)
- [SOLARFLUX-COMPAT-001.md](compat/SOLARFLUX-COMPAT-001.md)

### contracts

- [API-1B-CLOSURE-lifecycle.md](contracts/API-1B-CLOSURE-lifecycle.md)
- [CORE-CMD-001-brigadier-fragility.md](contracts/CORE-CMD-001-brigadier-fragility.md)
- [CORE-ENTITY-OWNERLESS-001.md](contracts/CORE-ENTITY-OWNERLESS-001.md)
- [CORE-MAILBOX-MERGE-001-audit.md](contracts/CORE-MAILBOX-MERGE-001-audit.md)
- [CORE-MAILBOX-MERGE-001-fix-spec.md](contracts/CORE-MAILBOX-MERGE-001-fix-spec.md)
- [CORE-PARKED-REGION-001-audit.md](contracts/CORE-PARKED-REGION-001-audit.md)
- [CORE-PIN-DOMAIN-COMPLETENESS-001-audit.md](contracts/CORE-PIN-DOMAIN-COMPLETENESS-001-audit.md)
- [EntityOperationRouter.md](contracts/EntityOperationRouter.md)
- [InventorySetSlotPrimitive.md](contracts/InventorySetSlotPrimitive.md)
- [MODULE-2-EXTERNAL-LOADER-AUDIT.md](contracts/MODULE-2-EXTERNAL-LOADER-AUDIT.md)
- [MODULE-2-EXTERNAL-LOADER-SPEC.md](contracts/MODULE-2-EXTERNAL-LOADER-SPEC.md)
- [MODULE-2-G0-RESULTS.md](contracts/MODULE-2-G0-RESULTS.md)
- [MODULE-LIFECYCLE-001-audit.md](contracts/MODULE-LIFECYCLE-001-audit.md)
- [MODULE-LIFECYCLE-001-spec.md](contracts/MODULE-LIFECYCLE-001-spec.md)
- [Module1Foundation.md](contracts/Module1Foundation.md)
- [OPS-CORE-47.4.264-SMOKE.md](contracts/OPS-CORE-47.4.264-SMOKE.md)
- [OPS-INCIDENT-2026-10-05-ae2-energy-race.md](contracts/OPS-INCIDENT-2026-10-05-ae2-energy-race.md)
- [OPS-INCIDENT-2026-10-15-ae2-processqueue-npe-spin.md](contracts/OPS-INCIDENT-2026-10-15-ae2-processqueue-npe-spin.md)
- [PERF-1-tz.md](contracts/PERF-1-tz.md)
- [PERF-1A-W2-spec.md](contracts/PERF-1A-W2-spec.md)
- [PERF-1A-spec.md](contracts/PERF-1A-spec.md)
- [PERF-1B-FINDING-001-cross-region-backpressure-ceiling.md](contracts/PERF-1B-FINDING-001-cross-region-backpressure-ceiling.md)
- [PERF-1B-spec.md](contracts/PERF-1B-spec.md)
- [PERF-1B-tz.md](contracts/PERF-1B-tz.md)
- [PERF-FINDING-001-tickattribution-contention.md](contracts/PERF-FINDING-001-tickattribution-contention.md)
- [PERF-HOST-INTERFERENCE-POLICY.md](contracts/PERF-HOST-INTERFERENCE-POLICY.md)
- [PKG-002-distribution-model.md](contracts/PKG-002-distribution-model.md)
- [SOURCE-BUILD-REPRO-001.md](contracts/SOURCE-BUILD-REPRO-001.md)
- [SOURCE-PATCH-INTEGRITY-001-audit.md](contracts/SOURCE-PATCH-INTEGRITY-001-audit.md)
- [SOURCE-PATCH-INTEGRITY-001-spec.md](contracts/SOURCE-PATCH-INTEGRITY-001-spec.md)
- [STRESS-001-hot-path-logging.md](contracts/STRESS-001-hot-path-logging.md)
- [SetVelocityPrimitive.md](contracts/SetVelocityPrimitive.md)
- [api-1a7-findings.md](contracts/api-1a7-findings.md)
- [api-1a8-findings.md](contracts/api-1a8-findings.md)
- [api-1b7-findings.md](contracts/api-1b7-findings.md)

### gmeil

- [ARCHITECTURE.md](gmeil/ARCHITECTURE.md)
- [CERTIFICATION_RULES.md](gmeil/CERTIFICATION_RULES.md)
- [EXECUTION_ENTRYPOINTS.md](gmeil/EXECUTION_ENTRYPOINTS.md)
- [FAILURE_MODEL.md](gmeil/FAILURE_MODEL.md)
- [MAILBOX_CONTRACT.md](gmeil/MAILBOX_CONTRACT.md)
- [POLICY_MODEL.md](gmeil/POLICY_MODEL.md)
- [TEST_PLAN.md](gmeil/TEST_PLAN.md)
- [WAIT_MODEL.md](gmeil/WAIT_MODEL.md)

### history

- [ROADMAP-2026-06-14.md](history/ROADMAP-2026-06-14.md)

### ops

- [OPS-TRIAGE-2026-10-23-tick-skips-crashes-dumps.md](ops/OPS-TRIAGE-2026-10-23-tick-skips-crashes-dumps.md)

### performance

- [PERF-1A-R1-results.md](performance/PERF-1A-R1-results.md)
- [PERF-1A-W2-results.md](performance/PERF-1A-W2-results.md)
- [PERF-1A-results.md](performance/PERF-1A-results.md)
