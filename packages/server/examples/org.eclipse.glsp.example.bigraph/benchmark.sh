#!/usr/bin/env bash
# Complete rebenchmark of all coverage analyzers, side by side:
#   domain | rewriting-naive | rewriting-census | rewriting | fragment-rounds ... fragment | fragment-memo
#
# Run from the module directory (packages/server/examples/org.eclipse.glsp.example.bigraph):
#   bash benchmark.sh            # everything (several hours, see the estimates per group below)
#   bash benchmark.sh <group>   # only one group: domain | rewriting | naive | fragment | cold | plots
#   SMOKE=1 bash benchmark.sh    # minutes instead of hours: 1 fork, 1 short iteration, to try the whole pipeline
#
# Every group writes its own benchmark-results/<group>-<timestamp>.json/.log. Before any measurement the script
# checks every analyzer against the Domain analyzer and stops if one differs. A file meta-<timestamp>.txt records
# commit, working-tree state, CPU, kernel and JVM.
set -euo pipefail

CPUS="${CPUS:-0,2,4,6}" # taskset CPU list, override with CPUS=... bash benchmark.sh
OUT=benchmark-results
mkdir -p "$OUT"
STAMP=$(date +%Y%m%d-%H%M%S)
ONLY="${1:-all}"

want() { [[ "$ONLY" == all || "$ONLY" == "$1" ]]; }

if [[ -z "${CP:-}" ]]; then
  echo "== build"
  mvn -q clean test-compile
  mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
  CP="target/classes:target/test-classes:$(cat target/cp.txt)"
fi
# SMOKE=1: replace every group's iteration settings (JMH rejects an option given twice, so the group's own
# -f/-wi/-i/-r/-to are dropped first, see jmh())
SMOKE_ARGS=()
[[ -n "${SMOKE:-}" ]] && SMOKE_ARGS=(-f 1 -wi 0 -i 1 -r 200ms -to 5m)
PKG=org.eclipse.glsp.example.bigraph.extension.popp.coverage

{
  echo "timestamp: $STAMP"
  echo "commit: $(git rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "working tree:"; git status --short 2>/dev/null | sed 's/^/  /' || true
  echo "cpu list: $CPUS"
  lscpu 2>/dev/null | grep -E 'Model name|^CPU\(s\)|Thread|MHz' || true
  echo "kernel: $(uname -r)"
  java -version 2>&1 | sed 's/^/jvm: /'
  echo "governor: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
} > "$OUT/meta-$STAMP.txt"
echo "metadata: $OUT/meta-$STAMP.txt"

# ---- correctness gates --------------------------------------------------------------------------------------
check() { # check <analyzer> <maxDepth>
  local out bad
  out=$(java -cp "$CP" -Danalyzer="$1" "$PKG.CoverageBenchmarkCheck" "$2" 2>&1 | grep '^\[check\]')
  echo "$out" | head -1
  bad=$(echo "$out" | grep 'depth=' | grep -v ' 0 of ' || true)
  if [[ -n "$bad" ]]; then echo "CHECK FAILED for $1:"; echo "$bad"; exit 1; fi
  echo "  $1: 0 differences up to depth $2"
}
random_check() { # random_check <analyzers,comma> <models> <maxNodes>
  local out
  out=$(java -cp "$CP" "$PKG.CoverageRandomModels" "$1" "$2" "$3" 2>&1 | grep '^\[random\]')
  echo "$out"
  if echo "$out" | grep -E 'mismatching' | grep -vq ': 0 mismatching'; then echo "RANDOM CHECK FAILED"; exit 1; fi
}

echo "== correctness"
want fragment && for a in fragment-rounds fragment-pruned fragment-worklist fragment-slim fragment fragment-memo; do check "$a" 4; done
want fragment && random_check fragment-rounds,fragment-pruned,fragment-worklist,fragment-slim,fragment,fragment-memo 500 40
want rewriting && for a in rewriting rewriting-census; do check "$a" 3; done
want naive && check rewriting-naive 2

