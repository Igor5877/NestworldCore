#!/usr/bin/env python3
"""PERF-1A harness driver (docs/contracts/PERF-1A-spec.md). Measures; changes nothing in Core.

  perf1.py prepare <server> <datapack-dir> <snapshot.tar>
      boot once, build the W1 walls and forceloads (no entities), save, stop, archive.
  perf1.py run <server> <snapshot.tar> <datapack-dir> <N> <cpu-list> <tag> <out-dir>
               [--warmup S] [--window S] [--warmup-ticks T]
      restore the snapshot, boot pinned to <cpu-list>, build the region layout on markers,
      summon the cows into it, warm up, measure, stop; writes <out-dir>/<tag>.json plus gc/jfr/console files.
  perf1.py validate <run.json>...
      applies the spec's validity checks and prints one verdict per run.

The server's own process is identified by its cwd (never by command-line pattern) and every
run records that cwd.
"""
import json, os, re, shutil, socket, struct, subprocess, sys, tarfile, time

RCON_PORT, RCON_PASS = 25622, "perf1"
CLK = os.sysconf("SC_CLK_TCK")


# ---------------------------------------------------------------- rcon
class Rcon:
    def __init__(self, port=RCON_PORT, password=RCON_PASS):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=120)
        self._send(1, 3, password)
        self._recv()

    def _send(self, i, t, body):
        d = struct.pack("<ii", i, t) + body.encode() + b"\0\0"
        self.s.sendall(struct.pack("<i", len(d)) + d)

    def _recv(self):
        n = struct.unpack("<i", self._read(4))[0]
        d = self._read(n)
        return d[8:-2].decode(errors="replace")

    def _read(self, n):
        b = b""
        while len(b) < n:
            chunk = self.s.recv(n - len(b))
            if not chunk:
                raise ConnectionError("rcon closed")
            b += chunk
        return b

    def cmd(self, c):
        self._send(2, 2, c)
        return self._recv()


def strip(s):
    return re.sub(r"§.", "", s)


# ---------------------------------------------------------------- process
def server_pid(server):
    """The java process whose cwd is the server root (cmdline patterns are not trusted)."""
    real = os.path.realpath(server)
    for p in os.listdir("/proc"):
        if not p.isdigit():
            continue
        try:
            if os.path.realpath(f"/proc/{p}/cwd") == real and \
                    os.path.basename(os.readlink(f"/proc/{p}/exe")).startswith("java"):
                return int(p)
        except OSError:
            continue
    return None


def container_cpuset():
    try:
        rel = open("/proc/self/cgroup").read().strip().split(":", 2)[2]
        return open(f"/sys/fs/cgroup{rel}/cpuset.cpus.effective").read().strip()
    except OSError:
        return open("/proc/self/status").read().split("Cpus_allowed_list:")[1].split()[0]


def expand(cpulist):
    out = []
    for part in cpulist.split(","):
        a, _, b = part.partition("-")
        out += range(int(a), int(b or a) + 1)
    return out


def siblings(c):
    return expand(open(f"/sys/devices/system/cpu/cpu{c}/topology/thread_siblings_list").read().strip())


def harness_cpus():
    """CPUs reserved for the driver itself (env PERF1_HARNESS_CPUS), never given to the server."""
    v = os.environ.get("PERF1_HARNESS_CPUS", "")
    return expand(v) if v else []


def pick_cpus(spec):
    """'auto:N' = N CPUs of the CURRENT container cpuset on distinct physical cores, excluding the
    harness CPUs and their SMT siblings. The LXD host may move a container whose limits.cpu is a
    count, so a fixed list can go stale (pilot finding)."""
    if not spec.startswith("auto:"):
        return spec
    n = int(spec.split(":")[1])
    reserved = {s for h in harness_cpus() for s in siblings(h)}
    chosen, cores = [], set()
    for c in expand(container_cpuset()):
        if c in reserved:
            continue
        core = open(f"/sys/devices/system/cpu/cpu{c}/topology/core_id").read().strip()
        if core not in cores:
            cores.add(core)
            chosen.append(c)
    if len(chosen) < n:
        sys.exit(f"need {n} distinct physical cores, container has {chosen}")
    return ",".join(map(str, chosen[:n]))


def host_cpu_busy():
    """Host-wide per-CPU (total, idle) jiffies; /proc/stat in this container shows the whole host."""
    d = {}
    for l in open("/proc/stat"):
        if l.startswith("cpu") and l[3].isdigit():
            f = l.split()
            v = list(map(int, f[1:]))
            d[int(f[0][3:])] = (sum(v), v[3] + v[4])
    return d


