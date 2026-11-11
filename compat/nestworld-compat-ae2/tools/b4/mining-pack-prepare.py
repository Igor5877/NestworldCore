#!/usr/bin/env python3
"""Prepare an isolated, repeatable production-world comparison. Never writes to live."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import zipfile

LIVE = Path('/root/project-infinity')
BASE = Path('/root/airis-2026-10-25')
TEST = BASE / 'WP3B-MINING-20261111'
SNAPSHOT = BASE / 'wp3b-mining-snapshot-20261111-1330'
ARCHIVE = LIVE / 'backups/2026-11-11_13-30-0.zip'
TEAM = '6fc4aa03-aab2-432e-b694-63d4d2e30a9a'

if TEST.exists() or SNAPSHOT.exists():
    raise SystemExit('Refusing to overwrite an existing test/snapshot directory')
TEST.mkdir(mode=0o700)
SNAPSHOT.mkdir(mode=0o700)
for directory in ('mods', 'config', 'defaultconfigs', 'kubejs', 'libraries', 'scripts'):
    source = LIVE / directory
    if source.exists():
        subprocess.run(['rsync', '-a', str(source) + '/', str(TEST / directory) + '/'], check=True)
for name in ('run.sh', 'eula.txt', 'server.properties', 'user_jvm_args.txt', '.rcon-pass', 'ops.json', 'whitelist.json'):
    if (LIVE / name).exists():
        shutil.copy2(LIVE / name, TEST / name)
(TEST / 'artifacts').mkdir()
for addon in (TEST / 'mods').glob('nestworld-compat-ae2-*.jar'):
    shutil.move(str(addon), TEST / 'artifacts' / ('original-' + addon.name))

# A completed FTB snapshot avoids a torn hot copy and needs no live save/restart command.
with zipfile.ZipFile(ARCHIVE) as archive:
    for member in archive.infolist():
        path = Path(member.filename)
        if not path.parts or path.parts[0] != 'world' or '..' in path.parts or path.is_absolute():
            raise SystemExit('Unexpected archive member; extraction stopped')
        archive.extract(member, SNAPSHOT)
subprocess.run(['rsync', '-a', str(SNAPSHOT / 'world') + '/', str(TEST / 'world') + '/'], check=True)

claims = (SNAPSHOT / 'world/ftbchunks' / (TEAM + '.snbt')).read_text()
match = re.search(r'"jamd:mining"\s*:\s*\[(.*?)\]', claims, re.S)
if not match:
    raise SystemExit('Mining claim absent in snapshot')
chunks = [(int(x), int(z)) for x, z in re.findall(r'\bx:\s*(-?\d+)\s*,\s*z:\s*(-?\d+)', match[1])]
(TEST / 'mining-chunks.json').write_text(json.dumps(chunks))

# Only the private test configuration changes. All comparison modes reuse these same values.
properties = TEST / 'server.properties'
updates = {'server-ip': '127.0.0.1', 'enable-rcon': 'true', 'enable-query': 'false',
           'enable-jmx-monitoring': 'true', 'max-tick-time': '120000'}
lines = []
for line in properties.read_text().splitlines():
    key = line.split('=', 1)[0]
    if key in updates:
        line = key + '=' + updates.pop(key)
    lines.append(line)
lines.extend(key + '=' + value for key, value in updates.items())
properties.write_text('\n'.join(lines) + '\n')

args = (TEST / 'user_jvm_args.txt').read_text().replace(str(LIVE), str(TEST))
lines = [line for line in args.splitlines()
         if not line.strip().startswith(('-Xmx', '-Xms', '-Dnestworld.compatAe2.fixtures=',
                '-Dnestworld.compatAe2.busTransit=', '-Dnestworld.compatAe2.appliedEnhInline=',
                '-Dnestworld.compatAe2.appliedEnhFixtures='))]
lines += ['-Xms4G', '-Xmx8G', '-Dcom.sun.management.jmxremote',
          '-Dcom.sun.management.jmxremote.local.only=true', '-Dnestworld.compatAe2.fixtures=false',
          '-Dnestworld.compatAe2.appliedEnhFixtures=false', '-Dnestworld.compatAe2.appliedEnhInline=false']
(TEST / 'base-jvm-args.txt').write_text('\n'.join(lines) + '\n')
backup_config = TEST / 'config/ftbbackups2.json'
if backup_config.exists():
    backup_config.write_text(re.sub(r'("enabled"\s*:\s*)true', r'\1false', backup_config.read_text(), count=1))

pins = {}
for name in ('nestworld-be-pins.txt', 'nestworld-pinned-mods.txt', 'nestworld-pins.txt', 'nestworld-pin-reasons.txt'):
    pins[name] = hashlib.sha256((SNAPSHOT / 'world/data' / name).read_bytes()).hexdigest()
assert 'ae2' in (SNAPSHOT / 'world/data/nestworld-pinned-mods.txt').read_text().splitlines()
(TEST / 'comparison-manifest.json').write_text(json.dumps({'snapshot': str(ARCHIVE), 'snapshotDirectory': str(SNAPSHOT),
    'dimension': 'jamd:mining', 'team': TEAM, 'chunks': chunks, 'heap': '4G/8G', 'pinsSHA256': pins,
    'network': 'new Linux network namespace, loopback only', 'automaticTestBackups': False}, indent=2))
print('Prepared ' + str(TEST))
print('Frozen snapshot: ' + str(ARCHIVE))
print('Mining chunks: ' + str(len(chunks)))
