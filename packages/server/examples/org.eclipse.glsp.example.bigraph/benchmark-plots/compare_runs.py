#!/usr/bin/env python3
"""
Compares the default analyzer of this run with the two earlier benchmark runs, per model size, from scratch (VERIFY)
and after one MIXED edit (incremental). The earlier runs measured another metamodel (causes only backwards, so no
consequence was ever covered and fewer rules could match) and partly another analyzer, which is why their numbers are
not valid any more; the table shows by how much they differ.

    python3 compare_runs.py <new-result-dir> [--jmh-run <dir>] [--roundup-run <dir>] [--out <file.csv>]

  --jmh-run      the first run (popp-benchmarks): default.json (CoverageBenchmark.inspectRoot, analyzer
                 worklist-guards-seeded-first) and edits.json (EditChainBenchmark, analyzer incremental, length 1)
  --roundup-run  the second run (popp-generic-benchmarks): roundup.csv (CoverageBenchmark median harness, variant
                 counted), from scratch the VERIFY session, incremental the mean over the edits
Writes old-vs-new.csv and old-vs-new.tex (rows) into <new-result-dir>/tables unless --out is given.
"""
import argparse
import csv
import json
import os
from collections import defaultdict


def nodes_of(depth):
    return 6 * (2 ** (depth + 1) - 1)


def jmh(path):
    return json.load(open(path)) if path and os.path.exists(path) else []


def first_run(directory):
    """(phase, nodes) -> ms of the first run."""
    out = {}
    if not directory:
        return out
    for entry in jmh(os.path.join(directory, "default.json")):
        params = entry["params"]
        if entry["benchmark"].endswith("inspectRoot") and params.get("analyzer") == "worklist-guards-seeded-first" \
                and params.get("mode", "VERIFY") == "VERIFY" and params.get("complete", "true") == "true":
            out[("scratch", nodes_of(int(params["depth"])))] = entry["primaryMetric"]["score"]
    for entry in jmh(os.path.join(directory, "edits.json")):
        params = entry["params"]
        if params.get("analyzer") == "incremental" and params.get("action") == "MIXED" and params.get("length") == "1":
            out[("incremental", nodes_of(int(params["depth"])))] = entry["primaryMetric"]["score"]
    return out


def second_run(directory):
    out = {}
    path = os.path.join(directory, "roundup.csv") if directory else None
    if not path or not os.path.exists(path):
        return out
    edits = defaultdict(list)
    for row in csv.DictReader(open(path)):
        if row["variant"] != "counted":
            continue
        nodes = int(row["popp_nodes"])
        if row["phase"] == "scratch" and row["mode"] == "VERIFY":
            out[("scratch", nodes)] = float(row["median_ms"])
        elif row["phase"] == "incremental":
            edits[nodes].append(float(row["median_ms"]))
    for nodes, values in edits.items():
        out[("incremental", nodes)] = sum(values) / len(values)
    return out


def this_run(directory):
    out = {}
    for name, phase in (("ladder-scratch-fast.json", "scratch"), ("ladder-incremental-fast.json", "incremental")):
        path = os.path.join(directory, "jmh", name)
        rerun = path.replace(".json", "-rerun.json")
        for entry in jmh(rerun if os.path.exists(rerun) else path):
            params = entry["params"]
            if params.get("config") == "default" and params.get("mode", "VERIFY") == "VERIFY":
                per = 2 * int(params["edits"]) if "edits" in params else 1  # an edit sequence, per edit
                out[(phase, nodes_of(int(params["depth"])))] = entry["primaryMetric"]["score"] / per
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("new")
    parser.add_argument("--jmh-run")
    parser.add_argument("--roundup-run")
    parser.add_argument("--out")
    args = parser.parse_args()
    first, second, new = first_run(args.jmh_run), second_run(args.roundup_run), this_run(args.new)
    header = ["phase", "popp_nodes", "first_run_ms", "second_run_ms", "this_run_ms", "this_vs_first", "this_vs_second"]
    rows = [header]
    for phase in ("scratch", "incremental"):
        for nodes in sorted({key[1] for key in list(first) + list(second) + list(new) if key[0] == phase}):
            a, b, c = first.get((phase, nodes)), second.get((phase, nodes)), new.get((phase, nodes))
            rows.append([phase, nodes, f"{a:.1f}" if a else "", f"{b:.1f}" if b else "", f"{c:.1f}" if c else "",
                         f"{c / a:.2f}" if a and c else "", f"{c / b:.2f}" if b and c else ""])
    out = args.out or os.path.join(args.new, "tables", "old-vs-new.csv")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", newline="") as handle:
        csv.writer(handle).writerows(rows)
    with open(out[:-4] + ".tex", "w") as handle:
        handle.write("% " + " & ".join(header) + "\n")
        for row in rows[1:]:
            handle.write(" & ".join(str(cell) for cell in row) + r" \\" + "\n")
    for row in rows:
        print("  ".join(f"{str(cell):>14}" for cell in row))


if __name__ == "__main__":
    main()