def affinity(pid):
    return open(f"/proc/{pid}/status").read().split("Cpus_allowed_list:")[1].split()[0]


def boot(server, console, cpus=None, java_opts=None):
    if server_pid(server):
        sys.exit("a server is already running in " + server)
    cmd = ["bash", "run.sh", "nogui"]
    if cpus:
        cmd = ["taskset", "-c", cpus] + cmd
    out = open(console, "w")
    env = dict(os.environ)
    env.pop("JDK_JAVA_OPTIONS", None)
    if java_opts:
        env["JDK_JAVA_OPTIONS"] = java_opts   # per-run options never touch user_jvm_args.txt
    subprocess.Popen(cmd, cwd=server, stdout=out, stderr=subprocess.STDOUT,
                     stdin=subprocess.DEVNULL, start_new_session=True, env=env)
    for _ in range(600):
        time.sleep(1)
        with open(console, errors="replace") as f:
            if "Done (" in f.read():
                break
    else:
        sys.exit("server did not finish booting")
    pid = server_pid(server)
    if not pid:
        sys.exit("no java process with cwd " + server)
    time.sleep(3)
    return pid


def stop(pid, rc):
    try:
        rc.cmd("stop")
    except Exception:
        pass
    for _ in range(180):
        if not os.path.exists(f"/proc/{pid}"):
            return True
        time.sleep(1)
    return False


def process_cpu(pid):
    """Whole-process CPU seconds from /proc/<pid>/stat: unlike the per-thread sum it keeps the
    time of threads that exited during the window."""
    f = open(f"/proc/{pid}/stat").read().rsplit(")", 1)[1].split()
    return (int(f[11]) + int(f[12])) / CLK


def jit_cpu(pid):
    """CPU seconds of the JIT compiler threads. Needs -XX:-UseDynamicNumberOfCompilerThreads so
    the threads never exit (their time would be lost from the per-thread sum)."""
    return thread_cpu(pid).get("jit", 0.0)


def pin_jit(pid, cpus):
    """Pin the JIT compiler threads (persistent with -XX:-UseDynamicNumberOfCompilerThreads)
    to the harness CPUs: at N=1 C2 recompilation after unstable_if deopts took ~22% of the only
    benchmark core in steady state (pilot finding), penalising low N. JIT is JVM warm-up cost,
    not Core cost; its CPU is still recorded."""
    pinned = []
    for tid in os.listdir(f"/proc/{pid}/task"):
        try:
            name = open(f"/proc/{pid}/task/{tid}/comm").read().strip()
        except OSError:
            continue
        if "CompilerThre" in name:
            os.sched_setaffinity(int(tid), cpus)
            pinned.append(int(tid))
    return pinned


def jit_affinities(pid):
    out = set()
    for tid in os.listdir(f"/proc/{pid}/task"):
        try:
            if "CompilerThre" in open(f"/proc/{pid}/task/{tid}/comm").read():
                out.add(open(f"/proc/{pid}/task/{tid}/status").read()
                        .split("Cpus_allowed_list:")[1].split()[0])
        except OSError:
            continue
    return sorted(out)


def counter_now(server):
    with open(os.path.join(server, "perf1-counter.csv")) as f:
        last = f.read().strip().splitlines()[-1]
    return int(last.split(",")[1])


def warm_up(pid, server, minimum_s, min_ticks, quiet_window=60, quiet_jit_cpu_s=0.5,
            maximum=3600):
    """Warmup measured in WORK, not time: at least min_ticks executed entity ticks (the same for
    every N, because tiered-JIT thresholds count invocations), at least minimum_s seconds, and
    then the JIT quiet for quiet_window seconds (its threads used <= quiet_jit_cpu_s CPU).
    Pilot findings: a fixed 120 s left up to 196 s of >=1 s compilations in the N=1 window, and
    a 30 s quiet check passed at 120 s while the JIT used 29% of the next window at N=1."""
    start = time.time()
    t0 = counter_now(server)
    hist = [(start, jit_cpu(pid))]
    while True:
        time.sleep(5)
        now = time.time()
        hist.append((now, jit_cpu(pid)))
        old = [h for h in hist if h[0] <= now - quiet_window]
        work_done = counter_now(server) - t0 >= min_ticks
        if now - start >= minimum_s and work_done and old \
                and hist[-1][1] - old[-1][1] <= quiet_jit_cpu_s:
            return now - start
        if now - start >= maximum:
            return -(now - start)          # never converged: recorded, makes the run void


