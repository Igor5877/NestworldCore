#!/bin/bash
# PERF-1A rig preflight. Run ON THE LXD HOST, read-only: changes nothing.
# Usage: host-preflight.sh <benchmark-container> <bench-cpu-list> <harness-cpu-list>
#   e.g. host-preflight.sh nestworld-dev 2-17 1
# Checks:
#  - the benchmark container's limits.cpu is an explicit list (not a count) covering bench + harness;
#  - every bench CPU is on its own physical core and the harness CPUs are on other cores;
#  - no other running container may use a bench CPU or its SMT sibling;
#  - SMT siblings of bench CPUs are not in any container's cpuset (incl. the benchmark's own).
set -u
CT=${1:?container}; BENCH=${2:?bench cpus}; HARN=${3:?harness cpus}
expand() { python3 -c "import sys
o=[]
for p in sys.argv[1].split(','):
    a,_,b=p.partition('-'); o+=range(int(a),int(b or a)+1)
print(' '.join(map(str,o)))" "$1"; }
sib() { python3 -c "import sys
o=[]
for p in open('/sys/devices/system/cpu/cpu%s/topology/thread_siblings_list'%sys.argv[1]).read().strip().split(','):
    a,_,b=p.partition('-'); o+=range(int(a),int(b or a)+1)
print(' '.join(map(str,o)))" "$1"; }
fail=0; say() { echo "$1"; }; bad() { echo "FAIL: $1"; fail=1; }

lim=$(lxc config get "$CT" limits.cpu)
say "benchmark container $CT limits.cpu='$lim'"
case "$lim" in ''|*[!0-9,-]*) bad "limits.cpu is empty or not a CPU list";; esac
[[ "$lim" =~ ^[0-9]+$ ]] && bad "limits.cpu='$lim' is a COUNT: the host may move the container between cores"
allowed=" $(expand "${lim:-0}") "
bench=$(expand "$BENCH"); harn=$(expand "$HARN")
declare -A core_seen; protect=""
for c in $bench; do
  [[ "$allowed" == *" $c "* ]] || bad "bench CPU $c not in limits.cpu"
  core=$(cat /sys/devices/system/cpu/cpu$c/topology/core_id)
  [ -n "${core_seen[$core]:-}" ] && bad "bench CPUs ${core_seen[$core]} and $c share physical core $core"
  core_seen[$core]=$c
  for s in $(sib $c); do protect="$protect $s"; [ "$s" != "$c" ] && [[ "$allowed" == *" $s "* ]] && bad "SMT sibling $s of bench CPU $c is in the benchmark container's cpuset"; done
done
for h in $harn; do
  [[ "$allowed" == *" $h "* ]] || bad "harness CPU $h not in limits.cpu"
  [[ " $protect " == *" $h "* ]] && bad "harness CPU $h is a bench CPU or its sibling"
done
say "protected CPUs (bench + their SMT siblings):$protect"
for other in $(lxc list --format csv -c n,s | awk -F, '$2=="RUNNING"{print $1}'); do
  [ "$other" = "$CT" ] && continue
  ol=$(lxc config get "$other" limits.cpu)
  if [ -z "$ol" ] || [[ "$ol" =~ ^[0-9]+$ ]]; then
    bad "running container $other has limits.cpu='${ol:-unset}' (unpinned: may land on protected CPUs)"; continue
  fi
  for c in $(expand "$ol"); do [[ " $protect " == *" $c "* ]] && bad "running container $other may use protected CPU $c"; done
done
[ $fail = 0 ] && echo "PREFLIGHT PASS" || { echo "PREFLIGHT FAIL"; exit 1; }