jmh() {
  local group=$1 include=$2; shift 2
  local ts; ts=$(date +%Y%m%d-%H%M%S)
  local runner=(java); command -v taskset >/dev/null && runner=(taskset -c "$CPUS" java)
  local args=("$@")
  if [[ ${#SMOKE_ARGS[@]} -gt 0 ]]; then
    args=(); while [[ $# -gt 0 ]]; do
      case "$1" in -f|-wi|-i|-r|-to) shift 2 ;; *) args+=("$1"); shift ;; esac
    done
  fi
  echo "== $group ($ts)"
  "${runner[@]}" -cp "$CP" org.openjdk.jmh.Main "$include" "${args[@]}" "${SMOKE_ARGS[@]}" \
    -jvmArgsAppend "-Dvariant=$group" -rf json -rff "$OUT/$group-$ts.json" > "$OUT/$group-$ts.log" 2>&1 \
    || echo "WARNING: JMH exited with an error for $group, see $OUT/$group-$ts.log"
  grep -E '^CoverageBenchmark|^Benchmark|<failure>|Exception' "$OUT/$group-$ts.log" | head -60 || true
}
ALL='.*CoverageBenchmark\..*'
STEADY='.*CoverageBenchmark\.(inspectRoot|editThenInspect)$'
COLD='.*CoverageBenchmark\.coldFirstSession$'

# Steady-state benchmarks use the class defaults (3 forks, 5 x 2 s warm-up, 10 x 2 s measurement) unless noted.
# inspectAllNodes is left out for the rewriting analyzers: they compute the whole session on every inspection,
# so it costs the same as inspectRoot (that was measured and is in the notes).

# Domain: all five benchmarks incl. editOnly (the edit baseline) and coldFirstSession. ~30 min.
want domain && jmh domain "$ALL" -p analyzer=domain -p depth=1,2,3,4

# Fragment analyzer, all levers, steady state. 6 variants x 4 depths x 2 benchmarks x 3 forks x 30 s ≈ 2.4 h.
want fragment && jmh fragment "$STEADY" \
  -p analyzer=fragment-rounds,fragment-pruned,fragment-worklist,fragment-slim,fragment,fragment-memo -p depth=1,2,3,4

# Full-agent rewriting with pruning (census only / census + link guard). Seconds per operation at depth 3-4, so fewer
# iterations: 3 forks x (3 warm-up + 5 measurement) x at least 1 operation. Roughly 1-2 h for both.
want rewriting && jmh rewriting "$STEADY" -p analyzer=rewriting-census,rewriting -p depth=1,2,3,4 -wi 3 -i 5 -to 30m

# Naive full-agent rewriting (no pruning): every one of the 303 rules in every round. Seconds per operation already
# at depth 1-2; depth 3+ would take minutes per operation. 2 forks x (2 + 5) iterations. Roughly 30-60 min.
want naive && jmh naive "$STEADY" -p analyzer=rewriting-naive -p depth=1,2 -f 2 -wi 2 -i 5 -to 30m

# Cold start (first session in a fresh JVM, 20 forks each), for everything except naive at depth 3+. ~30-45 min.
want cold && jmh cold "$COLD" \
  -p analyzer=rewriting-census,rewriting,fragment-rounds,fragment-pruned,fragment-worklist,fragment-slim,fragment,fragment-memo -p depth=1,2,3,4
want cold && jmh cold-naive "$COLD" -p analyzer=rewriting-naive -p depth=1,2

# ---- plots (only the files of this run) -----------------------------------------------------------------------
if want plots || [[ "$ONLY" == all ]]; then
  if [[ -f plot_benchmarks.py ]]; then
    FILES=$(ls -t "$OUT"/{domain,fragment,rewriting,naive,cold,cold-naive}-20*.json 2>/dev/null \
      | awk -F/ '{split($NF,a,"-20"); if (!seen[a[1]]++) print}' || true)
    echo "== plots from:"; echo "$FILES"
    python3 plot_benchmarks.py $FILES -o "$OUT/plots-$STAMP" --no-titles
    python3 plot_benchmarks.py $FILES -o "$OUT/plots-$STAMP-png" --format png
  else
    echo "plot_benchmarks.py not in the module directory, skipping plots"
  fi
fi
echo "done: $STAMP"
