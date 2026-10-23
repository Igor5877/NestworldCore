#!/usr/bin/env python3
"""nestworld-compat-ae2 live-world canary — EVALUATE (owner-run or on the copied evidence files).

Usage: airis-canary-evaluate.py <server dir>
Reads <server dir>/canary-evidence/{t0-after-start,t30min,t2h}-*.txt (latest of each), logs/latest.log
and crash-reports/, applies the PASS criteria of the canary TZ (§15) and prints the report skeleton
of §19. It never changes anything. INCONCLUSIVE when a required snapshot is missing.
"""
import glob, os, re, sys

D = sys.argv[1] if len(sys.argv) > 1 else "."
EV = os.path.join(D, "canary-evidence")

def latest(label):
    f = sorted(glob.glob(os.path.join(EV, label + "-*.txt")))
    return f[-1] if f else None

def stats(path):
    if not path:
        return None
    txt = open(path, errors="replace").read()
    m = re.search(r"> nwcompatae2 stats\n(.*)", txt)
    if not m:
        return {}
    return dict(re.findall(r"(\w+)=([A-Za-z0-9_.-]+)", m.group(1)))

def core_line(path, key):
    if not path:
        return "?"
    m = re.search(key + r"[: ]+(\S+)", open(path, errors="replace").read())
    return m.group(1) if m else "?"

snaps = {k: latest(k) for k in ("baseline-pre", "t0-after-start", "t30min", "t2h")}
st = {k: stats(v) for k, v in snaps.items() if k != "baseline-pre"}

log = os.path.join(D, "logs", "latest.log")
logtxt = open(log, errors="replace").read() if os.path.exists(log) else ""
def count(rx):
    return len(re.findall(rx, logtxt))

ae2_frame = r"(appeng\.|nestworld\.compat\.ae2)"
signals = {
    "AE2 CME": count(r"ConcurrentModificationException(?:.*\n){0,12}?.*" + ae2_frame),
    "AE2/P2P NPE": count(r"NullPointerException(?:.*\n){0,12}?.*appeng\.parts\.p2p"),
    "EnergyService.addRequester ISE": count(r"Cannot modify energy requesters"),
    "processQueue loop": count(r"TickHandler\.processQueue"),
    "accessDepth ISE": count(r"Close has been called multiple times|get was called after closing the wrapper"),
    "watchdog / hang": count(r"ServerHangWatchdog|A single server tick took"),
    "deadlock": count(r"Found one Java-level deadlock"),
    "DEGRADED": count(r"DEGRADED"),
    "unaudited": count(r"unaudited"),
}
other = {
    "Draconic deSpawnedMobs (separate class)": count(r"DEEventHandler\.serverTick"),
    "Mekanism NetworkAcceptorCache (separate class)": count(r"NetworkAcceptorCache"),
}
startup_ok = bool(re.search(r"startup gate PASS.*manifest ELIGIBLE", logtxt))
contract = "mode=CONTRACT" in logtxt
# only crash reports written after the pre-start baseline belong to this canary run
CR = os.path.join(D, "crash-reports")
since = os.path.getmtime(snaps["baseline-pre"]) if snaps["baseline-pre"] else 0
crashes = sorted((f for f in (os.listdir(CR) if os.path.isdir(CR) else []) if os.path.getmtime(os.path.join(CR, f)) > since),
                 key=lambda f: os.path.getmtime(os.path.join(CR, f)))

ZERO = ["unauditedPaths", "callbackExceptions", "mainAffinityViolation",
        "p2pInputDisplaced", "p2pDepthUnderflow", "p2pDepthFalseReject"]
fails, missing = [], []
for k in ("t0-after-start", "t30min", "t2h"):
    s = st.get(k)
    if s is None:
        missing.append(k); continue
    if not s:
        fails.append(f"{k}: no nwcompatae2 stats in the snapshot (RCON?)"); continue
    if s.get("mode") != "NORMAL": fails.append(f"{k}: mode={s.get('mode')}")
    if s.get("manifestEligible") != "true": fails.append(f"{k}: manifestEligible={s.get('manifestEligible')}")
    for z in ZERO:
        if s.get(z, "0") != "0": fails.append(f"{k}: {z}={s.get(z)}")
if not startup_ok: fails.append("log: 'startup gate PASS ... manifest ELIGIBLE' not found")
for c in crashes:
    fails.append(f"stability: crash report during the canary: {c}")
for name, n in signals.items():
    if n: fails.append(f"log: {name} x{n}")

result = "FAIL" if fails else ("INCONCLUSIVE" if missing else "PASS")
last = st.get("t2h") or st.get("t30min") or st.get("t0-after-start") or {}
print(f"""CANARY RESULT: {result}
INSTANCE: Airis
DURATION: 30 min {'done' if st.get('t30min') is not None else 'MISSING'} / 2 h {'done' if st.get('t2h') is not None else 'MISSING'}
PLAYERS: 0 (by plan)
CORE: {core_line(snaps['t0-after-start'] or snaps['baseline-pre'], 'ACTIVE Core')}
ADDON: nestworld-compat-ae2-0.1.0-all.jar (expected SHA 71a5d781...b233be; see snapshot mod list)
MODE: {last.get('mode', '?')}
MANIFEST: {'ELIGIBLE' if last.get('manifestEligible') == 'true' else last.get('manifestEligible', '?')}
STARTUP GATE PASS + ELIGIBLE in log: {startup_ok}; P2P contract mode seen: {contract}
COUNTERS (last snapshot): """ + " ".join(f"{z}={last.get(z, '?')}" for z in ZERO) + f"""
P2P REFUSALS: foreign={last.get('p2pForwardForeignRejected', '?')} mainCaller={last.get('p2pForwardRejectedMainCaller', '?')} (informational)
ERRORS (criteria): {fails if fails else 'none'}
LOG SIGNALS: """ + ", ".join(f"{k}={v}" for k, v in signals.items()) + f"""
NEW INCIDENTS / SEPARATE CLASSES: """ + ", ".join(f"{k}={v}" for k, v in other.items()) + f"""
CRASH REPORTS (since baseline-pre): {crashes if crashes else 'none'}
MISSING SNAPSHOTS: {missing if missing else 'none'}
ROLLBACK REQUIRED: {'REVIEW (criteria failed)' if fails else 'NO'}

BACKGROUND STABILITY: {result}
PLAYER-DRIVEN PATHS: NOT EXERCISED
TRUE CROSS-REGION P2P: UNSUPPORTED (fail-closed)
UNPIN: BLOCKED
FLEET ROLLOUT: NOT APPROVED""")
