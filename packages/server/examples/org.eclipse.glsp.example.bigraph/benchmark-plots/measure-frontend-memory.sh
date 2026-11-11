#!/usr/bin/env bash
# Memory of the running editor, step by step: the proportional set size (PSS, shared pages split between the
# processes that map them) of the VS Code extension host, the webview renderers (diagram and side panel) and the GLSP
# server (java), read from /proc/<pid>/smaps_rollup, and the server's Java heap after a full GC (jcmd). Linux only.
# Not part of the overnight run: run it any time, about 15 minutes, with you clicking through the editor while the
# script waits at each step.
#
#   ./measure-frontend-memory.sh <out-dir> [depth=2] [workspace-folder]
#
# The script starts everything itself, so that nothing else is measured along:
#   - the GLSP server from target/*.jar, as a user gets it: java -Xmx1g, no debugger, no DEBUG logging, port 52579
#     (stop any other server on that port first, e.g. one started from the IDE);
#   - a separate VS Code instance with its own empty profile (--user-data-dir, --extensions-dir) and only the
#     extension under development (--extensionDevelopmentPath, --disable-extensions), on the workspace folder
#     (default: a new empty folder in <out-dir>). Build the extension first (npm run build in packages/vscode-extension);
#   - it writes the benchmark model as POPP JSON into the workspace folder (benchmark-model-depth<depth>.json).
# The processes are found anew at every step, from the instance's own profile directory, so reopened webviews are
# measured as well: the extension host by its process type, the webviews as the renderers that are not the window.
# At the end the script stops the server and the VS Code instance.
#
# Environment: SETTLE seconds before sampling (default 10), SAMPLES samples one second apart (default 5, the median is
# written), CODE the VS Code binary (default code), PORT (default 52579), HEAP the server's -Xmx (default 1g).
set -euo pipefail

OUT="${1:?usage: $0 <out-dir> [depth] [workspace-folder]}"
DEPTH="${2:-2}"
SETTLE="${SETTLE:-10}"
SAMPLES="${SAMPLES:-5}"
CODE="${CODE:-code}"
PORT="${PORT:-52579}"
HEAP="${HEAP:-1g}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MODULE="$(cd "$HERE/.." && pwd)"
EXTENSION="$(cd "$MODULE/../../../vscode-extension" && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
WORKSPACE="${3:-$OUT/workspace}"
mkdir -p "$WORKSPACE"
PROFILE="$OUT/vscode-profile"
CSV="$OUT/memory.csv"
LOG="$OUT/memory.log"

JAR="$(ls "$MODULE"/target/org.eclipse.glsp.example.bigraph-*-SNAPSHOT.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || { echo "no server jar in $MODULE/target, build it first (mvn package)" >&2; exit 1; }
[ -d "$EXTENSION/dist" ] || { echo "the extension is not built ($EXTENSION/dist missing)" >&2; exit 1; }
command -v "$CODE" >/dev/null || { echo "VS Code binary '$CODE' not found, set CODE=..." >&2; exit 1; }
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  echo "port $PORT is in use: stop the other GLSP server first" >&2; exit 1
fi

# the benchmark model as POPP JSON, from the test classes (mvn test-compile once before)
MODEL="$WORKSPACE/benchmark-model-depth$DEPTH.json"
if [ ! -f "$MODEL" ]; then
  if [ ! -f "$MODULE/target/cp.txt" ]; then
    (cd "$MODULE" && mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test)
  fi
  java -cp "$MODULE/target/classes:$MODULE/target/test-classes:$(cat "$MODULE/target/cp.txt")" \
    org.eclipse.glsp.example.bigraph.extension.popp.coverage.ExportBenchmarkModel "$DEPTH" "$MODEL" 2>/dev/null | tail -1
fi

SERVER=""
cleanup() {
  [ -n "$SERVER" ] && kill "$SERVER" 2>/dev/null || true
  pkill -f -- "$PROFILE" 2>/dev/null || true
}
trap cleanup EXIT

