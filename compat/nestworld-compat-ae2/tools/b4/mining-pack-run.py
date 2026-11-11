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
parser.add_argument('--seconds', type=int, default=120)
parser.add_argument('--warmup', type=int, default=90)
parser.add_argument('--modes', nargs='+', choices=['none', 'off', 'on', 'typed', 'verify'], default=['none', 'off', 'on'])
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
chunks = json.loads((P / 'mining-chunks.json').read_text())
properties = (P / 'server.properties').read_text()
port = int(re.search(r'^rcon.port=(\d+)$', properties, re.M)[1])
forge_args = re.search(r'@(\S*unix_args.txt)', (P / 'run.sh').read_text())[1]
lock = P / '.comparison.lock'
with lock.open('x') as f:
    f.write(str(os.getpid()))
process = None
phase = None


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
    for index, mode in enumerate(args.modes, 1):
        phase = E / f'{index}-{mode}'
        phase.mkdir()
        print(f'PHASE {mode}: restore frozen world', flush=True)
        subprocess.run(['rsync', '-a', '--delete', str(S) + '/', str(P / 'world') + '/'], check=True)
        for addon in (P / 'mods').glob('nestworld-compat-ae2-*.jar'):
            addon.unlink()  # owned test copy only; the live path is never targeted
        if mode != 'none':
            shutil.copy2(frozen, P / 'mods/nestworld-compat-ae2-0.9.2-all.jar')
        flags = (P / 'base-jvm-args.txt').read_text()
        flags += '-Djava.rmi.server.hostname=127.0.0.1\n'
        flags += '-Dnestworld.compatAe2.busTransit=' + ('true' if mode in ['on', 'typed'] else 'false') + '\n'
        flags += '-Dnestworld.compatAe2.appliedEnhInline=' + ('true' if mode == 'typed' else 'false') + '\n'
        if mode == 'verify':
            flags += '-Dnestworld.compatAe2.appliedEnhFixtures=true\n'
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
        checked_wait(args.warmup)
        if mode == 'verify':
            trace = rcon('nwappliedenh tracedispatch', 'nwappliedenh status', timeout=90)
            (phase / 'dispatch-trace.txt').write_text(trace)
            print(trace, flush=True)
            stop()
            continue  # fixture runs never produce performance measurements
        (phase / 'before.txt').write_text(rcon('forge tps', 'nestworld mspt', 'nestworld status'))
        pid = test_pid()
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
        queries = ['forge tps', 'nestworld mspt', 'nestworld status', 'execute in jamd:mining run forceload query']
        if mode != 'none':
            queries += ['nwcompatae2 status', 'nwcompatae2 bustransit', 'nwcompatae2 bustransit grids', 'nwappliedenh status']
        (phase / 'after.txt').write_text(rcon(*queries, timeout=60))
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
    try:
        if lock.read_text().strip() == str(os.getpid()):
            lock.unlink()
    except FileNotFoundError:
        pass
