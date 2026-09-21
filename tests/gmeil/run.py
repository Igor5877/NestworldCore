#!/usr/bin/env python3
"""Compile the loader-independent Phase 1 gateway and run its semantics regression."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
sources = sorted((root / 'src/main/java/net/nestworld/gmeil').glob('*.java'))
sources.append(root / 'tests/gmeil/GatewayRegression.java')
with tempfile.TemporaryDirectory(prefix='nestworld-gmeil-test-') as output:
    subprocess.run(['javac', '-d', output, *map(str, sources)], check=True)
    subprocess.run(['java', '-ea', '-cp', output, 'GatewayRegression'], check=True)
