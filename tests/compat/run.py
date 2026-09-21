#!/usr/bin/env python3
"""Verify the user-authorized Canary exception using only synthetic temp files."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='nestworld-canary-test-') as temp:
    output = Path(temp) / 'classes'
    output.mkdir()
    subprocess.run(['javac', '-d', str(output),
                    str(root / 'fmlloader/src/main/java/net/minecraftforge/fml/loading/NestworldCanaryConfigGuard.java'),
                    str(root / 'tests/compat/CanaryConfigGuardRegression.java')], check=True)
    subprocess.run(['java', '-ea', '-cp', str(output), 'CanaryConfigGuardRegression', temp], check=True)
