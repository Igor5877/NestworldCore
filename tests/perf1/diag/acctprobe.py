#!/usr/bin/env python3
"""Diagnostic (not PERF-1A evidence): duty-cycle load on one pinned CPU; compare the process's
precise runtime (/proc/self/schedstat, ns) and utime+stime with that CPU's busy time from
/proc/stat (lxcfs). Usage: acctprobe.py <cpu> <seconds-per-step> <period-ms> [duties...]"""
import os, sys, time
cpu, secs, period = int(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3]) / 1000
duties = [float(x) for x in sys.argv[4:]] or [1.0, 0.5, 0.25, 0.1]
os.sched_setaffinity(0, {cpu})
CLK = os.sysconf("SC_CLK_TCK")
def stat_cpu():
    for l in open("/proc/stat"):
        if l.startswith(f"cpu{cpu} "):
            v = list(map(int, l.split()[1:])); return sum(v), v[3] + v[4]
def sched(): return int(open("/proc/self/schedstat").read().split()[0]) / 1e9
def ticks(): t = os.times(); return t.user + t.system
print("CLK_TCK", CLK)
for d in duties:
    s0, sc0, tk0, w0 = stat_cpu(), sched(), ticks(), time.monotonic()
    end = w0 + secs
    while True:
        now = time.monotonic()
        if now >= end: break
        busy_until = now + period * d
        while time.monotonic() < busy_until: pass
        if d < 1.0: time.sleep(period * (1 - d))
    s1, sc1, tk1, w1 = stat_cpu(), sched(), ticks(), time.monotonic()
    wall = w1 - w0
    cpu_busy = ((s1[0] - s0[0]) - (s1[1] - s0[1])) / CLK
    print(f"duty={d:.2f} period={period*1000:.1f}ms wall={wall:.1f}s schedstat={sc1-sc0:.2f}s "
          f"utime+stime={tk1-tk0:.2f}s cpu{cpu}_busy(/proc/stat)={cpu_busy:.2f}s "
          f"derived_foreign={(cpu_busy-(sc1-sc0))/wall*100:+.1f}%")
