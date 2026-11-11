#!/usr/bin/env bash
# The benchmark run of the thesis: every figure and table of the evaluation from one overnight run, one log.
#
#   ./run-benchmarks.sh [name] [cores]
#   SHUTDOWN=1 ./run-benchmarks.sh final 0,2,4,6
#
# Configurations (BenchmarkConfigs; whatever a comparison does not vary is always the editor's default):
#   ladder     baseline (no optimization) -> +overlap -> +topological -> +seed -> +per-mode -> +guards (cached)
#              -> default (guards counted), one optimization per step
#   ablation   the default without one optimization or with another strategy: no-seed, no-per-mode, fifo,
#              no-overlap, guards-cached, guards-uncached, no-guards, match-all
#   encodings  threshold / combination gate rules x overlap / control-pair dependencies, worklist only or default
#   domain     the domain analyzer (the reference, no rewriting)
#
# Calibrate first (about 3 minutes, not part of the default steps): STEPS=calibrate ./run-benchmarks.sh measures the
# default analyzer on the 42-node model and prints the estimated duration of the whole run on this machine.
#
# Steps (STEPS, default all, in this order: the essential figures first, so a run that overruns still has them):
#   check      CoverageBenchmarkCheck: every configuration against the domain analyzer, every mode, from scratch and
#              after edits of every action (default and domain up to depth 2, every other configuration depth 1)
#   static     EncodingComparison: rules and enabled rules per encoding (Figure 4.6)     -> encodings-static.csv
#   ladder     JMH, the ladder from scratch (depths 0-3) and after one MIXED edit (incremental, depths 1-3)
#                                                                                       -> jmh/ladder-*.json
#   modes      JMH with -prof gc, default and domain in every mode, from scratch and after one MIXED edit
#                                                                                       -> jmh/modes-*.json
#   edits      JMH, default incremental and from scratch, every edit action, depths 1-3 -> jmh/edits.json
#   ablation   JMH, the ablation on the 42-node model (depth 2), from scratch and incremental, plus the matcher
#              calls of ladder and ablation (CoverageCallCounts, depths 0-2, the default also 3, with its hits-only
#              bound)                                                       -> jmh/ablation-*.json, calls/, calls-default/
#   memory     MemoryFootprint, one JVM per depth: retained heap of model, rule set, incremental analyzer
#                                                                                       -> memory.csv
#   trace      CoverageTraceRound: every matcher call of one session per ladder step, depth 2, VERIFY  -> trace/
#   encodings  JMH, the encodings from scratch (depths 0-2) and incremental (depths 1-2) -> jmh/encodings-*.json
#   plot       benchmark-plots/plot_benchmarks.py and plot_traces.py (python3 with matplotlib)  -> figures/, tables/
#
# Error bars: JMH runs every benchmark in 3 fresh JVMs (forks) with warm-up iterations first; the plots show the mean and
# the 99.9 % confidence interval JMH reports (scoreError). Slow configurations (SLOW: the first ladder steps and the
# worklist-only encodings, whose sessions take seconds) run fewer iterations of the same length, a session there
# is long enough that a few already give a tight interval.
#
# Re-run of the incremental mixed-edit benchmarks (fixed edit sequences) into an earlier result folder, about 2 h:
#   RESULTS=benchmark-results/final-20261110-231047 STEPS=incremental,plot SHUTDOWN=1 ./run-benchmarks.sh
# Only some steps: STEPS=ladder,plot ./run-benchmarks.sh. The plot step plots everything in the output folder.
# Planned for about 6 hours on a Ryzen 7 5800X3D; STEPS=calibrate tells (the log shows the elapsed time per step).
# The frontend memory (VS Code extension host, webview, GLSP server) is measured by hand, any time later:
# benchmark-plots/measure-frontend-memory.sh (about 15 minutes, guided).
# Power off when done: SHUTDOWN=1 (also after a failed step, never after Ctrl-C, 60 s grace period).
set -euo pipefail

