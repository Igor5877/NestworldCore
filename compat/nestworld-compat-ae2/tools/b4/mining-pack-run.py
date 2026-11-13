#!/usr/bin/env python3
"""Run no-addon / addon-OFF / addon-ON from one frozen world, in a private network namespace."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('candidate')
parser.add_argument('--extra-jvm', action='append', default=[], help='extra -D flag line for the test JVM')
parser.add_argument('--pre-cmd', action='append', default=[], help='server command run after chunk load, before warmup (test copy only)')
parser.add_argument('--before-cmd', action='append', default=[], help='extra read-only query right before the window')
parser.add_argument('--after-cmd', action='append', default=[], help='extra read-only query right after the window')
parser.add_argument('--census', action='store_true', help='run AE2 grid + ingress census over the measured window')
parser.add_argument('--status', action='store_true', help='clean benchmark: read-only status queries after the window (no census)')
parser.add_argument('--load-retries', type=int, default=13, help='5 s retries for the claim-chunk load check')
parser.add_argument('--no-jfr', action='store_true', help='clean benchmark: no JFR recording during the window')
parser.add_argument('--extra-mod', action='append', default=[], type=Path,
                    help='additional addon jar copied into the test copy mods/ for the run (removed afterwards)')
parser.add_argument('--seconds', type=int, default=120)
parser.add_argument('--warmup', type=int, default=90)
parser.add_argument('--modes', nargs='+', choices=['none', 'off', 'on', 'typed', 'verify', 'cursorverify', 'transitfx', 'emptycfg', 'chestguard'], default=['none', 'off', 'on'])
parser.add_argument('--core-overlay', type=Path)
parser.add_argument('--server-overlay', type=Path, help='test-only replacement for the installed forge-*-server.jar (restored afterwards)')
parser.add_argument('--import-cursor', action='store_true')
args = parser.parse_args()
P = Path('/root/airis-2026-10-25/WP3B-MINING-20261111')
S = Path('/root/airis-2026-10-25/wp3b-mining-snapshot-20261111-1330/world')
E = P / ('comparison-' + time.strftime('%Y%m%d-%H%M%S'))
JAVA = '/usr/lib/jvm/java-17-openjdk-amd64/bin/java'
JCMD = '/usr/lib/jvm/java-17-openjdk-amd64/bin/jcmd'
RCON = '/root/airis-2026-10-25/rcon.py'
candidate = Path(args.candidate).resolve()
assert candidate.is_file() and S.is_dir() and P.is_dir()
E.mkdir(mode=0o700)
(P / 'logs').mkdir(exist_ok=True)  # the copied JVM GC logger opens this before Minecraft creates directories
frozen = E / 'candidate.jar'
shutil.copy2(candidate, frozen)
(E / 'candidate.sha256').write_text(hashlib.sha256(frozen.read_bytes()).hexdigest() + '\n')
chunks = sorted({tuple(row) for row in json.loads((P / 'mining-chunks.json').read_text())})
properties = (P / 'server.properties').read_text()
port = int(re.search(r'^rcon.port=(\d+)$', properties, re.M)[1])
forge_args = re.search(r'@(\S*unix_args.txt)', (P / 'run.sh').read_text())[1]
lock = P / '.comparison.lock'
with lock.open('x') as f:
    f.write(str(os.getpid()))
process = None
phase = None
core_target = None
fixture_config = None


def test_pid():
    found = []
    for item in Path('/proc').iterdir():
        if not item.name.isdigit():
            continue
        try:
            if (item / 'cwd').resolve() == P and (item / 'exe').resolve().name == 'java':
                found.append(int(item.name))
        except (FileNotFoundError, PermissionError, OSError):
            pass
    if len(found) > 1:
        raise RuntimeError('Multiple test JVMs; refusing to control them')
    return found[0] if found else None


def rcon(*commands, timeout=30):
    pid = test_pid()
    if pid is None:
        raise RuntimeError('Test JVM absent')
    result = subprocess.run(['nsenter', '-t', str(pid), '-n', 'python3', RCON, str(port), str(P / '.rcon-pass'), *commands],
                            cwd=P, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Test RCON unavailable')
    return result.stdout


def stop():
    global process
    if process is None:
        return
    if process.poll() is not None:
        process = None
        return
    try:
        rcon('stop', timeout=15)
    except (RuntimeError, subprocess.TimeoutExpired):
        process.terminate()
    try:
        process.wait(timeout=90)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
        if phase:
            (phase / 'INVALID-stop-hang.txt').write_text('Test JVM did not stop gracefully\n')
    process = None


def checked_wait(seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Test JVM exited')
        time.sleep(min(2, max(0, deadline - time.monotonic())))


try:
    if test_pid() is not None:
        raise RuntimeError('Existing test JVM; no files changed')
    if 'cursorverify' in args.modes:
        # FE is disabled in production. Enable it only for this correctness fixture to exercise the MAIN suffix.
        fixture_config = P / 'config/appflux-common.toml'
        shutil.copy2(fixture_config, E / 'original-appflux-common.toml')
        original = fixture_config.read_text()
        assert re.search(r'^enable_FE_pull\s*=\s*false\s*$', original, re.M)
        fixture_config.write_text(re.sub(r'^enable_FE_pull\s*=\s*false\s*$', 'enable_FE_pull = true', original, flags=re.M))
    if args.core_overlay:
        assert args.core_overlay.is_file()
        core_target = P / Path(forge_args).parent / (Path(forge_args).parent.name.replace('1.20.1-', 'forge-1.20.1-', 1) + '-universal.jar')
        assert core_target.is_file()
        shutil.copy2(core_target, E / 'original-core.jar')
        shutil.copy2(args.core_overlay, E / 'core-overlay.jar')
        shutil.copy2(E / 'core-overlay.jar', core_target)
        (E / 'core-overlay.sha256').write_text(hashlib.sha256(core_target.read_bytes()).hexdigest() + '\n')
    server_target = None
    if args.server_overlay:
        assert args.server_overlay.is_file()
        server_target = P / Path(forge_args).parent / (Path(forge_args).parent.name.replace('1.20.1-', 'forge-1.20.1-', 1) + '-server.jar')
        assert server_target.is_file()
        shutil.copy2(server_target, E / 'original-server.jar')
        shutil.copy2(args.server_overlay, server_target)
        (E / 'server-overlay.sha256').write_text(hashlib.sha256(server_target.read_bytes()).hexdigest() + '\n')
    extra_installed = []
    for extra in args.extra_mod:
        assert extra.is_file() and extra.name.startswith('nestworld-compat-') and not extra.name.startswith('nestworld-compat-ae2')
        target = P / 'mods' / ('nwextra-' + extra.name)
        assert not target.exists()
        shutil.copy2(extra, target)
        extra_installed.append(target)
        (E / ('extra-' + extra.name + '.sha256')).write_text(hashlib.sha256(target.read_bytes()).hexdigest() + '\n')
    for index, mode in enumerate(args.modes, 1):
        phase = E / f'{index}-{mode}'
        phase.mkdir()
        print(f'PHASE {mode}: restore frozen world', flush=True)
        subprocess.run(['rsync', '-a', '--delete', str(S) + '/', str(P / 'world') + '/'], check=True)
        for addon in (P / 'mods').glob('nestworld-compat-ae2-*.jar'):
            addon.unlink()  # owned test copy only; the live path is never targeted
        if mode != 'none':
            shutil.copy2(frozen, P / 'mods/nestworld-compat-ae2-candidate.jar')
        flags = (P / 'base-jvm-args.txt').read_text()
        flags += '-Djava.rmi.server.hostname=127.0.0.1\n'
        flags += '-Dnestworld.compatAe2.busTransit=' + ('true' if mode in ['on', 'typed', 'cursorverify', 'transitfx', 'emptycfg', 'chestguard'] else 'false') + '\n'
        flags += '-Dnestworld.compatAe2.busImportCursor=' + ('true' if args.import_cursor or mode in ['cursorverify', 'transitfx', 'emptycfg', 'chestguard'] else 'false') + '\n'
        flags += '-Dnestworld.compatAe2.appliedEnhInline=' + ('true' if mode == 'typed' else 'false') + '\n'
        if mode == 'verify':
            flags += '-Dnestworld.compatAe2.appliedEnhFixtures=true\n'
        if mode in ['cursorverify', 'transitfx', 'emptycfg', 'chestguard']:
            flags += '-Dnestworld.compatAe2.fixtures=true\n'
        for extra in args.extra_jvm:
            assert extra.startswith('-D') and '\n' not in extra
            flags += extra + '\n'
        (P / 'user_jvm_args.txt').write_text(flags)
        pins = {name: hashlib.sha256((P / 'world/data' / name).read_bytes()).hexdigest()
                for name in json.loads((P / 'comparison-manifest.json').read_text())['pinsSHA256']}
        (phase / 'initial-pins.json').write_text(json.dumps(pins, indent=2))
        log = (phase / 'console.log').open('w')
        process = subprocess.Popen(['unshare', '-n', 'bash', '-c', 'ip link set lo up\nexec "$@"', 'wp3b-test',
                                    'nice', '-n', '10', 'taskset', '-c', '0-7', JAVA,
                                    '@user_jvm_args.txt', '@' + forge_args, 'nogui'], cwd=P,
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        log.close()
        deadline = time.monotonic() + 600
        while True:
            if process.poll() is not None:
                (phase / 'INVALID-startup.txt').write_text('JVM exit code=' + str(process.returncode) + '\n')
                raise RuntimeError('Test startup failed; inspect phase console.log')
            try:
                if 'players online' in rcon('list', timeout=10):
                    break
            except (RuntimeError, subprocess.TimeoutExpired):
                pass
            if time.monotonic() > deadline:
                raise RuntimeError('Test startup timeout')
            time.sleep(5)
        (phase / 'pid.txt').write_text(str(test_pid()))
        print(f'PHASE {mode}: ready, force-loading {len(chunks)} mining claim chunks', flush=True)
        load_commands = [f'execute in jamd:mining run forceload add {x * 16} {z * 16}' for x, z in chunks]
        (phase / 'claim-load.txt').write_text(rcon(*load_commands, timeout=90))
        if args.pre_cmd:
            (phase / 'pre-cmd.txt').write_text(rcon(*args.pre_cmd, timeout=60))
        checked_wait(args.warmup)
        # Verify that forceload requests became actually loaded chunks before any performance collection.
        for attempt in range(args.load_retries):   # forceload can lag the warmup: retry, never measure partially loaded
            loaded = rcon(*[f'execute in jamd:mining if loaded {x * 16} 80 {z * 16}' for x, z in chunks], timeout=90)
            if loaded.count('Test passed') == len(chunks):
                break
            if attempt % 5 == 4:   # re-issue forceload only for the chunks still not loaded (load request lost)
                blocks = loaded.split('> execute in jamd:mining if loaded ')[1:]
                missing = [c for c, b in zip(chunks, blocks) if 'Test passed' not in b]
                if missing and len(blocks) == len(chunks):
                    (phase / 'reload-missing.txt').open('a').write(f'attempt={attempt + 1} missing={missing}\n')
                    rcon(*[f'execute in jamd:mining run forceload add {x * 16} {z * 16}' for x, z in missing], timeout=60)
            checked_wait(5)
        (phase / 'loaded-chunks.txt').write_text(f'attempts={attempt + 1}\n' + loaded)
        if loaded.count('Test passed') != len(chunks):
            raise RuntimeError('Not every mining claim chunk is loaded; performance window rejected')
        if mode == 'cursorverify':
            (phase / 'cursor-before.txt').write_text(rcon('nwcompatae2 bustransit'))
            (phase / 'cursor-setup.txt').write_text(rcon(
                'forceload add -208 -192 -160 -144',
                'nwcompatae2 bustransitfx build import -200 300 -180 1 2 0',
                'nwcompatae2 bustransitfx build importenergy -200 300 -160 1 2 0',
                'nwcompatae2 bustransitfx gen 1 on', timeout=90))
            checked_wait(30)
            rcon('nwcompatae2 bustransitfx gen 1 off')
            checked_wait(10)
            (phase / 'cursor-after.txt').write_text(rcon('forge tps', 'nwcompatae2 bustransit',
                'nwcompatae2 bustransit records', 'nwcompatae2 bustransit grids',
                'nwcompatae2 bustransitfx list', 'nwcompatae2 bustransitfx cursorstatus',
                'nwcompatae2 bustransitfx count 1 plain', 'nwcompatae2 bustransitfx count 2 energy'))
            checked_wait(3)
            (phase / 'cursor-counts.txt').write_text(rcon('nwcompatae2 bustransitfx cstatus 1',
                'nwcompatae2 bustransitfx cstatus 2'))
            stop()
            continue
        if mode == 'transitfx':
            # AE2-GRID-DOMAIN-001 (c) first rig gate (TZ §3″.2): REGION-owned targets (overworld, region #0) and
            # MAIN-owned targets (the_end: unmanaged), import + export, four independent grids for isolation.
            fx = 'nwcompatae2 bustransitfx '
            end = 'execute in minecraft:the_end run '
            log_lines = []
            def step(name, *cmds, wait=0):
                out = rcon(*cmds, timeout=120) if cmds else ''
                log_lines.append(f'=== {name} @{time.strftime("%H:%M:%S")}\n{out}')
                if wait: checked_wait(wait)
            step('setup', 'forceload add -208 -192 -150 -128', end + 'forceload add 192 192 240 256',
                 fx + 'build import -200 300 -180 2 2 0', fx + 'build export -200 300 -150 2 2 20000',
                 end + fx + 'build import 200 100 200 2 2 0', end + fx + 'build export 200 100 230 2 2 20000',
                 # station 5: double chest = non-local import source (model A owner-side import suffix)
                 fx + 'build importchest -200 300 -165 2 2 0',
                 fx + 'gen 1 on', fx + 'gen 2 on', fx + 'gen 3 on', fx + 'gen 4 on', fx + 'gen 5 on', fx + 'list', wait=25)
            step('promoted?', 'nwcompatae2 gridcensus worker', 'nwcompatae2 bustransit', wait=5)
            step('replace-import-REGION', fx + 'replace 1 replace', wait=3)
            step('restore-import-REGION', fx + 'replace 1 restore', wait=3)
            step('replace-export-MAIN', fx + 'replace 4 replace', wait=3)
            step('restore-export-MAIN', fx + 'replace 4 restore', wait=3)
            step('unload-target', 'forceload remove -208 -192 -150 -170', wait=4)
            step('reload-target', 'forceload add -208 -192 -150 -128', wait=6)
            step('owner-change REGION->MAIN', fx + 'pintargets 2 on', wait=5)
            step('owner-change back', fx + 'pintargets 2 off', wait=5)
            step('hold-for-dup', fx + 'hold on', wait=1)
            step('duplicate-delivery', fx + 'dupdeliver 1', fx + 'dupdeliver 2', fx + 'dupdeliver 3', fx + 'dupdeliver 4')
            step('release-hold', fx + 'hold off', wait=5)
            step('inventory-full', fx + 'fill 1', wait=5)
            step('break', fx + 'break 2 1', wait=3)
            step('rebuild', fx + 'rebuild 2 2', wait=10)
            step('mid-status', 'nwcompatae2 gridcensus worker', 'nwcompatae2 bustransit', wait=10)
            step('quiesce', fx + 'gen 1 off', fx + 'gen 2 off', fx + 'gen 3 off', fx + 'gen 4 off', fx + 'gen 5 off',
                 fx + 'pause 2 on', fx + 'pause 4 on', wait=20)
            # owner gate-6 protocol: pause → wait transit drain → snapshot #1 → 5 s → snapshot #2 (both must agree)
            drained = None
            for _ in range(60):
                out = rcon('nwcompatae2 bustransit')
                mo = re.search(r'records completed=\d+ .*?outstanding=(\d+)', out)
                if mo and mo.group(1) == '0':
                    drained = time.strftime('%H:%M:%S')
                    break
                checked_wait(1)
            log_lines.append(f'=== transit-drain @{time.strftime("%H:%M:%S")}\ndrained={drained}')
            for snap in (1, 2):
                step(f'counts-{snap}', fx + 'count 1 a-import-region', fx + 'count 2 a-export-region', fx + 'count 3 b-import-main',
                     fx + 'count 4 b-export-main', fx + 'count 5 c-import-chest', wait=4)
                step(f'cstatus-{snap}', fx + 'cstatus 1', fx + 'cstatus 2', fx + 'cstatus 3', fx + 'cstatus 4', fx + 'cstatus 5',
                     'nwcompatae2 bustransit records', wait=5 if snap == 1 else 0)
            step('final-status', 'nwcompatae2 bustransit', 'nwcompatae2 bustransit grids', 'nwcompatae2 gridcensus worker', 'forge tps')
            (phase / 'transitfx.txt').write_text('\n'.join(log_lines))
            stop()
            continue
        if mode == 'chestguard':
            # MVP-001 storage-bus option (1): Sophisticated handler serialised by P(G) for worker + MAIN + REGION hopper
            cg = 'nwcompatae2 bustransitfx chestguard '
            log_lines = []
            def step(name, *cmds, wait=0):
                out = rcon(*cmds, timeout=120) if cmds else ''
                log_lines.append(f'=== {name} @{time.strftime("%H:%M:%S")}\n{out}')
                if wait: checked_wait(wait)
                return out
            step('setup', 'forceload add -208 -192 -150 -128', cg + 'build -200 300 -140', cg + 'gen on', wait=20)
            step('promoted?', 'nwcompatae2 gridcensus worker', wait=40)
            step('mid', 'nwcompatae2 gridcensus worker', 'nwcompatae2 bustransit', cg + 'cstatus', wait=30)
            step('quiesce', cg + 'gen off', cg + 'pause on', cg + 'hopper off', wait=10)
            for _ in range(60):
                if re.search(r'records completed=\d+ .*?outstanding=0', rcon('nwcompatae2 bustransit')): break
                checked_wait(1)
            inv = []
            for snap in (1, 2):
                step(f'count-{snap}', cg + 'count', wait=4)
                out = step(f'cstatus-{snap}', cg + 'cstatus', wait=5 if snap == 1 else 0)
                mo = re.search(r'invariant\(total-created\+consumed\)=(-?\d+)', out)
                inv.append(mo.group(1) if mo else '?')
            fin = step('final', 'nwcompatae2 gridcensus worker', 'nwcompatae2 bustransit')
            log_lines.append(f'=== VERDICT conservation={inv}')
            (phase / 'chestguard.txt').write_text('\n'.join(log_lines))
            print('VERDICT conservation', inv, flush=True)
            stop()
            continue
        if mode == 'emptycfg':
            # owner 2026-11-12: empty-config export bus in a model-A grid = safe no-op (no refusal, no demotion, no transfer)
            fx = 'nwcompatae2 bustransitfx '
            log_lines = []
            def step(name, *cmds, wait=0):
                out = rcon(*cmds, timeout=120) if cmds else ''
                log_lines.append(f'=== {name} @{time.strftime("%H:%M:%S")}\n{out}')
                if wait: checked_wait(wait)
                return out
            def ledger():
                out = rcon('nwcompatae2 bustransit', 'nwcompatae2 gridcensus worker')
                def g(pat):
                    mo = re.search(pat, out)
                    return mo.group(1) if mo else '-1'
                return {'exportFromNetwork': g(r'export fromNetwork=(\d+)'), 'inlineRefused': g(r'inlineRefused\(must be 0\)=(\d+)'),
                        'demotions': g(r' demotions=(\d+)'), 'promotions': g(r'promotions=(\d+)'), 'emptyCfg': g(r'exportEmptyConfig=(\d+)'),
                        'outstanding': g(r'records completed=\d+ .*?outstanding=(\d+)')}
            step('setup', 'forceload add -208 -192 -150 -128', fx + 'build export -200 300 -150 2 2 20000', fx + 'gen 1 on', wait=25)
            step('promoted?', 'nwcompatae2 gridcensus worker', 'nwcompatae2 bustransit', wait=10)
            l0 = ledger(); log_lines.append(f'=== ledger normal-start {l0}')
            checked_wait(5)
            l1 = ledger(); log_lines.append(f'=== ledger normal-after-5s {l1}')
            step('empty-config', fx + 'pause 1 on', wait=3)              # in-flight records settle
            step('count-empty-a', fx + 'count 1 empty-a', wait=4)
            empty_a = step('cstatus-empty-a', fx + 'cstatus 1')
            l2 = ledger(); log_lines.append(f'=== ledger empty-start {l2}')
            checked_wait(15)                                              # 300 ticks with empty config
            l3 = ledger(); log_lines.append(f'=== ledger empty-after-300t {l3}')
            step('count-empty-b', fx + 'count 1 empty-b', wait=4)
            empty_b = step('cstatus-empty-b', fx + 'cstatus 1')
            step('restore-config', fx + 'pause 1 off', wait=15)
            l4 = ledger(); log_lines.append(f'=== ledger restored-after-300t {l4}')
            step('quiesce', fx + 'gen 1 off', fx + 'pause 1 on', wait=10)
            for _ in range(60):
                if ledger()['outstanding'] == '0': break
                checked_wait(1)
            inv = []
            for snap in (1, 2):
                step(f'count-{snap}', fx + 'count 1 final', wait=4)
                out = step(f'cstatus-{snap}', fx + 'cstatus 1', wait=5 if snap == 1 else 0)
                mo = re.search(r'invariant\(total-created\+consumed\)=(-?\d+)', out)
                inv.append(mo.group(1) if mo else '?')
            l5 = ledger(); log_lines.append(f'=== ledger final {l5}')
            verdict = {
                'normal_exports': int(l1['exportFromNetwork']) > int(l0['exportFromNetwork']),
                # station-local (the ledger is global: other grids export through transit too)
                'empty_transfer_zero': [re.search(r'network=(\d+).*consumed=(\d+)', x).groups() for x in (empty_a, empty_b)][0]
                    == [re.search(r'network=(\d+).*consumed=(\d+)', x).groups() for x in (empty_a, empty_b)][1],
                'empty_cfg_seen': int(l3['emptyCfg']) > int(l2['emptyCfg']),
                'restored_exports': int(l4['exportFromNetwork']) > int(l3['exportFromNetwork']),
                'inlineRefused_zero': l5['inlineRefused'] == '0', 'demotions_zero': l5['demotions'] == '0',
                'outstanding_zero': l5['outstanding'] == '0', 'conservation_snapshots': inv,
                'conservation_zero': inv == ['0', '0']}
            log_lines.append(f'=== VERDICT {verdict}')
            step('final-status', 'nwcompatae2 bustransit', 'nwcompatae2 gridcensus worker')
            (phase / 'emptycfg.txt').write_text('\n'.join(log_lines))
            print('VERDICT', verdict, flush=True)
            stop()
            continue
        if mode == 'verify':
            trace = rcon('nwappliedenh tracedispatch', 'nwappliedenh status', timeout=90)
            (phase / 'dispatch-trace.txt').write_text(trace)
            print(trace, flush=True)
            stop()
            continue  # fixture runs never produce performance measurements
        before_queries = ['forge tps', 'nestworld mspt', 'nestworld status',
                          'execute in jamd:mining run nestworld status']
        if mode != 'none':
            before_queries += ['nwcompatae2 bustransit', 'nwcompatae2 bustransit grids']
        if args.census and mode != 'none':
            before_queries += ['nwcompatae2 gridcensus start', 'nwcompatae2 gridcensus ingress start', 'nwcompatae2 gridcensus chests start']
        if args.status and mode != 'none':
            before_queries += ['nwcompatae2 gridcensus tickreset']
        before_queries += args.before_cmd
        (phase / 'before.txt').write_text(rcon(*before_queries, timeout=60))
        pid = test_pid()
        if not args.no_jfr:
            recording = subprocess.run([JCMD, str(pid), 'JFR.start', 'name=wp3b_comparison', 'settings=profile',
                                        f'duration={args.seconds + 10}s', f'filename={phase / "profile.jfr"}', 'maxsize=96m'],
                                       capture_output=True, text=True, timeout=20)
            (phase / 'jfr-start.txt').write_text(recording.stdout + recording.stderr)
        print(f'PHASE {mode}: measuring {args.seconds}s with neutral JMX collector', flush=True)
        result = subprocess.run(['nsenter', '-t', str(pid), '-n', JAVA, '--add-modules', 'jdk.attach', '-cp', '/tmp',
                                 'CollectServerJmx', str(pid), str(args.seconds), str(phase / 'metrics'), '200'],
                                capture_output=True, text=True, timeout=args.seconds + 60)
        (phase / 'collector.txt').write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('Neutral metrics collection failed; inspect collector.txt')
        queries = ['forge tps', 'nestworld mspt', 'nestworld status',
                   'execute in jamd:mining run nestworld status', 'execute in jamd:mining run forceload query']
        if mode != 'none':
            queries += ['nwcompatae2 status', 'nwcompatae2 bustransit', 'nwcompatae2 bustransit grids', 'nwappliedenh status',
                        'nwcompatae2 bustransit strategies', 'nwcompatae2 bustransit inlinetypes']
        if args.extra_mod:
            queries += ['nwcompatmm', 'nwcompatmm groups']
        if args.census and mode != 'none':
            queries += ['nwcompatae2 gridcensus chests report', 'nwcompatae2 gridcensus grid1', 'nwcompatae2 gridcensus coverage', 'nwcompatae2 gridcensus worker', 'nwcompatae2 gridcensus devices', 'nwcompatae2 gridcensus report', 'nwcompatae2 gridcensus ingress report']
        if args.status and not args.census and mode != 'none':
            queries += ['nwcompatae2 gridcensus worker']          # read-only: domain/tick metrics, ownership, guards
        queries += args.after_cmd
        (phase / 'after.txt').write_text(rcon(*queries, timeout=60))
        if not args.no_jfr:
            checked_wait(12)  # let the fixed-duration JFR flush before JVM shutdown
        stop()
        mining = P / 'world/dimensions/jamd/mining'
        if mining.exists():
            subprocess.run(['rsync', '-a', str(mining) + '/', str(phase / 'final-mining-world') + '/'], check=True)
        print(f'PHASE {mode}: done, world stopped gracefully', flush=True)
    (E / 'completed.txt').write_text('All requested modes completed\n')
    print('EVIDENCE=' + str(E), flush=True)
finally:
    stop()
    if locals().get('server_target') is not None and (E / 'original-server.jar').exists():
        shutil.copy2(E / 'original-server.jar', server_target)
    for target in locals().get('extra_installed', []):
        target.unlink(missing_ok=True)
    if core_target is not None and (E / 'original-core.jar').exists():
        shutil.copy2(E / 'original-core.jar', core_target)
    if fixture_config is not None and (E / 'original-appflux-common.toml').exists():
        shutil.copy2(E / 'original-appflux-common.toml', fixture_config)
    try:
        if lock.read_text().strip() == str(os.getpid()):
            lock.unlink()
    except FileNotFoundError:
        pass
