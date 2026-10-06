#!/usr/bin/env python3
"""PERF-1B harness driver (docs/contracts/PERF-1B-spec.md, PERF-1B TZ). Measures; changes nothing.

Reuses the verified helpers of tests/perf1/perf1.py (boot, RCON, CPU and affinity accounting,
host-interference telemetry, JIT pinning, marker-steered region layout) without modifying that
frozen file.

  perf1b.py run <server> <snapshot.tar> <datapack> <platform nestworld|forge> <mode API|RAW>
                <target self|partner> <N> <cpus> <tag> <out-dir>
                [--k 64] [--warmup-ops 20000000] [--warmup 120] [--window 300] [--api-stats 1]
  perf1b.py validate <run.json>...

B0 = forge RAW self, B1 = forge API self, B2 = nestworld API self, B3 = nestworld API partner.
--api-stats 0 runs without the D1 counters (Step 7 OFF/ON sanity): throughput then comes from the
consumer's submitted count, accounting closure and owner-loss terminals are not measurable, and
latency is not available. Only meaningful for B2, where every call applies synchronously.
Interference policy: v2-shared (docs/contracts/PERF-HOST-INTERFERENCE-POLICY.md).
"""
import json, os, re, subprocess, sys, tarfile, time, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "perf1"))
import perf1  # noqa: E402  (frozen PERF-1A harness; helpers only)
from perf1 import strip, Rcon  # noqa: E402

MODE_NAME = {("forge", "RAW", "self"): "B0", ("forge", "API", "self"): "B1",
             ("nestworld", "API", "self"): "B2", ("nestworld", "API", "partner"): "B3"}


def last_line(path):
    try:
        with open(path) as f:
            lines = f.read().strip().splitlines()
        return lines[-1] if lines else None
    except OSError:
        return None


def consumer_submitted(path):
    ln = last_line(path)
    return (int(ln.split(",")[0]), int(ln.split(",")[1])) if ln else (0, 0)


def api_stats(path):
    ln = last_line(path)
    return json.loads(ln) if ln else {}


TERMINALS = ("applied", "stale_dropped", "target_removed", "target_not_found",
             "reroute_limit_dropped", "redelivery_limit_dropped", "no_server", "other_terminal")


def terminals(s):
    return sum(s.get(k, 0) for k in TERMINALS)


def hist_percentiles(h, qs=(0.5, 0.95, 0.99)):
    """log2(ns) bucket counts -> upper-bound percentiles in microseconds."""
    total = sum(h)
    out = {}
    for q in qs:
        if total == 0:
            out[q] = None
            continue
        need, acc = q * total, 0
        for i, c in enumerate(h):
            acc += c
            if acc >= need:
                out[q] = (2 ** (i + 1)) / 1000.0
                break
    return out


