#!/usr/bin/env bash
# The final benchmark run: correctness check, every schedule of ScheduledRewritingCoverageAnalyzer from scratch and
# incremental with the hits-only references, the edit chains, a per-phase breakdown of the edit steps, the worklist
# under different rule sets and encodings, traced sessions and plots, one log.
#
#   ./run-benchmarks.sh [variant] [cores]
#   SHUTDOWN=1 ./run-benchmarks.sh final 0,2,4,6
#
# Schedules (SCHEDULES), each from scratch (<schedule>) and incremental (incremental-<schedule>):
#   rounds                                 no optimisation: every rule every round (the baseline)
#   worklist                                the rule worklist (overlap analysis), every rule queued at the start
#   worklist-seeded                         + seed (from scratch: the link rules; incremental: the rules the edit can
#                                          have enabled, see IncrementalCoverageAnalyzer)
#   worklist-guards-seeded-first            + guard patterns (matchFirst): the default; incremental-worklist-guards-
#                                          seeded-first is the extension's analyzer, also called "incremental"
# References (perfect pruning: only the matcher calls that hit):
#   oracle                                 from scratch: the default schedule's hits, replayed (JMH, schedules step)
#   bound_ms                               incremental: computed per edit step from a traced step (breakdown step;
#                                          a replay would fall into the measured step itself)
# Rules: threshold gate rules and the overlap analysis (the defaults since this run). The encodings step compares
# them with enumerated gate rules and the control-pair analysis (-Dpopp.gates, -Dpopp.enabling). Every schedule only
# queues the rules that can match in the mode (-Dpopp.prune=mode, the default; none keeps all 147).
#
# Output (in benchmark-results/<variant>-<timestamp>/):
#   run.log              everything printed by the steps, in order, plus the system settings
#   default.json         the default schedule from scratch (DEFAULT_ANALYZERS): all benchmarks, complete and partial
#   default-incremental.json  incremental: inspectRoot and inspectAllNodes (reading the kept fixpoint), editOnly (the
#                        edits with the analyzer observing the bigraph), coldFirstSession (its first fixpoint). Not
#                        editThenInspect: its undo and the next sample's edit cancel out; edit costs are in the chains.
#   schedules.json       every schedule and the oracle from scratch: inspectRoot (warm session), complete models
#   cold.json            every schedule from scratch: coldFirstSession (first session in a fresh JVM)
#   edits-scratch.json   edit chains from scratch with the default schedule, every action, length 1
#   edits.json           edit chains with the incremental analyzer: every action, chain lengths EDIT_LENGTHS
#   edits-schedules.json edit chains, incremental at every other schedule: MIXED edits, length 1
#                        Every chain starts from the benchmark model (the previous chain is undone untimed).
#   breakdown-incremental.csv            per edit step and action: time per phase and the hits-only bound (incremental)
#   breakdown-incremental-schedules.csv  the same for incremental at every other schedule, MIXED edits
#   breakdown-scratch.csv                the same for every schedule from scratch, MIXED edits
#                        Every step starts from the benchmark model, with the same edits for every analyzer.
#   encodings/           the worklist under the rule sets: encodings-static.csv (rules and enabled rules per encoding,
#                        incl. the two earlier ones), and per gate rules x enabling: <c>-<e>.json (JMH inspectRoot of
#                        ENCODING_SCHEDULES), precision-<c>-<e>/ (queued rule calls vs. hits, WorklistPrecision),
#                        breakdown-<c>-<e>.csv (incremental worklist schedules on identical edits). The guards
#                        depend on the encoding as well (one guard per receiver shape), so the default is included.
#   trace/               one traced session per schedule and the oracle, one traced MIXED edit (median reach of
#                        BREAKDOWN_STEPS) per incremental schedule and its hits-only replay (incremental-oracle)
#                        (RewritingTraceReport)
#   figures/             plots (benchmark-plots/*.py), if python3 with matplotlib is installed
#
# Models: CoverageTestModels.fullModel, all six trees balanced, 6 / 18 / 42 / 90 POPP nodes for depth 0..3.
# Only some steps: STEPS=edits,plot ./run-benchmarks.sh   (any of check,default,edits,schedules,breakdown,encodings,
# trace,plot; default all, in this order). The plot step plots everything in the output folder of this run.
# Depths: DEFAULT_DEPTHS, SCHEDULE_DEPTHS, TRACE_DEPTHS, ENCODING_DEPTHS (0..3); EDIT_DEPTHS, BREAKDOWN_SCRATCH_DEPTHS
# (1..3: edits need a decomposition). EDIT_LENGTHS (1), EDIT_ACTIONS (LINK,TYPE,MOVE,NODE,MIXED). BREAKDOWN_STEPS (6)
# and BREAKDOWN_WARMUP (2) per chain.
# Every analyzer but domain applies the rules by rewriting, so the run needs the fixed jLibBig AgentMatcher (see the
# patched framework jars) and refuses to start without it. The *-first schedules need a framework whose matchFirst works;
# without it they enumerate all matches. The log says "framework fixes: firstMatch=... rewrite=...".
# Power off when done: SHUTDOWN=1 (also after a failed step, never after Ctrl-C; 60 s grace period in which Ctrl-C
# cancels the shutdown).
# Rough duration on a Ryzen 5800X3D: about 6.5 hours; the longest steps are edits and breakdown (the schedules without
# incremental, with an untimed reset per step) and encodings (the enumerated rules).
set -euo pipefail

