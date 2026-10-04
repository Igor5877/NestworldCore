#!/usr/bin/env python3
"""PERF-1A official series driver (docs/contracts/PERF-1-tz.md). Wraps the FROZEN harness
tests/perf1/perf1.py without changing it: the frozen file runs and validates every measurement;
this script only adds what the TZ requires around it:

  - pre-run interference gate (TZ §4 "before and during"; "during" is the frozen check);
  - run order across N (TZ §3.1: 1..16, 16..1, 1..16, restricted to the available N);
  - per-run metadata.json (TZ §23) and a validation report;
  - durable raw evidence outside /tmp (TZ §9: raw artifacts are never deleted).

Usage: series.py <server> <snapshot.tar> <datapack> <evidence-root> <series-id>
                 --n 1,2 --bench-cpus 2,3 --housekeeping 1 [--reps 3]
Bench CPUs are given in core order; N uses the first N of them.
"""
import hashlib, json, os, subprocess, sys, time, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import perf1  # noqa: E402  (frozen harness; imported for read-only helpers)

REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
PRE_GATE_S, PRE_GATE_MAX = 20, 3.0


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def git(*a):
    return subprocess.run(["git", "-C", REPO, *a], capture_output=True, text=True).stdout.strip()


def core_provenance(server):
    """Manifest provenance of the installed Core (the universal jar the server runs)."""
    run_sh = open(os.path.join(server, "run.sh")).read()
    ver = run_sh.split("minecraftforge/forge/")[1].split("/unix_args")[0]
    d = os.path.join(server, "libraries/net/minecraftforge/forge", ver)
    uni = [f for f in os.listdir(d) if f.endswith("-universal.jar")][0]
    srv = [f for f in os.listdir(d) if f.endswith("-server.jar")][0]
    mf = zipfile.ZipFile(os.path.join(d, uni)).read("META-INF/MANIFEST.MF").decode()
    keys = ("Git-Commit", "Git-Dirty", "Patch-Set-Digest", "Generated-Source-Digest",
            "Source-Patch-Integrity")
    out = {"core_version": ver, "server_jar_sha256": sha256(os.path.join(d, srv))}
    for line in mf.splitlines():
        k, _, v = line.partition(": ")
        if k in keys:
            out[k] = v.strip()
    return out


def busy_window(cpus, seconds):
    a = perf1.host_cpu_busy()
    time.sleep(seconds)
    b = perf1.host_cpu_busy()
    return {c: 100 * (1 - (b[c][1] - a[c][1]) / max(1, b[c][0] - a[c][0])) for c in cpus}


def pre_gate(bench, housekeeping):
    """Benchmark CPUs and the SMT siblings of benchmark + housekeeping CPUs idle before boot."""
    watch = sorted({s for c in bench + housekeeping for s in perf1.siblings(c)} - set(housekeeping))
    busy = busy_window(watch, PRE_GATE_S)
    return all(v <= PRE_GATE_MAX for v in busy.values()), busy


def order(ns, reps):
    seq, up = [], sorted(ns)
    for r in range(reps):
        seq += [(n, r + 1) for n in (up if r % 2 == 0 else up[::-1])]
    return seq


