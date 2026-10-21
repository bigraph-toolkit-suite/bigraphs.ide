#!/usr/bin/env bash
# Benchmark run: correctness check, JMH for the domain analyzer and the schedules of ScheduledRewritingCoverageAnalyzer,
# the hits-only reference, traced sessions and plots, one log.
#
#   ./run-benchmarks.sh [variant] [cores]
#   ./run-benchmarks.sh final 0,2,4,6
#
# Output (in benchmark-results/<variant>-<timestamp>/):
#   run.log            everything printed by the steps, in order, plus the system settings
#   default.json       the domain analyzer (reference) and the default schedule from scratch
#                      (workset-prefilter-seeded): all benchmarks, complete and partial models
#   schedules.json     every schedule (see SCHEDULES): inspectRoot and coldFirstSession, complete models
#   oracle.json        the hits-only reference (OracleRewritingCoverageAnalyzer: perfect pruning), inspectRoot
#   edits.json         chains of edits + inspections (EditChainBenchmark): from scratch every session (default
#                      schedule) vs. the incremental analyzer (the extension's analyzer: one coverage bigraph
#                      per mode, edited in place and rewritten to its fixpoint), per action and chain length
#   trace/             one traced session per schedule and the oracle (RewritingTraceReport): rounds.csv, calls.csv,
#                      summary.csv; active rules, hits and misses per round and over time, side by side
#   figures/           all plots (benchmark-plots/*.py), if python3 with matplotlib is installed
#
# Models: CoverageTestModels.fullModel, all six trees balanced, 6 / 18 / 42 / 90 POPP nodes for depth 0..3.
# Only some steps: STEPS=edits,plot ./run-benchmarks.sh   (any of check,default,schedules,oracle,edits,trace,plot; default
# all). The plot step plots every *.json and trace/ in the output folder of this run.
# Depths: DEFAULT_DEPTHS / SCHEDULE_DEPTHS / ORACLE_DEPTHS / TRACE_DEPTHS (all 0,1,2,3 by default), EDIT_DEPTHS
# (1,2,3: edits need a decomposition); EDIT_LENGTHS (1,10) and EDIT_ACTIONS (LINK,TYPE,MOVE,NODE,MIXED).
# Every analyzer but the domain reference applies the rules by rewriting, so the run needs the fixed jLibBig
# AgentMatcher (see jlibbig-agentmatcher-fix) and refuses to start without it.
# The *-first schedules need a framework whose matchFirst works (the PureMatchIteratorImpl.FirstMatchOnly fix);
# without it they fall back to enumerating all matches. The log says "framework fixes: firstMatch=...".
# Power off when done: SHUTDOWN=1 ./run-benchmarks.sh  (also after a failed step, never after Ctrl-C;
# 60 s grace period in which Ctrl-C cancels the shutdown).
# Rough duration on a desktop: 4-5 hours, most of it the schedules without prefilter at 90 nodes and the
# from-scratch edit chains at 90 nodes.
set -euo pipefail

VARIANT="${1:-final}"
CORES="${2:-0,2,4,6}"            # four distinct physical cores (no SMT siblings); adjust to your CPU
DEFAULT_DEPTHS="${DEFAULT_DEPTHS:-0,1,2,3}"
SCHEDULE_DEPTHS="${SCHEDULE_DEPTHS:-0,1,2,3}"
ORACLE_DEPTHS="${ORACLE_DEPTHS:-0,1,2,3}"
TRACE_DEPTHS="${TRACE_DEPTHS:-0,1,2,3}"
EDIT_DEPTHS="${EDIT_DEPTHS:-1,2,3}"
EDIT_LENGTHS="${EDIT_LENGTHS:-1,10}"
EDIT_ACTIONS="${EDIT_ACTIONS:-LINK,TYPE,MOVE,NODE,MIXED}"
STEPS=",${STEPS:-check,default,schedules,oracle,edits,trace,plot},"
step() { [[ "$STEPS" == *",$1,"* ]]; }
DEFAULT="domain,workset-prefilter-seeded"
SCHEDULES="workset,workset-seeded,guards-first,prefilter,workset-guards-seeded-first,workset-prefilter-seeded,workset-guards-seeded-first-prefilter"
OUT="benchmark-results/${VARIANT}-$(date +%Y%m%d-%H%M%S)"

SHUTDOWN="${SHUTDOWN:-0}"
trap 'echo; echo "interrupted, no shutdown"; trap - EXIT; exit 130' INT TERM
finish() {
  local code=$?
  [ "$SHUTDOWN" = 1 ] || exit $code
  trap 'echo; echo "shutdown cancelled"; exit $code' INT TERM
  echo "exit code $code; powering off in 60 s (Ctrl-C cancels)"
  sleep 60
  systemctl poweroff
}
trap finish EXIT
mkdir -p "$OUT"

