#!/usr/bin/env python3
"""PERF-1B D1 deterministic accounting smoke (docs/contracts/PERF-1B-spec.md §6 rule 4).

Restores the PERF-1B snapshot, boots NestWorld with the D1 counters on, runs
`nestworld testmutate det d1accounting` <reps> times (one APPLIED and one STALE_INTENT_DROPPED
each, through the public setVelocity entry), then checks both the fixture's own verdicts and the
independent per-second stats file: accepted == sum of terminals, with applied and stale_dropped
both reached.

Usage: d1smoke.py <server> <snapshot.tar> <out-dir> [reps=3]
"""
import json, os, re, shutil, sys, tarfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "perf1"))
import perf1  # noqa: E402
from perf1 import strip, Rcon  # noqa: E402
from perf1b import api_stats, terminals  # noqa: E402


def main():
    server, snapshot, out = sys.argv[1], sys.argv[2], sys.argv[3]
    reps = int(sys.argv[4]) if len(sys.argv) > 4 else 3
    os.makedirs(out, exist_ok=True)
    perf1.check_config(server)
    shutil.rmtree(os.path.join(server, "world"), ignore_errors=True)
    with tarfile.open(snapshot) as t:
        t.extractall(server)
    stats = os.path.abspath(os.path.join(out, "d1-apistats.jsonl"))
    console = os.path.abspath(os.path.join(out, "d1-console.log"))
    if os.path.exists(stats):
        os.remove(stats)
    opts = ("-Dnestworld.apiStats=true -Dnestworld.apiStatsFile=" + stats
            + " -Dnestworld.ownershipAssertionsEnabled=true")
    pid = perf1.boot(server, console, java_opts=opts)
    rc = Rcon()
    results = []
    try:
        for i in range(reps):
            done_before = open(console, errors="replace").read().count("scenario=d1accounting FINISHED")
            print(strip(rc.cmd("nestworld testmutate det d1accounting")))
            for _ in range(120):
                time.sleep(1)
                text = open(console, errors="replace").read()
                if text.count("scenario=d1accounting FINISHED") > done_before:
                    break
            else:
                sys.exit("d1accounting did not finish")
            res = re.findall(r"\[API1A7-DET\] (RESULT=\S+.*|deltas .*|path deltas .*)", text)
            results.append(res[-3:])
            print(f"rep {i + 1}:", *res[-3:], sep="\n  ")
        time.sleep(2.5)                      # one more stats line after the last terminal
        s = api_stats(stats)
        oa = strip(rc.cmd("nestworld ownershipassertions"))
    finally:
        stopped = perf1.stop(pid, rc)
    closure = {"accepted": s.get("accepted"), "terminals": terminals(s), "applied": s.get("applied"),
               "stale_dropped": s.get("stale_dropped"),
               "unaccounted": s.get("accepted", 0) - terminals(s)}
    oa_ok = "violations=0 " in oa + " " and "DISABLED" not in oa
    passed = (all(r and r[-1].startswith("RESULT=PASS") for r in results) and closure["unaccounted"] == 0
              and closure["applied"] == reps and closure["stale_dropped"] == reps and oa_ok)
    report = {"reps": reps, "fixture_results": results, "stats_file_closure": closure,
              "ownership_assertions": oa, "stopped_cleanly": stopped,
              "verdict": "PASS" if passed else "FAIL"}
    json.dump(report, open(os.path.join(out, "d1-report.json"), "w"), indent=1)
    print(json.dumps(report, indent=1))
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