NAME="${1:-final}"
CORES="${2:-0,2,4,6}"            # distinct physical cores (no SMT siblings), adjust to your CPU
STEPS=",${STEPS:-check,static,ladder,modes,edits,ablation,memory,trace,encodings,plot},"
step() { [[ "$STEPS" == *",$1,"* ]]; }
# JMH options: forks, warm-up and measurement iterations of 1 s (an iteration always completes its last operation)
FAST_JMH="${FAST_JMH:--f 3 -wi 5 -i 10 -w 1 -r 1}"
SLOW_JMH="${SLOW_JMH:--f 3 -wi 1 -i 4 -w 1 -r 1}"
# Edit benchmarks: one operation is a fixed sequence of EDITS edits with their undos (2 * EDITS inspections), so an
# operation takes seconds and fewer iterations already average over the same edits
EDITS="${EDITS:-6}"
INC_JMH="${INC_JMH:--f 3 -wi 2 -i 5 -w 1 -r 1 -p edits=$EDITS}"
INC_SLOW_JMH="${INC_SLOW_JMH:--f 3 -wi 1 -i 3 -w 1 -r 1 -p edits=$EDITS}"
SLOW="${SLOW:-baseline,+overlap,+topological,threshold-overlap-worklist,threshold-controls-worklist,combination-overlap-worklist,combination-controls-worklist}"
MODES="${MODES:-PLANNING,VERIFY,GOALS_BY_CRITERIA,PROBLEMS_BY_GOALS,PROBLEMS_BY_PROOFS}"
EDIT_MODE="${EDIT_MODE:-VERIFY}"
ABLATION_DEPTHS="${ABLATION_DEPTHS:-2}"
ENCODING_DEPTHS="${ENCODING_DEPTHS:-0,1,2}"
CALL_DEPTHS="${CALL_DEPTHS:-0,1,2}"
CALL_REPS="${CALL_REPS:-3}"
CALL_EDITS="${CALL_EDITS:-8}"
MEMORY_EDITS="${MEMORY_EDITS:-50}"
# RESULTS=<existing result folder> adds to that folder instead of a new one (for a partial re-run), with its own log
OUT="${RESULTS:-benchmark-results/${NAME}-$(date +%Y%m%d-%H%M%S)}"
LOGNAME_="run.log"
[ -n "${RESULTS:-}" ] && LOGNAME_="run-$(date +%Y%m%d-%H%M%S).log"

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
mkdir -p "$OUT/jmh" "$OUT/calls" "$OUT/trace"

# Compile main and test classes (with the JMH-generated benchmark list) and write the classpath. Always runs, so
# edits since the last build are never benchmarked stale.
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
PKG=org.eclipse.glsp.example.bigraph.extension.popp.coverage
for class in CoverageScratchBenchmark CoverageEditBenchmark CoverageBenchmarkCheck CoverageCallCounts \
             CoverageTraceRound EncodingComparison MemoryFootprint ExportBenchmarkModel; do
  if [ ! -f "target/test-classes/${PKG//.//}/$class.class" ]; then
    echo "missing $class.class after mvn test-compile" >&2; exit 1
  fi
done
if [ ! -f target/test-classes/META-INF/BenchmarkList ]; then
  echo "JMH BenchmarkList missing: the jmh-generator-annprocess path in pom.xml did not run" >&2; exit 1
fi
CP="target/classes:target/test-classes:$(cat target/cp.txt)"
RUN="taskset -c $CORES java -Xms2g -Xmx2g"
CAPS="$(java -cp "$CP" $PKG.CoverageBenchmarkCheck --capabilities 2>/dev/null | tail -1)"
if [[ "$CAPS" != *"rewrite=true"* ]]; then
  echo "the framework on the classpath does not support rewriting ($CAPS): install the patched framework first" >&2; exit 1
