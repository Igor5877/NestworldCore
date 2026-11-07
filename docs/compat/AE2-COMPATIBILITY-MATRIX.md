# AE2-COMPATIBILITY-MATRIX (owner pack project-infinity, 2026-11-07)

Statuses: SUPPORTED · ENGINEERING_VERIFIED · PRODUCTION_VERIFIED · PARTIAL · MAIN_ONLY · UNSUPPORTED · UNKNOWN.
Tier: 0 unknown · 1 API-only · 2 custom machines · 3 grid-internal · 4 unsupported. "Machine" = may its devices tick off
main; today every AE2-ecosystem device ticks on main (pins + grid ticks on main), so Machine = MAIN_ONLY everywhere.

| Mod | Version | SHA (prefix) | Tier | Gate (power / manifest) | Machine | Callback | Storage | Production |
|---|---|---|---|---|---|---|---|---|
| ae2 | 15.4.10 (base) / 15.4.11 (equiv.) | fbfee05c / f2ff9ee5 | — | audited base / RELEASE-EQUIV-001 | MAIN_ONLY | ENGINEERING_VERIFIED (scope 2/3, ENDTICK, D2, D4a) | MAIN_ONLY (unguarded) | PARTIAL (pinned, live) |
| expatternprovider (ExtendedAE) | 1.20-1.4.21-forge | 30fc8abe | 2 (UNKNOWN for internals) | ALLOWLIST-002 / manifest screen | MAIN_ONLY | inherits AE2 | MAIN_ONLY | PARTIAL |
| advanced_ae | 1.3.6-1.20.1 | e216f95a | 2–3 (own crafting CPU cluster: UNKNOWN) | ALLOWLIST-002 / manifest screen | MAIN_ONLY | UNKNOWN | MAIN_ONLY | PARTIAL |
| crazyae2addons | 2.6.2 | 6a0e023b | 3 (overwrites `CraftingService.insertIntoCpus`) | ALLOWLIST-002 / manifest screen | MAIN_ONLY | UNKNOWN | MAIN_ONLY | PARTIAL |
| neoecoae | 20.4.2 | e6ba909b | 3 (own storage system, crafting bridge) | ALLOWLIST-002 / manifest screen | MAIN_ONLY | UNKNOWN | MAIN_ONLY | PARTIAL |
| extendedae_plus | 1.6.3 | d8634db4 | 2 | manifest screen | MAIN_ONLY | UNKNOWN | MAIN_ONLY | UNKNOWN |
| appflux | 1.20-1.3.7-forge | 7673b0e4 | 3 (energy distribute service) | manifest screen | MAIN_ONLY | UNKNOWN | — | UNKNOWN |
| appliede | 0.14.3-fix | 36d6b413 | 3 (knowledge service, EMC storage) | manifest screen | MAIN_ONLY | UNKNOWN | MAIN_ONLY | UNKNOWN |
| mae2 | 2.1.0 | b931dcaa | 3 (multi-P2P service) | manifest screen | MAIN_ONLY | UNKNOWN (P2P-001 open) | — | UNKNOWN |
| molecularmanipulator (omnisequence-transfinite) | 2.0.9-forge | b3513a2a | 3 (grid machines mixin) | manifest screen | MAIN_ONLY | UNKNOWN | — | UNKNOWN |
| bmaddon | 1.0.4 | d84ad182 | 2 | manifest screen (depth 2) | MAIN_ONLY | UNKNOWN | — | UNKNOWN |
| packagedauto | 1.20.1-3.4.15.46 | e312eb48 | 2 | manifest screen | MAIN_ONLY | UNKNOWN | — | UNKNOWN |
| aeallpattern | 0.2.6-beta.1 | 250fb541 | 1 | manifest screen | MAIN_ONLY | UNKNOWN | — | UNKNOWN |
| appliedenhancements | 1.1.2-forge | — | 3 (mixins into strategies / NetworkStorage) | not gated (no closure class) | — | UNKNOWN | UNKNOWN | UNKNOWN |
| megacells | 2.4.6-1.20.1 | 19f59cc7 | 1–2 | manifest (Airis audit) | MAIN_ONLY | inherits AE2 | MAIN_ONLY | UNKNOWN |
| solarflux | 20.1.11 | b8460262 | 3 (cross-mod energy state) | ALLOWLIST-001 | MAIN_ONLY | SOLARFLUX-COMPAT-001 OPEN | — | PARTIAL |