def thread_cpu(pid):
    """CPU seconds per thread group: region workers, main, GC, other."""
    groups = {}
    for tid in os.listdir(f"/proc/{pid}/task"):
        try:
            with open(f"/proc/{pid}/task/{tid}/comm") as f:
                name = f.read().strip()
            with open(f"/proc/{pid}/task/{tid}/stat") as f:
                fields = f.read().rsplit(")", 1)[1].split()
        except OSError:
            continue
        cpu = (int(fields[11]) + int(fields[12])) / CLK
        if name.startswith("NestWorld-Regio"):
            g = "region"
        elif name == "Server thread":
            g = "main"
        elif "CompilerThre" in name:
            g = "jit"
        elif name.startswith(("GC Thread", "G1 ")):
            g = "gc"
        else:
            g = "other"
        groups[g] = groups.get(g, 0.0) + cpu
    groups["total"] = sum(groups.values())
    return groups


# ---------------------------------------------------------------- parsing
def gametime(rc):
    m = re.search(r"(\d+)", strip(rc.cmd("time query gametime")))
    return int(m.group(1))


def entity_count(rc, sel):
    m = re.search(r"count: (\d+)", strip(rc.cmd(f"execute if entity {sel}")))
    return int(m.group(1)) if m else 0


def fairness(rc):
    """[(region id, owned, ticked, deferred)] from /nestworld entityfairness."""
    out = strip(rc.cmd("nestworld entityfairness"))
    return [tuple(map(int, m)) for m in
            re.findall(r"#(\d+) Entities (\d+)\s+Ticked (\d+)\s+Deferred (\d+)", out)]


def layout(rc):
    """Sorted region ids with their bounds, from /nestworld status <dim> full."""
    out = strip(rc.cmd("nestworld status minecraft:overworld full"))
    regs = re.findall(r"#(\d+) \[([^\]]+)\]", out)
    return sorted((int(i), b) for i, b in regs), out


def queue_depth_max(rc):
    """Max region mailbox depth right now (Core exposes it via interactionwaitbudget)."""
    m = re.search(r"regionQueueDepthMax=(\d+)", strip(rc.cmd("nestworld interactionwaitbudget")))
    return int(m.group(1)) if m else None


def mspt(rc):
    out = strip(rc.cmd("nestworld mspt"))
    m = re.search(r"avg=([\d.]+) p50=([\d.]+) p95=([\d.]+) p99=([\d.]+) max=([\d.]+)", out)
    return dict(zip(["avg", "p50", "p95", "p99", "max"], map(float, m.groups()))) if m else None


def counter_at(server, t):
    """(timestamp_ms, cumulative living ticks) of the counter line closest to wall time t."""
    best = None
    with open(os.path.join(server, "perf1-counter.csv")) as f:
        for line in f:
            ts, n = line.strip().split(",")
            ts, n = int(ts), int(n)
            if best is None or abs(ts / 1000 - t) < abs(best[0] / 1000 - t):
                best = (ts, n)
    return best


# ---------------------------------------------------------------- layout build
def merge_all(rc, log):
    """Merge sibling regions until the overworld is one region. Loading-time load can make
    the automatic splitter cut the world before the harness gets control (Core's region cap
    is never below 4), so the layout is always rebuilt from a single root."""
    for _ in range(64):
        ids = [i for i, _ in layout(rc)[0]]
        if len(ids) == 1:
            return ids[0]
        merged = False
        for i in ids:
            for j in ids:
                if i < j:
                    out = strip(rc.cmd(f"nestworld merge {i} {j}"))
                    if out.startswith("Merged"):
                        log.append(out)
                        merged = True
                        break
            if merged:
                break
        if not merged:
            raise RuntimeError(f"no sibling pair to merge among {ids}")
        time.sleep(1)
    raise RuntimeError("merge_all did not converge")


def wait_loaded(rc, total, timeout=600):
    """All cows loaded and owned by regions (entity loading after boot is gradual)."""
    end = time.time() + timeout
    while time.time() < end:
        if entity_count(rc, "@e[tag=perf1]") == total and \
                sum(o for _, o, _, _ in fairness(rc)) == total:
            return
        time.sleep(5)
    raise RuntimeError("cows did not finish loading")


def expected_cows(cells):
    """Cows expected in each cell. W1 cells.json has no "cows" list: every cell holds
    cows_per_cell. W2 lists them per cell (one hotspot cell, the rest empty)."""
    return cells.get("cows") or [cells["cows_per_cell"]] * len(cells["cells"])


