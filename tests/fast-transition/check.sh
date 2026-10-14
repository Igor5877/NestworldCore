#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 17 -d "$out" \
    "$root/src/main/java/net/nestworld/module/ContentGeneration.java" \
    "$root/src/main/java/net/nestworld/module/LoginBudget.java" \
    "$root/tests/fast-transition/ContractChecks.java"
java -cp "$out" net.nestworld.module.ContractChecks