fi
list() { java -cp "$CP" $PKG.CoverageBenchmarkCheck --list "$1" 2>/dev/null | tail -1; }
# the configurations of a group without / only the slow ones
fast() { list "$1" | tr ',' '\n' | grep -vxF -f <(echo "$SLOW" | tr ',' '\n') | paste -sd, -; }
slow() { list "$1" | tr ',' '\n' | grep -xF -f <(echo "$SLOW" | tr ',' '\n') | paste -sd, - || true; }
SUFFIX=""
STARTED=$(date +%s)
elapsed() { local s=$(( $(date +%s) - STARTED )); printf "%dh%02dm" $((s / 3600)) $((s % 3600 / 60)); }
# jmh <out-name> <benchmark> <options...>: one JMH run, results as JSON; skipped if the configuration list is empty
jmh() {
  local name="$1"; shift
  if [[ "$*" == *"-p config= "* || "$*" == *"-p config=" ]]; then return; fi
  echo "--- jmh $name ($(elapsed) so far)"
  $RUN -cp "$CP" org.openjdk.jmh.Main "$@" -jvmArgsAppend "-Xms2g -Xmx2g" -rf json -rff "$OUT/jmh/$name.json"
}

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
  echo "--- framework: $CAPS"
  java -cp "$CP" $PKG.CoverageBenchmarkCheck --rules 2>/dev/null | grep rules || true
  echo "--- jmh: fast $FAST_JMH | slow $SLOW_JMH ($SLOW)"
  echo

  if step calibrate; then
    echo "=== calibrate ($(elapsed))"
    $RUN -cp "$CP" $PKG.CoverageCallCounts "$OUT/calibrate" 2 default 3 2 "$EDIT_MODE" >/dev/null
    python3 - "$OUT/calibrate/calls.csv" "$EDIT_MODE" <<'PY'
