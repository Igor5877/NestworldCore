# Live 2–5 s freezes on project-infinity — root cause: a mod's per-tick DEBUG logging (2026-11-09)

Not a Core defect. Status: FIX DEPLOYED (live restart 21:22 after the host power loss).

```
symptom      "Can't keep up" 2–5 s at 0 players, also with no tests on the host; no GC pause (gc.log max 200 ms at boot),
             no dimension tick spike, overworld phases normal
evidence     spark profiler --only-ticks-over 1000 (tick_length_threshold 1 000 ms confirmed in the profile metadata),
             https://spark.lucko.me/5YiuJzsUCv: 97.9 % of the >1 s ticks in
             tickPinnedEntitiesOnMain → botanicalextramachinery EntityManaSparkPattern.tick → log4j debug() →
             RollingRandomAccessFileAppender.flush → RandomAccessFile.writeBytes                       CONFIRMED
cause        EntityManaSparkPattern logs DEBUG every tick ("Spark NN removed from outgoingTransfers"): 199 775 of the
             last 200 000 debug.log lines, debug.log 92 MB; the mod is pinned → the write is on the server thread;
             a slow disk write (host disk 90 % full, test-server I/O) blocks the tick for seconds
fix          live JVM arg -Dlog4j2.configurationFile=/root/infinity-monitor/log4j2-nestworld.xml = Forge's fmlloader
             log4j2.xml + <Logger level="info" name="net.lmor.botanicalextramachinery"/>; gameplay unchanged
verified     after the 21:22 boot: property active (jcmd VM.system_properties), new debug.log has 0 lines from the mod
note         the earlier 21-min profile (https://spark.lucko.me/VB7hYHwxGf, all ticks, rcon dropped the args) is a
             general B0 baseline: AE2 onPostLevelTick 36 % of the server thread (IO-bus export via storage bus /
             ExternalStorageFacade ≈ 15 %), dimension tick 35 %
```
