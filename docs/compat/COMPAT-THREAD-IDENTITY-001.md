# COMPAT-THREAD-IDENTITY-001 — NestWorld region threads pass weak "server thread" checks

**Status: OPEN finding (2026-10-14).** Found in MOD-COMPAT-1 Audit-1 (AE2-COMPAT-001, finding 4).

## Finding
- **Thread group.** `net.nestworld.region.RegionThread` is constructed by the server thread with
  `super(name)`, so it inherits the server thread's `ThreadGroup`, Forge's
  `SidedThreadGroups.SERVER`.
- **AE2 cannot tell.** AE2's `Platform.assertServerThread()` compares
  `Thread.currentThread().getThreadGroup()` with `SidedThreadGroups.SERVER`, so it **passes on
  NestWorld region threads**.
- **What that hides.** AE2 guards its grid registry (`TickHandler.addNetwork` / `removeNetwork`)
  this way. AE2's own thread-affinity check therefore cannot reveal that grid topology is being
  changed off the canonical server thread.
- **Who is affected.** Any mod that defines "server thread" by thread group, or by a similarly
  weak predicate such as a name prefix, treats region threads as the main server thread. Its own
  thread-affinity assertions then stay silent.

## Scope seen so far
Classes in the SkyBlock island pack that reference `SidedThreadGroups` (bytecode scan, 2026-10-14):

| Jar | References |
|---|---|
| appliedenergistics2-forge-15.4.10 | 1 (`Platform.assertServerThread`, CONFIRMED weak check) |
| sophisticatedcore-1.20.1-1.3.40.1925 | 5 |
| sophisticatedbackpacks-1.20.1-3.24.46.1803 | 4 |
| sophisticatedstorage-1.20.1-1.4.51.1769 | 4 |
| HammerLib-1.20.1-20.1.50 | 2 |
| FTB_PlaceHolders-1.0.6a, Glodium-1.20-1.5, MysticalExpansion-1.20.1-1.0.0, visual_recipe_editor-1.3.0 | 1 each |

- **Most uses are probably a side check** (client or server), which is correct for region threads.
- **Only checks that mean "the one main server thread" are affected.** Each reference needs a
  look before it is called a problem; these are not yet classified.
- **Minecraft's own affinity checks are correct.** `BlockableEventLoop.isSameThread` compares
  thread identity, so they are not affected.

## Not decided
Whether NestWorld should give region threads their own `ThreadGroup`:
- **For:** this would make weak checks fail loudly instead of passing silently.
- **Against:** it could break mods that use the group only to tell server from client, and would
  then misclassify region threads as not-server.

That needs the classification above first. No change in this gate.
