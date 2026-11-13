# LIVE AE2 follow-up: 2026-11-11, 18:31 Kyiv

Only live measurements are used here. No copied-world benchmark is evidence for this diagnosis.

Live addon SHA256: `1f6d4a57133604d4931a9a9250e8ed5ef31302b3773a4efb048114ec08dd5c86` (0.9.1). Candidate source is newer and is not installed on live.

## Fresh profile

Raw local recording `/tmp/ae2-followup-20261111.jfr`; execution samples span 16:31:00.676–16:32:00.541 UTC (~59.864s). MAIN 5860 execution samples, 2005 truncated (~34.2%). JFR contains earlier metadata/counter event timestamps, so the all-event minimum timestamp from AnalyzeJfr is NOT the recording interval.

Live Forge snapshots: overall 10.083 TPS / 99.179ms before; 10.032 TPS / 99.676ms after. These are rolling windows, not an exact profiler interval average.

- AE2 unsafeTickingRequest: 2426/5860 MAIN samples (~41.4%). This identifies CPU attribution, not a wall-time percentage or evidence that TickManager correctness is faulty.
- StorageExportStrategy.transfer: 991 (~16.9%). StorageImportStrategy.transfer: 751 (~12.8%). InterfaceLogic ticker: 449 (~7.7%). Inclusive counts overlap and truncated ancestors are undercounted.
- Sophisticated CachedFailedInsertInventoryHandler.getStackInSlot: 377; InventoryHandler.getStackInSlot:157. These demonstrate actual external-inventory reads on MAIN.
- GC: 5 events, 318.85ms aggregate pauses (~0.53% of 60s), max78.14ms. GC can cause individual spikes, but does not explain the sustained ~100ms ticks in this interval.
- Own elide wrapper leaf only4 samples in this profile; no basis for blaming that method from its name in an older profile. The default-disabled typed-dispatch optimization is not a supported remedy.

## Confirmed old export churn in LIVE jar

`javap -c -p` of the exact installed addon confirms onBusWork invokes prepareExport then exportRound before region insertion. exportRound assumes target acceptance; rejection later causes network return. This differs from reference StorageExportStrategy.transfer, which simulates target insert before MODULATE poweredExtraction. The newer source restores probe-before-extraction.

Counter snapshot interval ~74.98s (brackets but is wider than the JFR interval):

- export fromNetwork +18262; toNeighbour +336; backToNetwork +17926; held0; drop0.
- 98.16% of extracted units returned to AE2, only1.84% delivered.
- transitExport +212; transitImport +0; scheduled +6176; missed/failures/domain counters0.
- MAIN inline +4052921us; transit entry +78087us; commit +80953us; region +16901us. Counters are instrumentation scopes, not total MAIN CPU. Transit churn is real but this measured scoped overhead does not establish it as the dominant ~100ms tick cost.

Import offload is not exercised at all. Large strategy/main-neighbour rejection totals explain why merely enabling transit does not move the bulk of live bus work off MAIN. No pins were changed.

## Focused actual-addon code review

Exact live jars were inspected using javap, not guessed from source versions:

- AppliedEnh ItemHandlerFacade extractFromIndexedSlots: hasFastPath calls candidates(key), then wrapper calls candidates(key) again. A partial SIMULATE result falls back to original extractExternal with the full original amount, rescanning previously checked candidates. MODULATE fallback correctly uses the remainder. SlotIndex.invalidate sets current=false but candidates retains hints: dropping fallback is not justified.
- AppliedEnh recordListedSlot constructs AEItemKey for slot indexing; AE2 getAvailableStacks constructs another AEItemKey for KeyCounter. Capability serialization/hash/intern work can therefore repeat. A blanket cache would be unsafe because extractableOnly may call simulated extraction between constructions and item callbacks can be observable. This is a concrete optimization location, not yet a measured TPS cause.

## Source change in this follow-up

BusTransit.plan now skips its second MAIN poweredExtraction(SIMULATE) when probed remaining target capacity<=0. This branch previously could not move items, charge energy, consume operations or update planned amounts. Positive-capacity path and prepareExport availability selection remain unchanged. Build PASS (`reobfJarJar --offline`); no live runtime or performance acceptance claimed for this change.

Conclusion: confirmed sustained AE2 MAIN CPU work, confirmed ineffective import coverage and old live export extract/return churn. A single unsplit region is not proven to be the main cause by this profile. Deploying a candidate or changing region layout was not part of this measurement. No VERIFIED correctness areas reopened.