import csv, sys
rows = [r for r in csv.DictReader(open(sys.argv[1])) if r["config"] == "default" and r["phase"] == "scratch" and r["mode"] == sys.argv[2]]
ms = float(rows[0]["median_ms"])
# the plan: about 6 hours if this session takes 850 ms; about 1 hour of it (JVM forks, setup) does not scale with it
hours = 1.0 + 5.0 * ms / 850
print(f"default, 42 nodes, {sys.argv[2]} from scratch: {ms:.0f} ms -> the whole run takes about {hours:.1f} hours")
PY
  fi

  if step check; then
    echo "=== check ($(elapsed))"
    $RUN -cp "$CP" $PKG.CoverageBenchmarkCheck 2 default,domain 4
    $RUN -cp "$CP" $PKG.CoverageBenchmarkCheck 1 all 3
  fi

  if step static; then
    echo "=== static: encodings (Figure 4.6) ($(elapsed))"
    $RUN -cp "$CP" $PKG.EncodingComparison "$OUT/encodings-static.csv"
  fi

  if step ladder; then
    echo "=== ladder ($(elapsed))"
    jmh ladder-scratch-fast CoverageScratchBenchmark $FAST_JMH -p config="$(fast ladder)" -p depth=0,1,2,3 -p mode="$EDIT_MODE"
    jmh ladder-scratch-slow CoverageScratchBenchmark $SLOW_JMH -p config="$(slow ladder)" -p depth=0,1,2,3 -p mode="$EDIT_MODE"
    jmh ladder-incremental-fast$SUFFIX CoverageEditBenchmark $INC_JMH -p config="$(fast ladder)" -p analysis=incremental \
      -p action=MIXED -p depth=1,2,3 -p mode="$EDIT_MODE"
    jmh ladder-incremental-slow$SUFFIX CoverageEditBenchmark $INC_SLOW_JMH -p config="$(slow ladder)" -p analysis=incremental \
      -p action=MIXED -p depth=1,2,3 -p mode="$EDIT_MODE"
  fi

  if step modes; then
    echo "=== modes ($(elapsed))"
    jmh modes-scratch CoverageScratchBenchmark $FAST_JMH -prof gc -p config=default,domain -p depth=0,1,2,3 -p mode="$MODES"
    jmh modes-incremental$SUFFIX CoverageEditBenchmark $INC_JMH -prof gc -p config=default,domain -p analysis=incremental \
      -p action=MIXED -p depth=1,2,3 -p mode="$MODES"
  fi

  if step edits; then
    echo "=== edits ($(elapsed))"
    jmh edits$SUFFIX CoverageEditBenchmark $INC_JMH -p config=default -p analysis=incremental,scratch \
      -p action=LINK,TYPE,MOVE,NODE,MIXED -p depth=1,2,3 -p mode="$EDIT_MODE"
  fi

  if step ablation; then
    echo "=== ablation ($(elapsed))"
    jmh ablation-scratch CoverageScratchBenchmark $FAST_JMH -p config="default,$(list ablation)" \
      -p depth="$ABLATION_DEPTHS" -p mode="$EDIT_MODE"
    jmh ablation-incremental$SUFFIX CoverageEditBenchmark $INC_JMH -p config="default,$(list ablation)" -p analysis=incremental \
      -p action=MIXED -p depth="$ABLATION_DEPTHS" -p mode="$EDIT_MODE"
    echo "--- matcher calls ($(elapsed))"
    $RUN -cp "$CP" $PKG.CoverageCallCounts "$OUT/calls" "$CALL_DEPTHS" ladder,ablation "$CALL_REPS" "$CALL_EDITS" "$EDIT_MODE"
    # the default (and its hits-only bound) also on the largest model, for the ladder figure
    $RUN -cp "$CP" $PKG.CoverageCallCounts "$OUT/calls-default" 3 default "$CALL_REPS" "$CALL_EDITS" "$EDIT_MODE"
  fi

  if step memory; then
    echo "=== memory ($(elapsed))"
    for depth in 0 1 2 3; do
      $RUN -cp "$CP" $PKG.MemoryFootprint "$OUT/memory.csv" "$depth" "$MEMORY_EDITS"
    done
    for depth in 2 3; do
      java -cp "$CP" $PKG.ExportBenchmarkModel "$depth" "$OUT/benchmark-model-depth$depth.json"
    done
  fi

  if step trace; then
    echo "=== trace ($(elapsed))"
    $RUN -cp "$CP" $PKG.CoverageTraceRound "$OUT/trace" 2 "$EDIT_MODE" ladder 6
  fi

  if step encodings; then
    echo "=== encodings ($(elapsed))"
    jmh encodings-scratch-fast CoverageScratchBenchmark $FAST_JMH -p config="$(fast encodings)" -p depth="$ENCODING_DEPTHS" \
      -p mode="$EDIT_MODE"
    jmh encodings-scratch-slow CoverageScratchBenchmark $SLOW_JMH -p config="$(slow encodings)" -p depth="$ENCODING_DEPTHS" \
      -p mode="$EDIT_MODE"
    jmh encodings-incremental$SUFFIX CoverageEditBenchmark $INC_JMH -p config="$(fast encodings)" -p analysis=incremental \
      -p action=MIXED -p depth="${ENCODING_DEPTHS#0,}" -p mode="$EDIT_MODE"
  fi

  if step incremental; then
    # the re-run of the incremental mixed-edit benchmarks with fixed edit sequences, into an existing result folder:
    # RESULTS=benchmark-results/final-... STEPS=incremental,plot ./run-benchmarks.sh (writes *-rerun.json, which the
    # plots use in place of the first files)
    SUFFIX=-rerun
    echo "=== incremental re-run ($(elapsed))"
    jmh ladder-incremental-fast$SUFFIX CoverageEditBenchmark $INC_JMH -p config="$(fast ladder)" -p analysis=incremental \
      -p action=MIXED -p depth=1,2,3 -p mode="$EDIT_MODE"
    jmh ladder-incremental-slow$SUFFIX CoverageEditBenchmark $INC_SLOW_JMH -p config="$(slow ladder)" -p analysis=incremental \
      -p action=MIXED -p depth=1,2,3 -p mode="$EDIT_MODE"
    jmh ablation-incremental$SUFFIX CoverageEditBenchmark $INC_JMH -p config="default,$(list ablation)" -p analysis=incremental \
      -p action=MIXED -p depth="$ABLATION_DEPTHS" -p mode="$EDIT_MODE"
    jmh modes-incremental$SUFFIX CoverageEditBenchmark $INC_JMH -prof gc -p config=default,domain -p analysis=incremental \
      -p action=MIXED -p depth=1,2,3 -p mode="$MODES"
    jmh encodings-incremental$SUFFIX CoverageEditBenchmark $INC_JMH -p config="$(fast encodings)" -p analysis=incremental \
      -p action=MIXED -p depth="${ENCODING_DEPTHS#0,}" -p mode="$EDIT_MODE"
  fi

  if step plot; then
    echo "=== plot ($(elapsed))"
    if python3 -c "import matplotlib" 2>/dev/null; then
      python3 benchmark-plots/plot_benchmarks.py "$OUT"
      [ -f "$OUT/trace/calls.csv" ] && python3 benchmark-plots/plot_traces.py "$OUT/trace" "$OUT/figures"
    else
      echo "python3 with matplotlib not found, plot later: python3 benchmark-plots/plot_benchmarks.py $OUT"
    fi
  fi
  echo "=== done ($(elapsed)), results in $OUT"
} 2>&1 | tee "$OUT/$LOGNAME_"
