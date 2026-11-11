#!/usr/bin/env python3
"""Read-only RCON list gate; only an explicitly supplied player may be ignored."""
import re
import sys

ignored = sys.argv[1].casefold() if len(sys.argv) > 1 else ""
raw = sys.stdin.read()
match = re.search(r"There are (\d+) of a max of \d+ players online:\s*([^\r\n]*)", raw)
if not match:
    sys.exit(1)
players = [p.strip() for p in match[2].split(",") if p.strip()]
sys.exit(0 if int(match[1]) == len(players) and all(ignored and p.casefold() == ignored for p in players) else 1)
