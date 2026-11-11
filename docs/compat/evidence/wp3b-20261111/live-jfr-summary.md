# Live Java diagnosis — 2026-11-11

Recording: `/tmp/wp3b-live-tps-20261111.jfr`, 90 seconds, local time 13:40:23–13:41:53. Read using Java `jdk.jfr.consumer.RecordingFile`; no live mutations or additional recording by this agent.

## Findings

- MAIN: **10,111 ExecutionSample + 1 NativeMethodSample**. **3,475 traces truncated**; inclusive ancestors may therefore be undercounted. Sample shares are CPU-sample attribution, not direct wall-time percentages.
- AE2 tick-request path: **4,222 / 10,111** MAIN execution samples (~41.8%). External storage extraction: **2,490 / 10,111** (~24.6%).
- Strong concrete hot chain: `StorageExportStrategy.transfer → StorageHelper.poweredExtraction → NetworkStorage.extract → CompositeStorage / DelegatingMEInventory → ExternalStorageFacade.extractExternal → appliedenhancements indexed-extraction wrapper → nestworld$elideFirstRecheck`. This is **network-side external Storage Bus inventory work**, so moving only the export destination push does not remove this main bottleneck.
- Own `nestworld$elideFirstRecheck` is the top reported leaf: **1,267 samples (~12.5%)**. Its callers include 687 export-transfer samples, 376+109 import-network-insert samples, and 42 interface-plan samples. **This is not proof of a regression:** original/inlined work can be attributed to that wrapper; an isolated A/B would be needed.
- Other concrete MAIN stacks: Sophisticated `CachedFailedInsertInventoryHandler.insertItem` 210, `InventoryHandler.insertItem` 206, `onContentsChanged` 165, `saveInventory` 158. MM `FluidPortHandler.drain` 320 and `AbstractPortBlockEntity.markChangedWithoutSync` 248. AdvancedAE `ImportExportBusPart.doBusWork` 295. These counts overlap and must not be summed as independent time.

## GC, allocation and waiting

- **11 GC events; 438.11 ms total stop-the-world pause (~0.49% of the 90-second window); max 43.15 ms.** GC is not the principal explanation for sustained ~78 ms ticks.
- Actual `ThreadAllocationStatistics` counter deltas: MAIN **26,460,722,624 bytes / 89.510 s = 281.92 MiB/s**; mining region **71.02 MiB/s**; overworld region **32.11 MiB/s**; Spark sampler **27.17 MiB/s**. All 247 tracked threads: ~**420.2 MiB/s**. Raw `ObjectAllocationSample.weight` first samples include pre-recording history (MAIN first sample ~3.89 TB), so they are NOT used for allocation rate.
- Recorded long MAIN parks total **2,073.14 ms**, all `RegionThreadPool.awaitLatch`. **Short parks below JFR threshold are omitted; this is not total MAIN wait time.** Region CPU samples: mining 2,680; overworld 1,152; nether 265. A single region is not established as the primary cause merely from layout.

## Evidence artifacts

- `/tmp/wp3b-jfr-analysis.txt`: main inclusive/leaf counts, full representative stacks, wait and GC aggregation.
- `/tmp/wp3b-jfr-focused.txt`: exact target methods and full external-storage sample chain.
- `/tmp/wp3b-jfr-allocation-stats.txt`: min/max allocation counters and sampling intervals.
- Helpers: `/tmp/AnalyzeJfr.java`, `/tmp/JfrFocused.java`, `/tmp/JfrAllocStats.java`.
