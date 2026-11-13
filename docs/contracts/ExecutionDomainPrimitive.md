# ExecutionDomain primitive — `net.nestworld.modapi.domain` (DRAFT, 2026-11-12)

Status: DESIGN (owner 2026-11-12: "може під це розбити mod api визов?"). Internal-first: versioned interface in
`projects/modapi`, consumed by NestWorld addons (first: `nestworld-compat-ae2` grid worker, AE2-GRID-DOMAIN-001 model B).
Frozen as public Mod API only after the AE2 rig gate passes (MOD-API-BE-GROUP-001 merges into this).

## Purpose
Let a mod run a bounded piece of **its own** server-tick work on a dedicated NestWorld thread, in parallel with MAIN,
without the mod touching thread identity, vanilla thread checks or Core synchronisation. All dangerous mechanics live
in Core once.

## Facade
```java
DomainHandle h = NestWorldDomains.open("ae2");      // once per server; Forge peer ⇒ INLINE handle
boolean queued = h.submit(touchedLevels, job);       // MAIN only; false ⇒ caller runs job inline NOW
h.awaitAll();                                        // MAIN only; returns when every submitted job finished
h.isDomainThread();                                  // any thread
h.stats();                                           // telemetry string
```
- `touchedLevels`: every level whose block entities, chunks or block states the job may read or write.
- `job`: runs exactly once, on a domain thread, in submission order (FIFO per handle). Exceptions are caught, logged,
  counted; the next job still runs.

## Core guarantees (NestWorld peer)
1. **Exclusion.** While a job that touches level L is queued or running, MAIN does not start L's block-entity phase,
   L's chunk-task processing, world save, or server stop — Core awaits the job first. MAIN keeps running everything
   else (other levels, entities, players, network).
2. **Acting-main lease.** During the job the domain thread passes Core's main-thread checks for exactly the touched
   levels: `Level.getBlockEntity` (live object, never a ghost copy), `ServerChunkCache.getChunkNow`, loaded
   `getChunk`. An unloaded chunk ⇒ empty chunk, never a blocking hop to MAIN. `MinecraftServer.execute()` from the job
   still queues to MAIN (never runs inline).
3. **No new deadlock.** MAIN never holds a Core lock while awaiting; a job never waits for MAIN.
4. **Fallback.** No NestWorld Core, domains disabled (`-Dnestworld.domains=false`), worker failure, or a submit from a
   non-MAIN thread ⇒ `submit` returns false and the caller runs inline — identical to today's behaviour.

## Not provided (the caller's responsibility)
Mutual exclusion of the mod's OWN shared state against other threads (AE2: P(G) on NetworkStorage ingress), choosing
what is safe to submit, ordering between jobs of different handles.

## Telemetry
Per handle: jobs, inline fallbacks, failures, job ms, MAIN await ms (total/max), queued depth; exposed via
`/nestworld domains` and the inspector.