VARIANT="${1:-final}"
CORES="${2:-0,2,4,6}"            # four distinct physical cores (no SMT siblings); adjust to your CPU
DEFAULT_DEPTHS="${DEFAULT_DEPTHS:-0,1,2,3}"
SCHEDULE_DEPTHS="${SCHEDULE_DEPTHS:-0,1,2,3}"
TRACE_DEPTHS="${TRACE_DEPTHS:-0,1,2,3}"
ENCODING_DEPTHS="${ENCODING_DEPTHS:-0,1,2,3}"
EDIT_DEPTHS="${EDIT_DEPTHS:-1,2,3}"
EDIT_LENGTHS="${EDIT_LENGTHS:-1}"      # every chain starts from the benchmark model; 10 only adds drift
EDIT_ACTIONS="${EDIT_ACTIONS:-LINK,TYPE,MOVE,NODE,MIXED}"
BREAKDOWN_SCRATCH_DEPTHS="${BREAKDOWN_SCRATCH_DEPTHS:-1,2,3}"
DEFAULT_JMH="${DEFAULT_JMH:--f 2}"
SCHEDULE_JMH="${SCHEDULE_JMH:--f 2 -wi 2 -i 5}"
COLD_JMH="${COLD_JMH:--f 5}"
ENCODING_JMH="${ENCODING_JMH:--f 1 -wi 1 -i 3}"
BREAKDOWN_STEPS="${BREAKDOWN_STEPS:-6}"      # measured steps per chain (each chain runs twice: untraced, traced)
BREAKDOWN_WARMUP="${BREAKDOWN_WARMUP:-2}"
# the most important results first, so a run that overruns the night still has them
STEPS=",${STEPS:-check,default,edits,schedules,breakdown,encodings,trace,plot},"
step() { [[ "$STEPS" == *",$1,"* ]]; }
DEFAULT_SCHEDULE="worklist-guards-seeded-first"
SCHEDULES="${SCHEDULES:-rounds,worklist,worklist-seeded,worklist-guards-seeded-first}"
DEFAULT_ANALYZERS="${DEFAULT_ANALYZERS:-$DEFAULT_SCHEDULE}"       # add domain for its numbers in summary.csv
ORACLES="${ORACLES:-oracle}"
prefixed() { local out="" s; for s in ${SCHEDULES//,/ }; do out="$out,$1$s"; done; echo "${out#,}"; }
INCREMENTALS="$(prefixed incremental-)"
OTHER_INCREMENTALS="$(echo "$INCREMENTALS" | tr ',' '\n' | grep -vx "incremental-$DEFAULT_SCHEDULE" | paste -sd,)"
# the encodings step: the worklist schedules under both gate rule sets and both enabling analyses
ENCODING_GATES="${ENCODING_GATES:-enumerated,threshold}"
ENCODING_ENABLING="${ENCODING_ENABLING:-controls,overlap}"
ENCODING_SCHEDULES="${ENCODING_SCHEDULES:-worklist,worklist-seeded,worklist-guards-seeded-first}"   # + rounds once per gate rule set
ENCODING_INCREMENTALS="${ENCODING_INCREMENTALS:-incremental-worklist,incremental-worklist-seeded,incremental-worklist-guards-seeded-first}"
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
for c in CoverageBenchmark CoverageBenchmarkCheck RewritingTraceReport EditChainBenchmark EditChainBreakdown \
         WorklistPrecision EncodingComparison; do
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
PKG=org.eclipse.glsp.example.bigraph.extension.popp.coverage
CAPS="$(java -cp "$CP" $PKG.CoverageBenchmarkCheck --capabilities 2>/dev/null | tail -1)"
if [[ "$CAPS" != *"rewrite=true"* ]]; then
  echo "the jLibBig AgentMatcher on the classpath splits links ($CAPS); install jlibbig-agentmatcher-fix first" >&2; exit 1
fi

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
  echo "--- rules: $(java -cp "$CP" $PKG.CoverageBenchmarkCheck --rules 2>/dev/null | tail -1)"
  echo

  if step check; then
  echo "=== check: analyzers vs domain, all coverage modes (default schedule, oracle, incremental up to depth 2, the others up to 1)"
  $RUN -Dvariant="$VARIANT" -cp "$CP" $PKG.CoverageBenchmarkCheck \
    2 "$DEFAULT_SCHEDULE,oracle-$DEFAULT_SCHEDULE,incremental-$DEFAULT_SCHEDULE"
  $RUN -Dvariant="$VARIANT" -cp "$CP" $PKG.CoverageBenchmarkCheck 1 "$SCHEDULES,$ORACLES,$OTHER_INCREMENTALS"
  $RUN -cp "$CP" $PKG.CoverageBenchmarkCheck --edits incremental,incremental-worklist
  echo
  fi

  if step default; then
  echo "=== default: JMH $DEFAULT_ANALYZERS ($DEFAULT_JMH), all benchmarks, depth $DEFAULT_DEPTHS"
  $JMH CoverageBenchmark $DEFAULT_JMH -rf json -rff "$OUT/default.json" \
    -p analyzer="$DEFAULT_ANALYZERS" -p depth="$DEFAULT_DEPTHS" -p complete=true,false \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo "=== default: JMH incremental ($DEFAULT_JMH), inspectRoot, inspectAllNodes, editOnly, coldFirstSession, depth $DEFAULT_DEPTHS"
  $JMH 'CoverageBenchmark\.(inspectRoot|inspectAllNodes|editOnly|coldFirstSession)$' $DEFAULT_JMH -rf json \
    -rff "$OUT/default-incremental.json" -p analyzer=incremental -p depth="$DEFAULT_DEPTHS" -p complete=true,false \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo
  fi

  if step edits; then
  echo "=== edits: from scratch ($DEFAULT_SCHEDULE), length 1, depth $EDIT_DEPTHS, actions $EDIT_ACTIONS"
  $JMH EditChainBenchmark -rf json -rff "$OUT/edits-scratch.json" \
    -p analyzer="$DEFAULT_SCHEDULE" -p depth="$EDIT_DEPTHS" -p length=1 -p action="$EDIT_ACTIONS" \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo "=== edits: incremental, lengths $EDIT_LENGTHS, depth $EDIT_DEPTHS, actions $EDIT_ACTIONS"
  $JMH EditChainBenchmark -rf json -rff "$OUT/edits.json" \
    -p analyzer=incremental -p depth="$EDIT_DEPTHS" -p length="$EDIT_LENGTHS" -p action="$EDIT_ACTIONS" \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo "=== edits: incremental at the other schedules, MIXED, length 1, depth $EDIT_DEPTHS"
  $JMH EditChainBenchmark -rf json -rff "$OUT/edits-schedules.json" \
    -p analyzer="$OTHER_INCREMENTALS" -p depth="$EDIT_DEPTHS" -p length=1 -p action=MIXED \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo
  fi

  if step schedules; then
  echo "=== schedules: JMH inspectRoot of $SCHEDULES,$ORACLES ($SCHEDULE_JMH), depth $SCHEDULE_DEPTHS"
  # the oracle records its schedule in the setup (outside the measurement), so it has no cold start of its own
  $JMH 'CoverageBenchmark\.inspectRoot' $SCHEDULE_JMH -rf json -rff "$OUT/schedules.json" \
    -p analyzer="$SCHEDULES,$ORACLES" -p depth="$SCHEDULE_DEPTHS" -p complete=true \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo "=== schedules: JMH coldFirstSession of $SCHEDULES ($COLD_JMH), depth $SCHEDULE_DEPTHS"
  $JMH 'CoverageBenchmark\.coldFirstSession' $COLD_JMH -rf json -rff "$OUT/cold.json" \
    -p analyzer="$SCHEDULES" -p depth="$SCHEDULE_DEPTHS" -p complete=true \
    -jvmArgsAppend "-Dvariant=$VARIANT"
  echo
  fi

  if step breakdown; then
  echo "=== breakdown: phases and hits-only bound per edit step, incremental, depth $EDIT_DEPTHS, actions $EDIT_ACTIONS"
  $RUN -cp "$CP" $PKG.EditChainBreakdown \
    "$OUT/breakdown-incremental.csv" incremental "$EDIT_DEPTHS" "$EDIT_ACTIONS" "$BREAKDOWN_STEPS" "$BREAKDOWN_WARMUP"
  echo "=== breakdown: incremental at the other schedules, MIXED, depth $EDIT_DEPTHS"
  $RUN -cp "$CP" $PKG.EditChainBreakdown \
    "$OUT/breakdown-incremental-schedules.csv" "$OTHER_INCREMENTALS" "$EDIT_DEPTHS" MIXED "$BREAKDOWN_STEPS" "$BREAKDOWN_WARMUP"
  echo "=== breakdown: every schedule from scratch, MIXED, depth $BREAKDOWN_SCRATCH_DEPTHS"
  $RUN -cp "$CP" $PKG.EditChainBreakdown \
    "$OUT/breakdown-scratch.csv" "$SCHEDULES" "$BREAKDOWN_SCRATCH_DEPTHS" MIXED "$BREAKDOWN_STEPS" "$BREAKDOWN_WARMUP"
  echo
  fi

  if step encodings; then
  mkdir -p "$OUT/encodings"
  echo "=== encodings: rules and enabled rules per encoding (static)"
  $RUN -cp "$CP" $PKG.EncodingComparison "$OUT/encodings/encodings-static.csv"
  for c in ${ENCODING_GATES//,/ }; do
    for e in ${ENCODING_ENABLING//,/ }; do
      PROPS="-Dpopp.gates=$c -Dpopp.enabling=$e"
      # rounds does not use the enabling analysis: once per gate rule set
      SCH="$ENCODING_SCHEDULES"; [ "$e" = "${ENCODING_ENABLING##*,}" ] && SCH="rounds,$SCH"
      echo "=== encodings: gates=$c enabling=$e: check, worklist precision, JMH inspectRoot of $SCH, incremental breakdown"
      $RUN $PROPS -cp "$CP" $PKG.CoverageBenchmarkCheck 1 "$SCH,$ENCODING_INCREMENTALS"
      $RUN $PROPS -cp "$CP" $PKG.WorklistPrecision "$OUT/encodings/precision-$c-$e" "$ENCODING_DEPTHS" true,false
      $JMH 'CoverageBenchmark\.inspectRoot' $ENCODING_JMH -rf json -rff "$OUT/encodings/$c-$e.json" \
        -p analyzer="$SCH" -p depth="$ENCODING_DEPTHS" -p complete=true -jvmArgsAppend "-Dvariant=$VARIANT $PROPS"
      $RUN $PROPS -cp "$CP" $PKG.EditChainBreakdown "$OUT/encodings/breakdown-$c-$e.csv" "$ENCODING_INCREMENTALS" \
        "$EDIT_DEPTHS" MIXED "$BREAKDOWN_STEPS" "$BREAKDOWN_WARMUP"
    done
  done
  echo
  fi

  if step trace; then
  echo "=== trace: one traced session per schedule and the oracle, and one traced MIXED edit per incremental schedule, depth $TRACE_DEPTHS"
  $RUN -cp "$CP" $PKG.RewritingTraceReport "$OUT/trace" "$TRACE_DEPTHS" VERIFY "$SCHEDULES,$ORACLES,$INCREMENTALS,incremental-oracle" 2 MIXED "$BREAKDOWN_STEPS"
  echo
  fi

  if step plot; then
  echo "=== plot"
  if python3 -c "import matplotlib" 2>/dev/null; then
    if ls "$OUT"/*.json >/dev/null 2>&1; then
      python3 benchmark-plots/plot_benchmarks.py "$OUT" "$OUT/figures" || echo "plot_benchmarks.py failed; the data is complete"
    fi
    if ls "$OUT"/breakdown-*.csv >/dev/null 2>&1; then
      python3 benchmark-plots/plot_breakdown.py "$OUT" "$OUT/figures" || echo "plot_breakdown.py failed; the data is complete"
    fi
    if [ -d "$OUT/encodings" ]; then
      python3 benchmark-plots/plot_encodings.py "$OUT/encodings" "$OUT/figures" || echo "plot_encodings.py failed; the data is complete"
    fi
    if [ -f "$OUT/trace/rounds.csv" ]; then
      python3 benchmark-plots/plot_traces.py "$OUT/trace" "$OUT/figures" || echo "plot_traces.py failed; the data is complete"
    fi
  else
    echo "python3 with matplotlib not found; plot later with benchmark-plots/plot_*.py"
  fi
  echo
  fi
  echo "=== done $(date -Is)"
} 2>&1 | tee "$OUT/run.log"

echo "Log:     $OUT/run.log"
echo "Results: $OUT/*.json, $OUT/*.csv, $OUT/encodings/, $OUT/trace/, $OUT/figures/"
