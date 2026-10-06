#!/usr/bin/env python3
"""Read-only: every 30 s, per JVM thread: name, Cpus_allowed_list, utime+stime. Output JSONL."""
import os, json, time, sys
SRV, OUT = sys.argv[1], sys.argv[2]
def find():
    for p in os.listdir("/proc"):
        if not p.isdigit(): continue
        try:
            if os.readlink(f"/proc/{p}/cwd") == SRV and "java" in os.readlink(f"/proc/{p}/exe"): return int(p)
        except OSError: pass
while True:
    pid = find()
    if pid:
        rows = []
        for t in os.listdir(f"/proc/{pid}/task"):
            try:
                st = open(f"/proc/{pid}/task/{t}/stat").read()
                name = st[st.index("(")+1:st.rindex(")")]; f = st[st.rindex(")")+2:].split()
                allowed = [l.split(":")[1].strip() for l in open(f"/proc/{pid}/task/{t}/status") if l.startswith("Cpus_allowed_list")][0]
                rows.append([int(t), name, allowed, int(f[11]) + int(f[12]), int(f[36])])
            except (OSError, ValueError, IndexError): pass
        with open(OUT, "a") as o: o.write(json.dumps({"t": time.time(), "pid": pid, "tasks": rows}) + "\n")
    time.sleep(30)