def cell_bounds(cells, cl):
    """(x, y, z, dx, dy, dz) volume a cell's cows must stay in. W1 default: the one-layer cell."""
    if "bounds" in cl:
        return tuple(cl["bounds"])
    side = cells["side"]
    return (cl["x0"], -61, cl["z0"], side - 1, 3, side - 1)


def wall_checks(cells):
    """[(cell k, box (x1,y1,z1,x2,y2,z2), expected stone)]. W1 default: one box per cell."""
    if "verify" in cells:
        return [(v["k"], tuple(v["box"]), v["stone"]) for v in cells["verify"]]
    side = cells["side"]
    return [(cl["k"], (cl["x0"], -60, cl["z0"], cl["x0"] + side - 1, -59, cl["z0"] + side - 1),
             cells["stone_per_cell"]) for cl in cells["cells"]]


def build_layout(rc, cells, log):
    """One region per cell, built while the world holds no load (see gen_datapack.py):
    merge to a single root, steer 15 manual splits with markers, then summon the cows."""
    n_cells = len(cells["cells"])
    merge_all(rc, log)
    rc.cmd("function perf1:markers")
    wait_owned(rc, n_cells)
    regs = {rid: owned for rid, owned, _, _ in fairness(rc)}
    root = [rid for rid, owned in regs.items() if owned == n_cells]
    if len(root) != 1:
        raise RuntimeError(f"no single region holds all {n_cells} cell markers: {regs}")
    work = [(root[0], cells["cells"])]
    splits = 0
    while work:
        rid, group = work.pop()
        if len(group) == 1:
            continue
        upper = group[len(group) // 2]
        # Core cuts at the chunk of the median owned entity and keeps that chunk in the lower
        # child. One marker per cell plus this gap marker, in the chunk just before the upper
        # cell, makes the gap marker the median, so the cut falls between the cells.
        mx, mz = upper["chunk_x"] * 16 - 4, upper["chunk_z"] * 16 - 4
        rc.cmd(f"summon minecraft:marker {mx} -60 {mz} {{Tags:[\"perf1m\"]}}")
        wait_owned(rc, len(group) + 1, rid)
        out = strip(rc.cmd(f"nestworld split {rid}"))
        rc.cmd("kill @e[type=minecraft:marker,tag=perf1m]")
        log.append(out)
        sm = re.search(r"Split #\d+ -> #(\d+) \+ #(\d+)", out)
        if not sm:
            raise RuntimeError(f"split of #{rid} failed: {out}")
        splits += 1
        a, b = int(sm.group(1)), int(sm.group(2))
        work.append((a, group[:len(group) // 2]))
        work.append((b, group[len(group) // 2:]))
    time.sleep(2)
    per = sorted(owned for _, owned, _, _ in fairness(rc))
    if per != [1] * n_cells:
        raise RuntimeError(f"markers are not one per region: {per}")
    rc.cmd("kill @e[type=minecraft:marker,tag=perf1cell]")
    rc.cmd("function perf1:cows")
    exp = expected_cows(cells)
    wait_loaded(rc, sum(exp))
    per = sorted(owned for _, owned, _, _ in fairness(rc))
    if per != sorted(x for x in exp if x):
        raise RuntimeError(f"layout is not one cell per region: {per} vs {exp}")
    return splits


def wait_owned(rc, n, rid=None, timeout=60):
    """Wait until the region (or all regions) own n entities."""
    end = time.time() + timeout
    while time.time() < end:
        f = fairness(rc)
        have = sum(o for r, o, _, _ in f if rid is None or r == rid)
        if have == n:
            return
        time.sleep(0.5)
    raise RuntimeError(f"expected {n} owned entities in {rid or 'all regions'}")


# ---------------------------------------------------------------- commands
def verify_walls(rc, cells):
    """Count each cell's stone with a masked clone into a scratch area that is cleared again;
    returns the cells whose count differs from the generator's expectation."""
    rc.cmd("forceload add 0 0 127 127")
    time.sleep(3)
    bad = []
    for k, (x1, y1, z1, x2, y2, z2), want in wall_checks(cells):
        out = strip(rc.cmd(f"clone {x1} {y1} {z1} {x2} {y2} {z2} 0 -60 0 masked force"))
        m = re.search(r"(\d+) block", out)
        n = int(m.group(1)) if m else 0
        rc.cmd(f"fill 0 -60 0 {x2 - x1} {-60 + y2 - y1} {z2 - z1} minecraft:air")
        if n != want:
            bad.append((k, (x1, y1, z1), n, want))
    rc.cmd("forceload remove 0 0 127 127")
    return bad


def prepare(server, dp, snapshot):
    console = snapshot + ".console.log"
    pid = boot(server, console)
    rc = Rcon()
    dst = os.path.join(server, "world", "datapacks", "perf1")
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(dp, dst)
    rc.cmd("reload")
    time.sleep(5)
    cells = json.load(open(os.path.join(dp, "cells.json")))
    print(strip(rc.cmd("function perf1:setup")))
    # walls only once every forceloaded cell chunk is actually loaded: a fill on an unloaded
    # chunk fails silently (the first snapshot had no walls at all in cell 15)
    corners = sorted({(x, z) for cl in cells["cells"] for bx, _, bz, dx, _, dz in [cell_bounds(cells, cl)]
                      for x in (bx, bx + dx) for z in (bz, bz + dz)})
    for _ in range(120):
        if all("passed" in strip(rc.cmd(f"execute if loaded {x} -60 {z}")) for x, z in corners):
            break
        time.sleep(1)
    else:
        sys.exit("cell chunks did not load")
    print(strip(rc.cmd("function perf1:walls")))
    time.sleep(3)
    bad = verify_walls(rc, cells)
    if bad:
        sys.exit(f"cells with incomplete walls: {bad}")
    print(f"walls verified: {len(wall_checks(cells))} boxes, "
          f"{sum(w for _, _, w in wall_checks(cells))} stone")
    have = entity_count(rc, "@e")
    print(f"entities in snapshot: {have}")
    rc.cmd("save-all flush")
    time.sleep(5)
    stop(pid, rc)
    with tarfile.open(snapshot, "w") as t:
        t.add(os.path.join(server, "world"), arcname="world")
    digest = subprocess.run(["sha256sum", snapshot], capture_output=True, text=True).stdout.split()[0]
    print(f"snapshot {snapshot} sha256 {digest}")
    if have != 0:
        sys.exit("snapshot must hold no entities")


CANON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server-config")


def check_config(server):
    """The server must run the canonical PERF-1A config committed in tests/perf1/server-config:
    user_jvm_args.txt byte-identical, and every key of the canonical server.properties equal."""
    if open(os.path.join(server, "user_jvm_args.txt")).read() != \
            open(os.path.join(CANON, "user_jvm_args.txt")).read():
        sys.exit("user_jvm_args.txt differs from tests/perf1/server-config")
    props = dict(l.strip().split("=", 1) for l in open(os.path.join(server, "server.properties"))
                 if "=" in l and not l.startswith("#"))
    for l in open(os.path.join(CANON, "server.properties")):
        k, v = l.strip().split("=", 1)
        if props.get(k) != v:
            sys.exit(f"server.properties {k}={props.get(k)} differs from canonical {v}")


def run(server, snapshot, dp, n, cpus, tag, out_dir, warmup, window, warmup_ticks):
    os.makedirs(out_dir, exist_ok=True)
    check_config(server)
    cpus = pick_cpus(cpus)
    smt = {c: siblings(c) for c in expand(cpus)}
    res = {"tag": tag, "N": n, "cpus": cpus, "container_cpuset_at_start": container_cpuset(),
           "harness_cpus": harness_cpus(),
           "harness_reserved": sorted({x for h in harness_cpus() for x in siblings(h)}),
           "smt_siblings": {str(k): v for k, v in smt.items()}, "warmup_s": warmup, "window_s": window,
           "snapshot_sha256": subprocess.run(["sha256sum", snapshot], capture_output=True,
                                             text=True).stdout.split()[0]}
    props = dict(l.strip().split("=", 1) for l in open(os.path.join(server, "server.properties"))
                 if "=" in l and not l.startswith("#"))
    # the N=1 point is deliberately below 4 TPS; the vanilla watchdog would stop the server
    # there without any long tick (lag behind nextTickTime is only caught up every 300 ticks)
    res["max_tick_time"] = props.get("max-tick-time", "60000")
    with open(os.path.join(server, "user_jvm_args.txt")) as f:
        res["jvm_args"] = [l.strip() for l in f if l.strip() and not l.startswith("#")]
    res["jvm_args"] = [a for a in res["jvm_args"] if not a.startswith("-Xlog")]
    cells = json.load(open(os.path.join(dp, "cells.json")))
    shutil.rmtree(os.path.join(server, "world"), ignore_errors=True)
    for f in ("perf1-counter.csv",):
        if os.path.exists(os.path.join(server, f)):
            os.remove(os.path.join(server, f))
    with tarfile.open(snapshot) as t:
        t.extractall(server)

    gclog = os.path.abspath(os.path.join(out_dir, f"{tag}-gc.log"))
    pid = boot(server, os.path.join(out_dir, f"{tag}-console.log"), cpus,
               java_opts=f"-Xlog:gc*:file={gclog}:uptime,time")
    res["pid"], res["cwd"] = pid, os.path.realpath(f"/proc/{pid}/cwd")
    res["affinity"] = affinity(pid)
    jit_cpus = harness_cpus()
    res["jit_threads_pinned"] = len(pin_jit(pid, jit_cpus)) if jit_cpus else 0
    rc = Rcon()
    log = []
    res["layout_splits"] = build_layout(rc, cells, log)
    res["layout_log"] = log
    res["entities"] = entity_count(rc, "@e[tag=perf1]")
    res["non_cow_entities"] = entity_count(rc, "@e[tag=!perf1]")
    res["warmup_min_ticks"] = warmup_ticks
    res["warmup_actual_s"] = warm_up(pid, server, warmup, warmup_ticks)

    # ---- measurement window
    jfr = os.path.abspath(os.path.join(out_dir, f"{tag}.jfr"))
    lay0, _ = layout(rc)
    t0 = time.time()
    g0 = gametime(rc)
    cpu0 = thread_cpu(pid)
    proc0 = process_cpu(pid)

    host0 = host_cpu_busy()
    subprocess.run(["jcmd", str(pid), "JFR.start", "name=perf1", f"filename={jfr}",
                    "settings=default"], capture_output=True)
    blocks, fair, g_last = [], [], g0
    seen_aff, seen_cpuset = {affinity(pid)}, {container_cpuset()}
    seen_jit_aff = set(jit_affinities(pid))
    depth = []
    while time.time() - t0 < window:
        time.sleep(1)
        seen_aff.add(affinity(pid))
        seen_jit_aff.update(jit_affinities(pid))
        seen_cpuset.add(container_cpuset())
        g = gametime(rc)
        if g - g_last >= 100:                      # non-overlapping 100-tick blocks
            blocks.append(mspt(rc))
            g_last = gametime(rc)
        if len(depth) < int((time.time() - t0) // 5):
            depth.append(queue_depth_max(rc))
        if len(fair) < int((time.time() - t0) // 30):
            fair.append(fairness(rc))
    g1 = gametime(rc)
    t1 = time.time()
    cpu1 = thread_cpu(pid)
    proc1 = process_cpu(pid)

    host1 = host_cpu_busy()
    subprocess.run(["jcmd", str(pid), "JFR.stop", "name=perf1"], capture_output=True)
    lay1, status_full = layout(rc)
    fair.append(fairness(rc))
    # mailbox latency is not exposed by Core unless -Dnestworld.mailboxAudit=true (owner decision);
    # depth is sampled every 5 s, the lifecycle/audit counters are read once at the end
    res["mailbox_depth_max_samples"] = depth
    res["pressure"] = strip(rc.cmd("nestworld pressure"))
    res["status_full_end"] = status_full
    res["entities_end"] = entity_count(rc, "@e[tag=perf1]")
    # every cow still inside its own cell at the end of the window (cells are sealed)
    res["cows_in_own_cell_end"] = [entity_count(
        rc, "@e[tag=perf1c{},x={},y={},z={},dx={},dy={},dz={}]".format(cl["k"], *cell_bounds(cells, cl)))
        for cl in cells["cells"]]
    res["cows_per_cell"] = cells["cows_per_cell"]
    res["expected_cows"] = expected_cows(cells)
    c0, c1 = counter_at(server, t0), counter_at(server, t1)

    dt = t1 - t0
    res.update({
        "window_wall_s": dt, "gametime": [g0, g1],
        "tps": (g1 - g0) / dt,
        "primary_ticks_per_s": (c1[1] - c0[1]) / ((c1[0] - c0[0]) / 1000.0),
        "counter_span_ms": c1[0] - c0[0],
        "mspt_blocks": blocks,
        "layout_start": lay0, "layout_end": lay1,
        "deferred_samples": [sum(d for _, _, _, d in s) for s in fair],
        "fairness_samples": fair,
        "regions_with_entities": [len(s) for s in fair],
        "cpu_s": {k: cpu1.get(k, 0) - cpu0.get(k, 0) for k in cpu1},
        "affinity_seen": sorted(seen_aff), "jit_affinity_seen": sorted(seen_jit_aff), "container_cpuset_seen": sorted(seen_cpuset),
        "jfr": jfr, "gc_log": gclog,
    })
    res["secondary_ticks_per_s"] = res["tps"] * res["entities"]
    # interference on a host that cannot isolate cores: the SMT siblings of the benchmark CPUs
    # must stay idle, and the benchmark CPUs must carry nothing but this server
    bench = expand(cpus)
    sib = sorted({x for c in bench for x in siblings(c)} - set(bench))

    def busy_s(c):
        tot, idle = host1[c][0] - host0[c][0], host1[c][1] - host0[c][1]
        return (tot - idle) / CLK
    res["sibling_busy_pct"] = {str(c): 100 * busy_s(c) / dt for c in sib}
    res["process_cpu_s"] = proc1 - proc0
    res["bench_cpu_s"] = res["process_cpu_s"] - res["cpu_s"].get("jit", 0.0)
    res["jit_cpu_s_in_window"] = res["cpu_s"].get("jit", 0.0)
    res["foreign_on_bench_pct"] = 100 * (sum(busy_s(c) for c in bench) - res["bench_cpu_s"]) / (dt * len(bench))
    res["cpu_util"] = {k: v / (dt * n) for k, v in res["cpu_s"].items()}
    res["stopped_cleanly"] = stop(pid, rc)
    res.update(jfr_summary(jfr))
    with open(os.path.join(out_dir, f"{tag}.json"), "w") as f:
        json.dump(res, f, indent=1)
    print(json.dumps({k: res[k] for k in ("tag", "N", "tps", "primary_ticks_per_s",
                                          "secondary_ticks_per_s", "deferred_samples")}))


def jfr_summary(path):
    """GC pause total/count and sampled allocation weight in the window, from the JFR file."""
    out = {}
    try:
        p = subprocess.run(["jfr", "print", "--json", "--events",
                            "jdk.GarbageCollection,jdk.ObjectAllocationSample", path],
                           capture_output=True, text=True, timeout=600)
        ev = json.loads(p.stdout)["recording"]["events"]
    except Exception as e:  # recorded, never hidden
        return {"jfr_error": str(e)}
    gcs = [e for e in ev if e["type"] == "jdk.GarbageCollection"]

    def dur(s):  # ISO-8601 duration "PT0.0123S"
        m = re.match(r"PT(?:(\d+)M)?([\d.]+)S", s)
        return (int(m.group(1) or 0) * 60 + float(m.group(2))) if m else 0.0
    out["gc_count"] = len(gcs)
    out["gc_pause_total_s"] = sum(dur(e["values"]["sumOfPauses"]) for e in gcs)
    out["alloc_sampled_bytes"] = sum(e["values"]["weight"] for e in ev
                                     if e["type"] == "jdk.ObjectAllocationSample")
    return out


def validate(paths):
    runs = [json.load(open(p)) for p in paths]
    ok_all = True
    verdicts = []
    for r in runs:
        exp = r.get("expected_cows") or [r.get("cows_per_cell")] * 16
        nonzero = sorted(x for x in exp if x)
        checks = {
            "process cwd is the server": r["cwd"].endswith("/server"),
            "vanilla watchdog off (max-tick-time=-1)": r.get("max_tick_time") == "-1",
            "mailboxAudit off (primary run)":
                "-Dnestworld.mailboxAudit=true" not in r["jvm_args"] or r["tag"].startswith("diag-"),
            "one CPU per physical core": all(
                set(v) & set(map(int, r["cpus"].split(","))) == {int(k)}
                for k, v in r.get("smt_siblings", {}).items()) and "smt_siblings" in r,
            "harness CPUs (and siblings) not used by the server":
                not set(r.get("harness_reserved", [])) & set(map(int, r["cpus"].split(","))),
            "affinity == requested cpus": expand(r["affinity"]) == expand(r["cpus"]),
            "affinity stable in window": [expand(a) for a in r["affinity_seen"]] == [expand(r["cpus"])],
            "container cpuset stable in window": len(r["container_cpuset_seen"]) == 1,
            "CPU used <= N cores":
                r.get("bench_cpu_s", r["cpu_s"]["total"]) / r["window_wall_s"] <= r["N"] * 1.02,
            "layout: 15 splits, one cell per region": r["layout_splits"] == 15,
            "16 regions in total": len(r["layout_start"]) == 16,
            "only workload entities": r["non_cow_entities"] == 0,
            "layout unchanged in window (split=0, merge=0)": r["layout_start"] == r["layout_end"],
            "deferred = 0": all(d == 0 for d in r["deferred_samples"]),
            "every region owns its cell and ticks all of it in every sample":
                "fairness_samples" in r and all(
                    sorted(o for _, o, _, _ in smp) == nonzero and all(t == o for _, o, t, _ in smp)
                    for smp in r["fairness_samples"]),
            "every cow inside its own cell at window end": r.get("cows_in_own_cell_end") == exp,
            "regions with entities == non-empty cells":
                all(n == len(nonzero) for n in r["regions_with_entities"]),
            "entity count stable": r["entities"] == r["entities_end"],
            "not truncated by the 20 TPS cap": r["tps"] < 19.0 and
                sorted(b["p50"] for b in r["mspt_blocks"])[len(r["mspt_blocks"]) // 2] >= 50.0,
            "primary ~ secondary (1%)":
                abs(r["primary_ticks_per_s"] / r["secondary_ticks_per_s"] - 1) < 0.01,
            "mspt blocks collected": len(r["mspt_blocks"]) >= 2 and all(r["mspt_blocks"]),
            "gc/jfr collected": "gc_count" in r and os.path.getsize(r["gc_log"]) > 0,
            "SMT siblings idle (<= 3%)": "sibling_busy_pct" in r and
                all(v <= 3.0 for v in r["sibling_busy_pct"].values()),
            "no foreign load on bench CPUs (<= 3%)": "foreign_on_bench_pct" in r and
                r["foreign_on_bench_pct"] <= 3.0,
            "JIT converged before the window": r.get("warmup_actual_s", -1) > 0,
            "JIT threads off the benchmark CPUs": r.get("jit_threads_pinned", 0) > 0 and
                all(set(expand(a)) == set(r["harness_cpus"]) for a in r.get("jit_affinity_seen", ["x"])),
            "dynamic compiler threads off":
                "-XX:-UseDynamicNumberOfCompilerThreads" in r["jvm_args"],
            "mailbox depth collected": len(r.get("mailbox_depth_max_samples", [])) >= 2 and
                None not in r["mailbox_depth_max_samples"],
            "stopped cleanly": r["stopped_cleanly"],
        }
        bad = [k for k, v in checks.items() if not v]
        ok_all &= not bad
        verdicts.append(not bad)
        print(f"{r['tag']}: {'VALID' if not bad else 'VOID: ' + '; '.join(bad)}")
    by_n = {}
    for r, valid in zip(runs, verdicts):
        if valid:  # spread is a property of valid runs only
            by_n.setdefault(r["N"], []).append(r["primary_ticks_per_s"])
    # owner gate (2026-10-04): CV <= 5% VALID; 5-10% UNSTABLE, repeat; > 10% INVALID
    # ENVIRONMENT, investigate. Only CV <= 5% enters the PERF-1A dataset. Needs >= 3 valid runs.
    spread_ok = True
    for n_, v in sorted(by_n.items()):
        mean = sum(v) / len(v)
        cv = (sum((x - mean) ** 2 for x in v) / len(v)) ** 0.5 / mean if len(v) > 1 else 0.0
        tier = ("TOO FEW VALID RUNS" if len(v) < 3 else "VALID" if cv <= 0.05
                else "UNSTABLE - repeat" if cv <= 0.10 else "INVALID ENVIRONMENT - investigate")
        spread_ok &= tier == "VALID"
        print(f"N={n_}: valid runs={len(v)} primary mean={mean:.0f}/s CV={cv * 100:.1f}% {tier}")
    layouts = {json.dumps(r["layout_start"]) for r in runs}
    same = len(layouts) == 1
    print(f"same topology across runs: {same}")
    print(f"same jvm args across runs: {len({json.dumps(r['jvm_args']) for r in runs}) == 1}")
    print(f"same snapshot across runs: {len({r['snapshot_sha256'] for r in runs}) == 1}")
    return 0 if ok_all and same and spread_ok else 1


def main():
    a = sys.argv[1:]
    if harness_cpus():
        os.sched_setaffinity(0, harness_cpus())  # the driver, jcmd and jfr stay off benchmark CPUs
    if a and a[0] == "prepare":
        prepare(*a[1:4])
    elif a and a[0] == "run":
        opts = {"--warmup": 120, "--window": 300, "--warmup-ticks": 15_000_000}
        pos = []
        it = iter(a[1:])
        for x in it:
            if x in opts:
                opts[x] = int(next(it))
            else:
                pos.append(x)
        server, snap, dp, n, cpus, tag, out = pos
        run(server, snap, dp, int(n), cpus, tag, out, opts["--warmup"], opts["--window"],
            opts["--warmup-ticks"])
    elif a and a[0] == "validate":
        sys.exit(validate(a[1:]))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