def run(a, o):
    server, snapshot, dp, platform, mode, target, n, cpus, tag, out_dir = a
    n = int(n)
    os.makedirs(out_dir, exist_ok=True)
    if platform == "nestworld":
        perf1.check_config(server)
    cpus = perf1.pick_cpus(cpus)
    res = {"tag": tag, "mode": MODE_NAME.get((platform, mode, target), "UNDEFINED"),
           "platform": platform, "consumer_mode": mode, "target": target, "N": n, "cpus": cpus,
           "k": o["--k"], "warmup_ops": o["--warmup-ops"], "window_s": o["--window"],
           "harness_cpus": perf1.harness_cpus(),
           "interference_policy": "v2-shared",
           "snapshot_sha256": subprocess.run(["sha256sum", snapshot], capture_output=True,
                                             text=True).stdout.split()[0]}
    if res["mode"] == "UNDEFINED":
        sys.exit("undefined mode combination")
    with open(os.path.join(server, "user_jvm_args.txt")) as f:
        res["jvm_args"] = [l.strip() for l in f if l.strip() and not l.startswith("#")]
    cells = json.load(open(os.path.join(dp, "cells.json")))
    shutil.rmtree(os.path.join(server, "world"), ignore_errors=True)
    with tarfile.open(snapshot) as t:
        t.extractall(server)
    dst = os.path.join(server, "world", "datapacks", "perf1")
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(dp, dst)

    stats_c = os.path.abspath(os.path.join(out_dir, f"{tag}-consumer.csv"))
    stats_a = os.path.abspath(os.path.join(out_dir, f"{tag}-apistats.jsonl"))
    gclog = os.path.abspath(os.path.join(out_dir, f"{tag}-gc.log"))
    for p in (stats_c, stats_a):
        if os.path.exists(p):
            os.remove(p)
    opts = (f"-Xlog:gc*:file={gclog}:uptime,time -Dperf1b.mode={mode} -Dperf1b.k={o['--k']} "
            f"-Dperf1b.statsFile={stats_c} "
            + (f"-Dnestworld.apiStats=true -Dnestworld.apiStatsFile={stats_a} " if o["--api-stats"] else "")
            + "-Dnestworld.ownershipAssertionsEnabled=true")   # wrong-owner gate (diagnostic mode)
    res["api_stats"] = bool(o["--api-stats"])
    res["run_java_opts"] = opts
    pid = perf1.boot(server, os.path.join(out_dir, f"{tag}-console.log"), cpus, java_opts=opts)
    res["server"] = os.path.realpath(server)
    res["pid"], res["cwd"], res["affinity"] = pid, os.path.realpath(f"/proc/{pid}/cwd"), perf1.affinity(pid)
    res["jit_threads_pinned"] = len(perf1.pin_jit(pid, perf1.harness_cpus())) if perf1.harness_cpus() else 0
    rc = Rcon()
    rc.cmd("reload")
    time.sleep(3)
    if platform == "nestworld":
        log = []
        res["layout_splits"] = perf1.build_layout(rc, cells, log)   # also runs perf1:cows
        res["layout_log"] = log
        res["layout_start"], _ = perf1.layout(rc)
    else:
        rc.cmd("function perf1:setup")
        time.sleep(5)
        rc.cmd("function perf1:cows")
        want = sum(perf1.expected_cows(cells))
        for _ in range(120):
            if perf1.entity_count(rc, "@e[tag=perf1]") == want:
                break
            time.sleep(1)
    res["targets"] = perf1.entity_count(rc, "@e[tag=perf1]")
    res["bind"] = strip(rc.cmd(f"perf1b bind {target}"))
    rc.cmd("perf1b resume")

    # warmup: a fixed amount of submitted work, then 60 s of JIT quiet (PERF-1A discipline)
    t_w = time.time()
    hist = [(t_w, perf1.jit_cpu(pid))]
    while True:
        time.sleep(5)
        now = time.time()
        hist.append((now, perf1.jit_cpu(pid)))
        old = [h for h in hist if h[0] <= now - 60]
        done = consumer_submitted(stats_c)[1] >= o["--warmup-ops"]
        if now - t_w >= o["--warmup"] and done and old and hist[-1][1] - old[-1][1] <= 0.5:
            res["warmup_actual_s"] = now - t_w
            break
        if now - t_w > 3600:
            res["warmup_actual_s"] = -(now - t_w)
            break

    # measurement window
    jfr = os.path.abspath(os.path.join(out_dir, f"{tag}.jfr"))
    t0 = time.time()
    g0 = perf1.gametime(rc)
    c0, s0 = consumer_submitted(stats_c), api_stats(stats_a)
    cpu0, proc0, host0 = perf1.thread_cpu(pid), perf1.process_cpu(pid), perf1.host_cpu_busy()
    subprocess.run(["jcmd", str(pid), "JFR.start", "name=perf1b", f"filename={jfr}", "settings=default"],
                   capture_output=True)
    bench = perf1.expand(cpus)
    sib = sorted({x for c in bench for x in perf1.siblings(c)} - set(bench))
    tele = [(time.time(), host0, proc0, perf1.jit_cpu(pid))]
    seen_aff, blocks, g_last = {perf1.affinity(pid)}, [], g0
    seen_jit = set(perf1.jit_affinities(pid))
    fair = [perf1.fairness(rc)] if platform == "nestworld" else []
    while time.time() - t0 < o["--window"]:
        time.sleep(1)
        seen_aff.add(perf1.affinity(pid))
        seen_jit.update(perf1.jit_affinities(pid))
        tele.append((time.time(), perf1.host_cpu_busy(), perf1.process_cpu(pid), perf1.jit_cpu(pid)))
        g = perf1.gametime(rc)
        if g - g_last >= 100:
            blocks.append(perf1.mspt(rc))
            g_last = perf1.gametime(rc)
        if platform == "nestworld" and len(fair) <= int((time.time() - t0) // 30):
            fair.append(perf1.fairness(rc))   # ownership gate: owned = ticked = targets per cell
    g1 = perf1.gametime(rc)
    t1 = time.time()
    c1, s1 = consumer_submitted(stats_c), api_stats(stats_a)
    cpu1, proc1, host1 = perf1.thread_cpu(pid), perf1.process_cpu(pid), perf1.host_cpu_busy()
    subprocess.run(["jcmd", str(pid), "JFR.stop", "name=perf1b"], capture_output=True)
    if platform == "nestworld":
        res["layout_end"], _ = perf1.layout(rc)
        fair.append(perf1.fairness(rc))
        res["fairness_samples"] = fair
    res["targets_end"] = perf1.entity_count(rc, "@e[tag=perf1]")
    res["targets_expected"] = sum(perf1.expected_cows(cells))
    res["targets_per_cell"] = cells["cows_per_cell"]

    # quiescence: stop producers, wait until every accepted operation has a terminal
    rc.cmd("perf1b pause")
    q = {}
    for _ in range(60):
        time.sleep(1.5)
        q = api_stats(stats_a)
        if mode == "RAW" or not o["--api-stats"] or (q and q.get("accepted", 0) == terminals(q)):
            break
    res["quiescent_stats"] = q
    res["check"] = strip(rc.cmd("perf1b check"))
    if platform == "nestworld":
        res["ownership_assertions"] = strip(rc.cmd("nestworld ownershipassertions"))

    dt = t1 - t0
    res.update({"window_wall_s": dt, "tps": (g1 - g0) / dt, "mspt_blocks": blocks,
                "affinity_seen": sorted(seen_aff), "jit_affinity_seen": sorted(seen_jit),
                "window_stats_start": s0, "window_stats_end": s1})
    res["submitted_per_s"] = (c1[1] - c0[1]) / ((c1[0] - c0[0]) / 1000.0) if c1[0] > c0[0] else 0.0
    if mode == "RAW" or not o["--api-stats"]:   # B0 / counters off: throughput = submitted
        res["accepted_per_s"] = res["terminal_per_s"] = res["submitted_per_s"]
    else:
        span = (s1.get("ts", 0) - s0.get("ts", 0)) / 1000.0
        res["accepted_per_s"] = (s1.get("accepted", 0) - s0.get("accepted", 0)) / span if span > 0 else 0.0
        res["terminal_per_s"] = (terminals(s1) - terminals(s0)) / span if span > 0 else 0.0
        paths = {k: s1.get(k, 0) - s0.get(k, 0) for k in s1 if k.startswith("path_") or k in ("reroutes", "redeliveries")}
        res["window_paths"] = paths
        hist = [x - y for x, y in zip(s1.get("latency_log2ns", []), s0.get("latency_log2ns", []))]
        res["latency_us"] = {str(k): v for k, v in hist_percentiles(hist).items()}
        res["latency_samples"] = sum(hist)
    res["cpu_s"] = {k: cpu1.get(k, 0) - cpu0.get(k, 0) for k in cpu1}
    res["process_cpu_s"] = proc1 - proc0
    res["bench_cpu_s"] = res["process_cpu_s"] - res["cpu_s"].get("jit", 0.0)
    res["jit_cpu_s_in_window"] = res["cpu_s"].get("jit", 0.0)
    res["cpu_s_per_mop"] = (res["bench_cpu_s"] / (res["terminal_per_s"] * dt / 1e6)
                            if res["terminal_per_s"] > 0 else None)

    def busy_s(c):
        return ((host1[c][0] - host0[c][0]) - (host1[c][1] - host0[c][1])) / perf1.CLK
    res["sibling_busy_pct"] = {str(c): 100 * busy_s(c) / dt for c in sib}
    res["foreign_on_bench_pct"] = 100 * (sum(busy_s(c) for c in bench) - res["bench_cpu_s"]) / (dt * len(bench))
    res.update(perf1.burst_telemetry(tele, bench, sib))
    res["stopped_cleanly"] = perf1.stop(pid, rc)
    res.update(perf1.jfr_summary(jfr))
    if res.get("alloc_sampled_bytes") and res["terminal_per_s"]:
        res["alloc_bytes_per_op"] = res["alloc_sampled_bytes"] / (res["terminal_per_s"] * dt)
    with open(os.path.join(out_dir, f"{tag}.json"), "w") as f:
        json.dump(res, f, indent=1)
    print(json.dumps({k: res.get(k) for k in ("tag", "mode", "N", "tps", "submitted_per_s",
                                              "accepted_per_s", "terminal_per_s", "latency_us",
                                              "cpu_s_per_mop", "check")}))


def check_counts(text):
    m = re.search(r"match=(\d+) mismatch=(\d+) removed=(\d+)", text or "")
    return tuple(map(int, m.groups())) if m else (None, None, None)


def validate(paths):
    rc = 0
    for p in paths:
        r = json.load(open(p))
        match, mismatch, removed = check_counts(r.get("check"))
        q = r.get("quiescent_stats") or {}
        wp = r.get("window_paths") or {}
        total_paths = sum(v for k, v in wp.items() if k.startswith("path_")) or 1
        checks = {
            "process cwd is the server": r["cwd"] == r.get("server"),
            "affinity stable in window": [perf1.expand(x) for x in r["affinity_seen"]] == [perf1.expand(r["cpus"])],
            "JIT threads off the benchmark CPUs": r.get("jit_threads_pinned", 0) > 0 and
                all(set(perf1.expand(x)) == set(r["harness_cpus"]) for x in r.get("jit_affinity_seen", ["x"])),
            "warmup converged": r.get("warmup_actual_s", -1) > 0,
            "LATEST_INTENT_WINS final state (mismatch = 0, removed = 0)": mismatch == 0 and removed == 0,
            "accounting closed (accepted = sum of terminals)":
                r["consumer_mode"] == "RAW" or not r.get("api_stats", True)
                or (q and q.get("accepted", -1) == terminals(q)),
            "stopped cleanly": r.get("stopped_cleanly", False),
            "not capped by 20 TPS": r["tps"] < 19.0,
        }
        # Ownership validity gate (owner, 2026-10-06; CORE-MAILBOX-FR-FLAKE-001): every run must
        # prove in its own window that no target lost its authoritative owner. A failure makes the
        # run INVALID -- it is never just another terminal outcome.
        own = {}
        if r["platform"] == "nestworld":
            exp = r.get("targets_expected")
            per_cell = r.get("targets_per_cell")
            own["layout unchanged in window (topology matches the scenario)"] = \
                r.get("layout_start") == r.get("layout_end") and len(r.get("layout_start") or []) == 16
            oa = r.get("ownership_assertions", "")
            own["ownership assertions enabled, wrong-owner = 0"] = \
                "violations=0 " in oa + " " and "DISABLED" not in oa
            own["targets expected = present at start = at end"] = \
                exp is not None and r.get("targets") == exp == r.get("targets_end")
            own["targets expected = owned = ticked in every sample, one region per cell"] = \
                bool(r.get("fairness_samples")) and all(
                    sorted(o_ for _, o_, _, _ in smp if o_) == [per_cell] * 16
                    and all(t == o_ for _, o_, t, _ in smp)
                    for smp in r["fairness_samples"])
            if r.get("api_stats", True):
                own["no owner-loss terminals (target_not_found = target_removed = 0)"] = \
                    q.get("target_not_found", 0) == 0 and q.get("target_removed", 0) == 0
            # counters off (Step 7 OFF runs): terminals are not observable; the owned = ticked
            # samples and the final-state check above still cover ownership loss
        invalid = [k for k, v in own.items() if not v]
        bad = [k for k, v in checks.items() if not v]
        # path purity is a WORKLOAD-validity criterion, not an API correctness invariant (owner,
        # 2026-10-05): a B3 run at 98.7% mailbox due to real race / topology behaviour is
        # INVESTIGATE for path purity, not a correctness failure. Correctness stays the checks
        # above (accounting, owner, final state / ordering).
        purity = None
        if not r.get("api_stats", True):
            pass                              # no path counters with the counters off
        elif r["mode"] == "B2":
            purity = wp.get("path_owner_fast", 0) / total_paths
        elif r["mode"] == "B3":
            purity = wp.get("path_mailbox_cross", 0) / total_paths
        if purity is not None and purity < 0.99 and not bad:
            bad.append(f"INVESTIGATE path purity ({purity * 100:.2f}% on the intended path, workload validity)")
        tier, note = perf1.interference_tier(r)
        review = perf1.load_review(p).get(r["tag"])
        if review:
            note += f" | review: {review['verdict']} ({review['reason']})"
            if review["verdict"] == "VOID":
                bad.append(f"reviewed VOID: {review['reason']}")
        elif tier == "investigate" and not bad:
            bad.append("PENDING REVIEW (interference investigate tier)")
        rc |= bool(bad) or bool(invalid)
        verdict = ("INVALID (ownership gate): " + "; ".join(invalid + bad) if invalid
                   else "VOID: " + "; ".join(bad) if bad else "VALID")
        print(f"{r['tag']} {r['mode']} N={r['N']}: {verdict}{note}")
    return rc


def main():
    a = sys.argv[1:]
    if a and a[0] == "validate":
        sys.exit(validate(a[1:]))
    if not a or a[0] != "run":
        sys.exit(__doc__)
    o = {"--k": 64, "--warmup-ops": 20_000_000, "--warmup": 120, "--window": 300, "--api-stats": 1}
    pos, it = [], iter(a[1:])
    for x in it:
        if x in o:
            o[x] = int(next(it))
        else:
            pos.append(x)
    if len(pos) != 10:
        sys.exit(__doc__)
    if perf1.harness_cpus():
        os.sched_setaffinity(0, perf1.harness_cpus())
    run(pos, o)


if __name__ == "__main__":
    main()