# Compile main and test classes (incl. the JMH-generated benchmark list) and write the classpath.
# Always runs, so edits since the last build are never benchmarked stale.
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
for c in CoverageBenchmark CoverageBenchmarkCheck RewritingTraceReport; do
  if [ ! -f "target/test-classes/org/eclipse/glsp/example/bigraph/extension/popp/coverage/$c.class" ]; then
    echo "missing $c.class after mvn test-compile" >&2; exit 1
  fi
done
if [ ! -f target/test-classes/META-INF/BenchmarkList ]; then
  echo "JMH BenchmarkList missing: the jmh-generator-annprocess path in pom.xml did not run" >&2; exit 1
fi
CP="target/classes:target/test-classes:$(cat target/cp.txt)"
RUN="taskset -c $CORES java"
JMH="$RUN -cp $CP org.openjdk.jmh.Main"
CAPS="$(java -cp "$CP" org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageBenchmarkCheck --capabilities 2>/dev/null | tail -1)"
if [[ "$CAPS" != *"rewrite=true"* ]]; then
  echo "the jLibBig AgentMatcher on the classpath splits links ($CAPS); install jlibbig-agentmatcher-fix first" >&2; exit 1
fi
EDITORS="workset-prefilter-seeded,incremental"

{
  echo "=== $(date -Is)  variant=$VARIANT  cores=$CORES  shutdown=$SHUTDOWN  steps=$STEPS"
  echo "--- git"
  git rev-parse --short HEAD 2>/dev/null || true
  git status --short 2>/dev/null | head -20 || true
  echo "--- system"
  java -version 2>&1
  uname -r
  lscpu 2>/dev/null | grep -E "Model name|^CPU\(s\)" || true
  cpupower frequency-info -p 2>/dev/null | tail -2 || true
  cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null | sed 's/^/governor: /' || true
  echo "--- framework fixes: $CAPS"
  echo

  if step check; then
  echo "=== check: every schedule and the oracle vs Domain, all coverage modes, depth 0..2"
  $RUN -Dvariant="$VARIANT" -cp "$CP" \
    org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageBenchmarkCheck 2 "$SCHEDULES,oracle"
  $RUN -cp "$CP" org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageBenchmarkCheck --edits incremental
  echo
  fi

  if step default; then
  echo "=== default: JMH $DEFAULT, all benchmarks, depth $DEFAULT_DEPTHS"
  $JMH CoverageBenchmark -rf json -rff "$OUT/default.json" \
    -p analyzer="$DEFAULT" -p depth="$DEFAULT_DEPTHS" -p complete=true,false \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo
  fi

  if step schedules; then
  echo "=== schedules: JMH $SCHEDULES, depth $SCHEDULE_DEPTHS"
  # seconds per session for the schedules without prefilter: only the session benchmarks (inspectRoot, cold start)
  $JMH 'CoverageBenchmark\.(inspectRoot|coldFirstSession)' -rf json -rff "$OUT/schedules.json" \
    -p analyzer="$SCHEDULES" -p depth="$SCHEDULE_DEPTHS" -p complete=true \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo
  fi

  if step oracle; then
  echo "=== oracle: JMH hits-only reference (perfect pruning), depth $ORACLE_DEPTHS"
  # warm sessions only: recording the oracle's schedule in the setup already warms the JVM, so no cold start
  $JMH 'CoverageBenchmark\.inspectRoot' -rf json -rff "$OUT/oracle.json" \
    -p analyzer=oracle -p depth="$ORACLE_DEPTHS" -p complete=true \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo
  fi

  if step edits; then
  echo "=== edits: JMH edit chains ($EDITORS), depth $EDIT_DEPTHS, lengths $EDIT_LENGTHS, actions $EDIT_ACTIONS"
  $JMH EditChainBenchmark -rf json -rff "$OUT/edits.json" \
    -p analyzer="$EDITORS" -p depth="$EDIT_DEPTHS" -p length="$EDIT_LENGTHS" -p action="$EDIT_ACTIONS" \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo
  fi

  if step trace; then
  echo "=== trace: one traced session per schedule and the oracle, depth $TRACE_DEPTHS"
  $RUN -cp "$CP" org.eclipse.glsp.example.bigraph.extension.popp.coverage.RewritingTraceReport \
    "$OUT/trace" "$TRACE_DEPTHS" VERIFY "$SCHEDULES,oracle"
  echo
  fi

  if step plot; then
  echo "=== plot"
  if python3 -c "import matplotlib" 2>/dev/null; then
    if ls "$OUT"/*.json >/dev/null 2>&1; then python3 benchmark-plots/plot_benchmarks.py "$OUT" "$OUT/figures"; fi
    if [ -f "$OUT/trace/rounds.csv" ]; then python3 benchmark-plots/plot_traces.py "$OUT/trace" "$OUT/figures"; fi
  else
    echo "python3 with matplotlib not found; plot later with benchmark-plots/plot_benchmarks.py and plot_traces.py"
  fi
  echo
  fi
  echo "=== done $(date -Is)"
} 2>&1 | tee "$OUT/run.log"

echo "Log:     $OUT/run.log"
echo "Results: $OUT/*.json, $OUT/trace/, $OUT/figures/"
