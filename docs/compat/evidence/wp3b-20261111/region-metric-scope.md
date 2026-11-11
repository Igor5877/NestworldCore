# Region hypothesis — read-only live check

Live `execute in jamd:mining run nestworld status`: one active region, autoSplit=false. Overworld also one active region. This constrains parallelism of eligible region-owned work, but does not migrate pinned AE2 authority or END grid handlers from MAIN.

Important metric scope: `nestworld regionpool` always selects overworldRegion(sys), even when executed in another dimension. Its observed one worker / ~26ms BLOCK_ENTITY wait is NOT mining telemetry. Wait is latch wall time after the inline MAIN BE loop; it includes MAIN chunk polling and scheduling delays. Poll time is a subset, not additional. Averages are cumulative per nonempty phase round, not necessarily per server tick. Free-running workers do not join the latch.

Status 'Main thread avg since boot' is per-dimension phase wall time, including region waits; it is not MAIN CPU. It excludes ordinary AE2 LevelTick END handlers after dimension tick return. Do not compare it directly with neutral whole Server-thread CPU measurements or add it to AE2 census values.

Core source explicitly documents autoSplit=false as the emergency switch after prior live watchdog incidents. No live layout/config was changed. A controlled split comparison would require a separate copied-world measurement; current evidence does not establish its TPS benefit.
