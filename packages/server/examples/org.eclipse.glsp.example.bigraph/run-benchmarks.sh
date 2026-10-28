#!/usr/bin/env bash
# Benchmarks the coverage analysis on the generic rewriter: every rewriter variant (RewriterVariants), from the baseline
# without any analysis to the analyzer's default, each adding one optimization:
#   baseline      every rule enables every rule, first in first out, no guards
#   worklist      + the generic overlap analysis (a rewrite queues only the rules it can enable)
#   topological   + the worklist ordered by the strongly connected components of the dependencies
#   guards        + the receiver guards, matched and kept until an enabling rewrite
#   counted       + the guards counted (the analyzer's default)
# and the hits-only bound: the counted variant's hitting matcher calls and their rewrites alone (perfect pruning).
#
#   ./run-benchmarks.sh [name] [cores]
#   SHUTDOWN=1 ./run-benchmarks.sh final 0,2,4,6
#
# Steps (STEPS, default all, in this order):
#   roundup   CoverageBenchmark: every variant from scratch (all five modes) and incremental (EDITS MIXED edits,
#             EDIT_MODE) on the benchmark models of depth DEPTHS (6 / 18 / 42 / 90 POPP nodes), median of REPS runs
#             after a warm-up. Every variant is checked against the domain analyzer on every model first.
#   trace     CoverageTraceRound: every matcher call of every variant, from scratch and after each of TRACE_EDITS
#             edits, on the model of depth TRACE_DEPTH. The edits are those of the earlier trace report (seeds
#             4711 + 2 + k after two untraced edits), the figure shows the one of median reach, as before.
#   plot      benchmark-plots/plot_roundup.py and plot_traces.py (needs python3 with matplotlib)
#
# Output (in benchmark-results/<name>-<timestamp>/):
#   run.log                 everything printed, plus the system settings
#   variants.csv            per variant: build time (its static analysis), rules, guards, rules queued per rewrite
#   roundup.csv             per variant, depth, phase and mode or edit: median time, matcher calls
#   summary.csv             per variant, phase and model size: total time and speed-up over the baseline
#   trace/calls.csv         every matcher call of the traced sessions
#   trace/sessions.csv      one row per traced session
#   figures/roundup.*       each variant per model size in EDIT_MODE, from scratch and after one edit, with the
#                           hits-only bound (the default variant's hitting calls alone) and the response-time line
#   figures/roundup-calls.* where the matcher calls go on the 42-node model (hits, misses, guard calls)
#   figures/trace-timeline.*   every matcher call of one session per variant, from scratch and after one edit
#
# Rewriting needs the fixed jLibBig AgentMatcher (see the patched framework jars), the run stops without it. Without a
# framework whose matchFirst works, every matcher call enumerates all matches. The log says which fixes are present.
# Power off when done: SHUTDOWN=1 (also after a failed step, never after Ctrl-C, 60 s grace period).
set -euo pipefail

NAME="${1:-roundup}"
CORES="${2:-0,2,4,6}"            # distinct physical cores (no SMT siblings), adjust to your CPU
DEPTHS="${DEPTHS:-0,1,2,3}"
VARIANTS="${VARIANTS:-all}"
REPS="${REPS:-3}"
EDITS="${EDITS:-12}"
EDIT_MODE="${EDIT_MODE:-VERIFY}"
TRACE_DEPTH="${TRACE_DEPTH:-2}"
TRACE_EDITS="${TRACE_EDITS:-6}"     # the edits of the earlier trace report, it traced the one of median reach
STEPS=",${STEPS:-roundup,trace,plot},"
step() { [[ "$STEPS" == *",$1,"* ]]; }
OUT="benchmark-results/${NAME}-$(date +%Y%m%d-%H%M%S)"

SHUTDOWN="${SHUTDOWN:-0}"
trap 'echo; echo "interrupted, no shutdown"; trap - EXIT; exit 130' INT TERM
finish() {
  local code=$?
  [ "$SHUTDOWN" = 1 ] || exit $code
  trap 'echo; echo "shutdown cancelled"; exit $code' INT TERM
  echo "exit code $code, powering off in 60 s (Ctrl-C cancels)"
  sleep 60
  systemctl poweroff
}
trap finish EXIT
mkdir -p "$OUT"

# Compile main and test classes and write the classpath. Always runs, so edits are never benchmarked stale.
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
CP="target/classes:target/test-classes:$(cat target/cp.txt)"
RUN="taskset -c $CORES java"
PKG=org.eclipse.glsp.example.bigraph.extension.popp.coverage

{
  echo "=== $(date -Is)  name=$NAME  cores=$CORES  shutdown=$SHUTDOWN  steps=$STEPS"
  echo "--- git"
  git rev-parse --short HEAD 2>/dev/null || true
  git status --short 2>/dev/null | head -20 || true
  echo "--- system"
  java -version 2>&1
  uname -r
  lscpu 2>/dev/null | grep -E "Model name|^CPU\(s\)" || true
  cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null | sed 's/^/governor: /' || true
  echo

  if step roundup; then
  echo "=== roundup: variants $VARIANTS, depths $DEPTHS, $REPS reps, $EDITS edits in $EDIT_MODE"
  $RUN -cp "$CP" $PKG.CoverageBenchmark "$OUT" "$DEPTHS" "$VARIANTS" "$REPS" "$EDITS" "$EDIT_MODE"
  echo
  fi

  if step trace; then
  echo "=== trace: variants $VARIANTS, depth $TRACE_DEPTH, $TRACE_EDITS edits in $EDIT_MODE"
  $RUN -cp "$CP" $PKG.CoverageTraceRound "$OUT/trace" "$TRACE_DEPTH" "$EDIT_MODE" "$VARIANTS" "$TRACE_EDITS"
  echo
  fi

  if step plot; then
  echo "=== plot"
  if python3 -c "import matplotlib" 2>/dev/null; then
    [ -f "$OUT/roundup.csv" ] && python3 benchmark-plots/plot_roundup.py "$OUT" "$OUT/figures" --mode "$EDIT_MODE"
    [ -f "$OUT/trace/calls.csv" ] && python3 benchmark-plots/plot_traces.py "$OUT/trace" "$OUT/figures"
  else
    echo "python3 with matplotlib not found, no figures (the data is complete)"
  fi
  fi
} 2>&1 | tee "$OUT/run.log"
echo "Results: $OUT"