def main():
    a = sys.argv[1:]
    opts = {"--n": None, "--bench-cpus": None, "--housekeeping": None, "--reps": "3"}
    pos, it = [], iter(a)
    for x in it:
        if x in opts:
            opts[x] = next(it)
        else:
            pos.append(x)
    if len(pos) != 5 or None in opts.values():
        sys.exit(__doc__)
    server, snapshot, dp, root, sid = pos
    ns = [int(x) for x in opts["--n"].split(",")]
    bench = [int(x) for x in opts["--bench-cpus"].split(",")]
    hk = perf1.expand(opts["--housekeeping"])
    if max(ns) > len(bench):
        sys.exit("more N than bench CPUs")
    if git("status", "--porcelain", "--", "tests/perf1"):
        sys.exit("tests/perf1 has uncommitted changes: the series must run a committed harness")
    os.environ["PERF1_HARNESS_CPUS"] = opts["--housekeeping"]
    os.sched_setaffinity(0, hk)                  # the series driver itself stays on housekeeping
    out = os.path.join(root, sid)
    os.makedirs(os.path.join(out, "inputs"), exist_ok=True)
    import shutil                                # inputs kept with the evidence (TZ §9)
    if not os.path.exists(os.path.join(out, "inputs", os.path.basename(snapshot))):
        shutil.copy2(snapshot, os.path.join(out, "inputs"))
        shutil.copytree(dp, os.path.join(out, "inputs", "datapack"), dirs_exist_ok=True)
        for f in ("user_jvm_args.txt", "server.properties"):
            shutil.copy2(os.path.join(server, f), os.path.join(out, "inputs"))
    common = {
        "series": sid,
        "harness_commit": git("rev-parse", "HEAD"),
        "harness_perf1_py_sha256": sha256(os.path.join(HERE, "..", "perf1.py")),
        "counter_mod_sha256": sha256(os.path.join(server, "mods", "perf1-counter.jar")),
        "java_version": subprocess.run(["java", "-version"], capture_output=True,
                                       text=True).stderr.strip().splitlines()[0],
        "server_config_sha256": {f: sha256(os.path.join(server, f))
                                 for f in ("user_jvm_args.txt", "server.properties")},
        "snapshot_sha256": sha256(snapshot),
        "datapack_cells_sha256": sha256(os.path.join(dp, "cells.json")),
        "housekeeping_cpus": hk, "bench_cpu_order": bench,
        **core_provenance(server),
    }
    json.dump(common, open(os.path.join(out, "series.json"), "w"), indent=1)
    for n, rep in order(ns, int(opts["--reps"])):
        tag = f"N{n}-r{rep}"
        cpus = ",".join(map(str, bench[:n]))
        meta = dict(common, tag=tag, N=n, cpus=cpus,
                    smt_siblings={c: perf1.siblings(c) for c in bench[:n]})
        for attempt in range(1, 31):              # wait for a quiet host, never measure noise
            ok, busy = pre_gate(bench[:n], hk)
            if ok:
                break
            time.sleep(60)
        meta["pre_gate"] = {"pass": ok, "busy_pct": busy, "attempts": attempt}
        meta["start"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        if ok:
            rc = subprocess.run([sys.executable, os.path.join(HERE, "..", "perf1.py"), "run",
                                 server, snapshot, dp, str(n), cpus, tag, out]).returncode
            meta["harness_exit"] = rc
            v = subprocess.run([sys.executable, os.path.join(HERE, "..", "perf1.py"), "validate",
                                os.path.join(out, f"{tag}.json")], capture_output=True, text=True)
            meta["validation"] = v.stdout
            line = v.stdout.splitlines()[0] if v.stdout else "no result"
            meta["verdict"] = "VALID" if line.endswith(": VALID") else "VOID"
            meta["reason"] = line.split("VOID: ", 1)[1] if "VOID: " in line else \
                (None if meta["verdict"] == "VALID" else line)
        else:
            meta["verdict"], meta["reason"] = "VOID", "pre-run interference gate failed for 30 min"
        meta["end"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        json.dump(meta, open(os.path.join(out, f"{tag}-metadata.json"), "w"), indent=1)
        print(f"{tag}: {meta['verdict']} {meta.get('reason') or ''}", flush=True)
    runs = sorted(os.path.join(out, f) for f in os.listdir(out)
                  if f.startswith("N") and f.endswith(".json") and "metadata" not in f)
    rep = subprocess.run([sys.executable, os.path.join(HERE, "..", "perf1.py"), "validate", *runs],
                         capture_output=True, text=True).stdout
    open(os.path.join(out, "validation-report.txt"), "w").write(rep)
    print(rep)


if __name__ == "__main__":
    main()