echo "starting the GLSP server ($JAR, -Xmx$HEAP, port $PORT)"
(cd "$MODULE" && exec java -Xmx"$HEAP" -jar "$JAR" -p "$PORT" > "$OUT/glsp-server.log" 2>&1) &
SERVER=$!
for _ in $(seq 60); do
  (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && break
  kill -0 "$SERVER" 2>/dev/null || { echo "the server stopped, see $OUT/glsp-server.log" >&2; exit 1; }
  sleep 1
done

echo "starting VS Code (own profile, only the extension under development) on $WORKSPACE"
rm -rf "$PROFILE"
"$CODE" --new-window --user-data-dir="$PROFILE" --extensions-dir="$PROFILE/extensions" --disable-extensions \
  --disable-workspace-trust --skip-welcome --skip-release-notes \
  --extensionDevelopmentPath="$EXTENSION" "$WORKSPACE" > "$OUT/vscode.log" 2>&1 &

# the processes of this VS Code instance: every process whose command line names its profile directory
#, and all their descendants (in case a child process does not repeat the switch)
instance_pids() {
  local roots pid queue=() seen=" "
  # the path alone: some builds (e.g. Code - OSS) pass it on as "--user-data-dir <path>" instead of "...=<path>"
  roots=$(pgrep -f -- "$PROFILE" 2>/dev/null || true)
  queue=($roots)
  while [ ${#queue[@]} -gt 0 ]; do
    pid="${queue[0]}"; queue=("${queue[@]:1}")
    [[ "$seen" == *" $pid "* ]] && continue
    seen="$seen$pid "
    echo "$pid"
    queue+=($(pgrep -P "$pid" 2>/dev/null || true))
  done
}
cmdline() { tr '\0' ' ' < "/proc/$1/cmdline" 2>/dev/null || true; }
environ_value() { tr '\0' '\n' < "/proc/$1/environ" 2>/dev/null | sed -n "s/^$2=//p" | head -1; }
# what a VS Code process is: its crash reporter type, or the module its utility process runs
process_kind() {
  local kind
  kind="$(environ_value "$1" VSCODE_CRASH_REPORTER_PROCESS_TYPE)"
  [ -n "$kind" ] || kind="$(environ_value "$1" VSCODE_ESM_ENTRYPOINT)"
  [ -n "$kind" ] || kind="$(environ_value "$1" VSCODE_AMD_ENTRYPOINT)"
  echo "$kind"
}

# The extension host is the process of the instance with a TCP connection to the GLSP server's port (the extension
# connects to it once a diagram is open). Code - OSS marks its helper processes neither on the command line nor in the
# environment, so the connection is the reliable sign. Before the first connection, the extension host cannot be told
# apart from the other node services (terminal host, file watcher, shared process).
EXTHOST=""
server_socket_inodes() { # sockets whose remote end is the server's port
  local port_hex
  port_hex=$(printf '%04X' "$PORT")
  awk -v port=":$port_hex" 'NR > 1 && substr($3, length($3) - 4) == port {print $10}' /proc/net/tcp /proc/net/tcp6 2>/dev/null
}
extension_hosts() {
  if [ -n "$EXTHOST" ] && [ -r "/proc/$EXTHOST/smaps_rollup" ]; then echo "$EXTHOST"; return; fi
  local inodes pid inode
  inodes=$(server_socket_inodes)
  [ -n "$inodes" ] || return 0
  for pid in $(instance_pids); do
    [ "$pid" = "$SERVER" ] && continue
    for inode in $inodes; do
      if ls -l "/proc/$pid/fd" 2>/dev/null | grep -q "socket:\[$inode\]"; then EXTHOST="$pid"; echo "$pid"; return; fi
    done
  done
}

# The webviews: every renderer of the instance except the window's own. Code - OSS gives the window configuration to
# the webview renderers as well, so the command line does not tell them apart; the instance has one window, whose
# renderer is the oldest one (by start time). Every other renderer is a webview: the diagram, the side panel and the
# BigraphIDE's sidebar view.
start_time() { awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
renderers_by_age() {
  local pid
  for pid in $(instance_pids); do
    [[ "$(cmdline "$pid")" == *"--type=renderer"* ]] && echo "$(start_time "$pid") $pid"
  done | sort -n | awk '{print $2}'
}
window_renderer() { renderers_by_age | head -1; }
webviews() { renderers_by_age | tail -n +2; }
# every process of the instance with what the script makes of it, into the log (to check the detection)
describe_instance() {
  local pid line kind
  echo "    processes of the VS Code instance:"
  for pid in $(instance_pids); do
    line="$(cmdline "$pid")"
    kind="$(process_kind "$pid")"
    printf "      %7s  %-12s %-40s %s\n" "$pid" "$(grep -o -- '--type=[a-z-]*' <<< "$line" | head -1 | cut -c8-)" \
      "${kind:--}" "$([ "$pid" = "$(window_renderer)" ] && echo window)"
  done
}

pss_kib() { awk '/^Pss:/ {print $2}' "/proc/$1/smaps_rollup" 2>/dev/null || echo 0; }
rss_kib() { awk '/^Rss:/ {print $2}' "/proc/$1/smaps_rollup" 2>/dev/null || echo 0; }
median() { sort -n | awk '{a[NR]=$1} END {if (NR == 0) print 0; else print (NR%2 ? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2)}'; }
HAVE_JCMD=0
command -v jcmd >/dev/null && HAVE_JCMD=1

{
  echo "=== $(date -Is)  depth=$DEPTH  settle=${SETTLE}s  samples=$SAMPLES"
  uname -r
  "$CODE" --version 2>/dev/null | head -1 | sed 's/^/vscode /' || true
  java -version 2>&1 | head -1
  echo "server: java -Xmx$HEAP -jar $(basename "$JAR") -p $PORT (pid $SERVER)"
} | tee "$LOG"
[ -f "$CSV" ] || echo "step,description,process,pid,pss_kib,rss_kib,java_heap_used_kib" > "$CSV"

sample() { # $1: pid -> "pss rss", PSS as the median of SAMPLES samples
  local pss
  pss=$(for _ in $(seq "$SAMPLES"); do pss_kib "$1"; sleep 1; done | median)
  echo "$pss $(rss_kib "$1")"
}

measure() { # $1: step id, $2: what to do in the editor
  echo
  echo "--- step $1: $2"
  read -r -p "    do it, wait until the editor is idle, then press Enter "
  sleep "$SETTLE"
  local heap="" entries=() index=0 pid name values total=0
  if [ "$HAVE_JCMD" = 1 ]; then
    jcmd "$SERVER" GC.run >/dev/null 2>&1 || true
    sleep 2
    heap=$(jcmd "$SERVER" GC.heap_info 2>/dev/null | grep -m1 -o 'used [0-9]*K' | grep -o '[0-9]*' || true)
  fi
  for pid in $(extension_hosts); do entries+=("extension-host:$pid"); done
  for pid in $(webviews); do index=$((index + 1)); entries+=("webview-$index:$pid"); done
  entries+=("glsp-server:$SERVER")
  if [ "$1" = 0 ] || [ ${#entries[@]} -le 1 ]; then describe_instance | tee -a "$LOG"; fi
  [ -n "$(extension_hosts)" ] || echo "    (extension host not connected to the server yet: it is measured from the first step with an open diagram)" | tee -a "$LOG"
  for entry in "${entries[@]}"; do
    name="${entry%%:*}"; pid="${entry#*:}"
    read -r pss rss <<< "$(sample "$pid")"
    local javaheap=""
    [ "$name" = glsp-server ] && javaheap="$heap"
    [[ "$name" == webview-* ]] && total=$((total + pss))
    echo "$1,\"$2\",$name,$pid,$pss,$rss,$javaheap" >> "$CSV"
    printf "    %-15s %7s  PSS %8s KiB   RSS %8s KiB %s\n" "$name" "$pid" "$pss" "$rss" \
      "${javaheap:+  java heap used after GC $javaheap KiB}" | tee -a "$LOG"
  done
  echo "$1,\"$2\",webviews-total,-,$total,," >> "$CSV"
  printf "    %-15s %7s  PSS %8s KiB\n" "webviews total" "" "$total" | tee -a "$LOG"
}

# The analysis runs whenever a POPP diagram is open: after every model change (debounced) the diagram requests the
# coverage report, which the server computes for all five modes, and the gaps of the selected mode. The "highlight
# uncovered" toggle and the side panel only show these results, the inspect tool traces one node on the server.
measure 0 "VS Code is open on the workspace, no diagram open yet"
measure 1 "create a new POPP diagram and keep it open (the analysis is already active), side panel closed, highlight uncovered off"
measure 2 "import $(basename "$MODEL") from the workspace into this diagram (Import POPP model from JSON), wait until the report has updated"
measure 3 "open the POPP side panel and switch on highlight uncovered"
measure 4 "switch through every coverage mode in the side panel once, end in Verify"
measure 5 "make about 20 edits, both halves of each pair count (unlink and relink, AND to OR and back, detach and reattach)"
measure 6 "undo the edits until the model is the imported one again (or re-import it)"
measure 7 "inspect a few nodes with the inspect tool (e.g. a problem root and a goal), then leave the inspect tool"
measure 8 "close the diagram (the GLSP server keeps running)"

echo
read -r -p "done: $CSV. Press Enter to stop the server and close this VS Code instance "