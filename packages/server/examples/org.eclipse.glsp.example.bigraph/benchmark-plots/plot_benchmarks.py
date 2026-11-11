#!/usr/bin/env python3
"""
Plots a run of run-benchmarks.sh: the JMH results (jmh/*.json), the matcher calls (calls/calls.csv), the static
encoding comparison (encodings-static.csv) and the memory footprint (memory.csv).

    python3 plot_benchmarks.py <result-dir> [<out-dir>] [--mode VERIFY] [--nfr-ms 500 --nfr-nodes 42]

The figures are drawn at the printed text width (bench_style.WIDTH), include them with width=\\textwidth. Only the
bars of the 42-node model carry their value. Every time is the JMH score (mean over all measurement iterations of all forks) with its 99.9 % confidence interval
as the error bar. Edit benchmarks with an edits parameter score a fixed sequence (each edit and its undo, each
followed by an inspection), shown per edit and inspection. If both an old and a re-run result file exist for the
same benchmark (name-rerun.json), the re-run replaces the old one. Figures (PNG + PDF, default <result-dir>/figures):
  ladder      each optimization added to the one before, per model size: from scratch and after one MIXED edit
              (incremental), log scale, with the hits-only bound of the default and the response-time requirement
  ablation    the default without one optimization, on one model: the slowdown over the default (from scratch and
              incremental) and where the matcher calls go (hits, misses, guard calls)
  modes       the default in every coverage mode, per model size, from scratch and incremental; the domain analyzer
              as a reference mark
  edits       one edit of each action and the session after it, incremental and from scratch, per model size
  encodings   rules and enabled rules per encoding (left) and what both gate rule sets cost under both dependency
              analyses (right)
Tables (CSV + LaTeX rows, <result-dir>/tables): ladder (time and speed-up per step), domain-vs-default (per mode and
model size), memory (retained heap per component, allocation per inspection).
"""
import argparse
import csv
import glob
import json
import math
import os
from collections import defaultdict

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter

from bench_style import INK, INK2, LABELS, ONE_LINE, OUTCOME, SIZES, SMALL, WIDTH, ordered, save, style, time_label

ACTIONS = ["LINK", "TYPE", "MOVE", "NODE", "MIXED"]
MODES = ["PLANNING", "VERIFY", "GOALS_BY_CRITERIA", "PROBLEMS_BY_GOALS", "PROBLEMS_BY_PROOFS"]
MODE_LABELS = {"PLANNING": "Planning", "VERIFY": "Verify", "GOALS_BY_CRITERIA": "Goals by criteria",
               "PROBLEMS_BY_GOALS": "Problems by goals", "PROBLEMS_BY_PROOFS": "Problems by proofs"}
BOUND = "#9b9a95"


def nodes_of(depth):
    return 6 * (2 ** (depth + 1) - 1)


def load_jmh(src):
    """Every JMH result as a dict: phase (scratch / edit), config, depth, mode, action, analysis, ms, low, high, alloc."""
    out = []
    paths = sorted(glob.glob(os.path.join(src, "jmh", "*.json")))
    reruns = {p.replace("-rerun.json", ".json") for p in paths if p.endswith("-rerun.json")}
    for path in paths:
        if path in reruns:
            print(f"replaced by its re-run: {os.path.basename(path)}")
            continue
        try:
            entries = json.load(open(path))
        except json.JSONDecodeError:
            print(f"skipped {path}: not complete")
            continue
        for entry in entries:
            params = entry.get("params", {})
            metric = entry["primaryMetric"]
            score, error = metric["score"], metric["scoreError"]
            if isinstance(error, str) or math.isnan(error):
                error = 0.0
            alloc = None
            for name, secondary in entry.get("secondaryMetrics", {}).items():
                if name.lstrip("·").endswith("gc.alloc.rate.norm"):
                    alloc = secondary["score"]
            # an edit sequence (edit, inspection, undo, inspection per edit) is scored per edit and inspection
            per = 2 * int(params["edits"]) if "edits" in params else 1
            score, error = score / per, error / per
            if alloc is not None:
                alloc /= per
            out.append({
                "file": os.path.basename(path).replace("-rerun", ""),
                "phase": "edit" if "CoverageEditBenchmark" in entry["benchmark"] else "scratch",
                "config": params.get("config"), "depth": int(params.get("depth", 0)),
                "mode": params.get("mode", "VERIFY"), "action": params.get("action"),
                "analysis": params.get("analysis"),
                "ms": score, "low": max(score - error, score * 0.02), "high": score + error, "alloc": alloc,
            })
    return out


def pick(rows, **criteria):
    return [r for r in rows if all(r.get(k) == v for k, v in criteria.items())]


def one(rows, **criteria):
    found = pick(rows, **criteria)
    return found[0] if found else None


def load_calls(src):
    """calls/calls.csv (ladder and ablation) and calls-default/calls.csv (the default on the largest model)."""
    rows = []
    for path in sorted(glob.glob(os.path.join(src, "calls*", "calls.csv"))):
        rows += list(csv.DictReader(open(path)))
    return rows


def hits_only(calls, mode):
    """The hits-only bound per (phase, depth): scratch in mode, incremental the mean over the edits."""
    out = {}
    scratch = [r for r in calls if r["config"] == "hits-only" and r["phase"] == "scratch" and r["mode"] == mode]
    for r in scratch:
        out[("scratch", int(r["depth"]))] = float(r["median_ms"])
    edits = defaultdict(list)
    for r in calls:
        if r["config"] == "hits-only" and r["phase"] == "incremental":
            edits[int(r["depth"])].append(float(r["median_ms"]))
    for depth, values in edits.items():
        out[("edit", depth)] = sum(values) / len(values)
    return out


def log_axis(ax, low, high):
    ax.set_yscale("log")
    ax.yaxis.set_major_locator(LogLocator(base=10))
    ax.yaxis.set_major_formatter(FuncFormatter(lambda value, _: time_label(value)))
    ax.yaxis.set_minor_formatter(NullFormatter())
    ax.set_ylim(10 ** math.floor(math.log10(max(low, 0.001))), 10 ** (math.ceil(math.log10(high)) + 0.4))


LABEL_DEPTH = 2  # the model size of NR2 (42 nodes): the only bars with their value written next to them


def grouped_bars(ax, groups, depths, value, label_depth=LABEL_DEPTH):
    """Bars per group (x) and depth (color); value(group, depth) gives a JMH row or a (ms, low, high) tuple."""
    width = 0.84 / max(1, len(depths))
    extremes = []
    for index, depth in enumerate(depths):
        for x, group in enumerate(groups):
            row = value(group, depth)
            if row is None:
                continue
            ms, low, high = (row["ms"], row["low"], row["high"]) if isinstance(row, dict) else row
            position = x + (index - (len(depths) - 1) / 2) * width
            bound = group == "hits-only"
            color = SIZES[depth % len(SIZES)]
            ax.bar(position, ms, width=width * 0.88, color="white" if bound else color,
                   edgecolor=color if bound else "white", hatch="//////" if bound else None, linewidth=0.5)
            if high > low:
                ax.errorbar(position, ms, yerr=[[ms - low], [high - ms]], fmt="none", ecolor=INK2, elinewidth=0.6,
                            capsize=1.2, capthick=0.6)
            if depth == label_depth:
                ax.text(position, high * 1.15, time_label(ms), rotation=90, ha="center", va="bottom", fontsize=SMALL,
                        color=INK)
            extremes += [low, high]
    return extremes


def size_legend(fig, depths, extra=(), ncol=None):
    handles = list(extra) + [Patch(color=SIZES[d % len(SIZES)], label=f"{nodes_of(d)} nodes") for d in depths]
    fig.legend(handles=handles, loc="lower center", ncol=ncol or len(handles), frameon=False,
               bbox_to_anchor=(0.5, 0.0), handlelength=1.4, columnspacing=1.2, handletextpad=0.5)


def nfr_line(ax, args):
    ax.axhline(args.nfr_ms, color="#d03b3b", linestyle="--", linewidth=0.8)
    return Line2D([], [], color="#d03b3b", linestyle="--", linewidth=0.8, label=f"NFR2: {args.nfr_ms:g} ms")


def horizontal_style(ax):
    style(ax)
    ax.grid(axis="y", visible=False)
    ax.grid(axis="x", color="#e4e3df", linewidth=0.5)
    ax.tick_params(axis="y", length=0)


# Figures: drawn at the printed width (bench_style.WIDTH), the two phases above each other

def plot_ladder(jmh, calls, args, out, tables):
    scratch = [r for r in jmh if r["file"].startswith("ladder-scratch")]
    edit = [r for r in jmh if r["file"].startswith("ladder-incremental")]
    if not scratch and not edit:
        return
    bound = hits_only(calls, args.mode)
    configs = ordered({r["config"] for r in scratch + edit}) + (["hits-only"] if bound else [])
    fig, axes = plt.subplots(2, 1, figsize=(WIDTH, 5.6), sharex=True)
    for ax, rows, phase, title in ((axes[0], scratch, "scratch", "From scratch: one inspection"),
                                   (axes[1], edit, "edit", "Incremental: per edit and inspection")):
        depths = sorted({r["depth"] for r in rows})

        def value(config, depth):
            if config == "hits-only":
                ms = bound.get((phase, depth))
                return None if ms is None else (ms, ms, ms)
            return one(rows, config=config, depth=depth)

        extremes = grouped_bars(ax, configs, depths, value)
        if extremes:
            log_axis(ax, min(extremes), max(extremes))
        legend_nfr = nfr_line(ax, args)
        ax.set_title(title, color=INK, loc="left")
        ax.set_ylabel("time (log)")
        style(ax)
    axes[1].set_xticks(range(len(configs)))
    axes[1].set_xticklabels([LABELS.get(c, c) for c in configs])
    bound_patch = Patch(facecolor="white", edgecolor=INK2, hatch="//////", linewidth=0.5, label="hits-only bound")
    size_legend(fig, sorted({r["depth"] for r in scratch + edit}), (legend_nfr, bound_patch))
    fig.tight_layout(rect=(0, 0.04, 1, 1), h_pad=1.0)
    save(fig, out, "ladder")

    rows = [["config", "phase", "popp_nodes", "ms", "ci_low", "ci_high", "speedup_vs_baseline", "speedup_vs_previous"]]
    for phase, data in (("scratch", scratch), ("incremental", edit)):
        for depth in sorted({r["depth"] for r in data}):
            previous = None
            base = one(data, config="baseline", depth=depth)
            for config in ordered({r["config"] for r in data}):
                row = one(data, config=config, depth=depth)
                if row is None:
                    continue
                rows.append([config, phase, nodes_of(depth), f"{row['ms']:.2f}", f"{row['low']:.2f}", f"{row['high']:.2f}",
                             f"{base['ms'] / row['ms']:.2f}" if base else "",
                             f"{previous['ms'] / row['ms']:.2f}" if previous else ""])
                previous = row
    tables["ladder"] = rows


def plot_ablation(jmh, calls, args, out, tables):
    scratch = [r for r in jmh if r["file"].startswith("ablation-scratch")]
    edit = [r for r in jmh if r["file"].startswith("ablation-incremental")]
    if not scratch:
        return
    depth = max(r["depth"] for r in scratch)
    configs = ["default"] + [c for c in ordered({r["config"] for r in scratch}) if c != "default"]
    labels = [ONE_LINE.get(c, c) if c != "default" else "default (all optimizations)" for c in configs]
    fig, axes = plt.subplots(2, 1, figsize=(WIDTH, 0.42 * len(configs) * 2 + 1.0), sharey=True)
    ax = axes[0]
    rows = [["config", "phase", "popp_nodes", "ms", "ci_low", "ci_high", "slowdown_vs_default"]]
    for offset, data, color in ((-0.2, scratch, "#143f77"), (0.2, edit, "#7fb0e6")):
        reference = one(data, config="default", depth=depth)
        if reference is None:
            continue
        for y, config in enumerate(configs):
            row = one(data, config=config, depth=depth)
            if row is None:
                continue
            factor = row["ms"] / reference["ms"]
            low, high = row["low"] / reference["ms"], row["high"] / reference["ms"]
            ax.barh(y + offset, factor, height=0.38, color=color)
            ax.errorbar(factor, y + offset, xerr=[[factor - low], [high - factor]], fmt="none", ecolor=INK2,
                        elinewidth=0.6, capsize=1, capthick=0.6)
            ax.text(high, y + offset, f" {factor:.2f}×  {time_label(row['ms'])}", va="center", fontsize=SMALL,
                    color=INK)
            rows.append([config, "scratch" if data is scratch else "incremental", nodes_of(depth), f"{row['ms']:.2f}",
                         f"{row['low']:.2f}", f"{row['high']:.2f}", f"{factor:.3f}"])
    ax.axvline(1, color=INK2, linewidth=0.6)
    ax.set_yticks(range(len(configs)))
    ax.set_yticklabels(labels)
    ax.invert_yaxis()
    ax.set_xlim(0, ax.get_xlim()[1] * 1.25)
    ax.set_xlabel("time relative to the default")
    ax.set_title(f"Time without one optimization, {nodes_of(depth)} nodes", color=INK, loc="left")
    horizontal_style(ax)
    ax.legend(handles=[Patch(color="#143f77", label="from scratch"), Patch(color="#7fb0e6", label="incremental")],
              loc="lower right", frameon=False)
    tables["ablation"] = rows

    ax = axes[1]
    parts = ["hit", "miss", "guard-pass", "guard-fail"]
    for y, config in enumerate(configs):
        row = next((r for r in calls if r["config"] == config and r["phase"] == "scratch"
                    and r["mode"] == args.mode and int(r["depth"]) == depth), None)
        if row is None:
            continue
        hits, rule_calls = int(row["hits"]), int(row["rule_calls"])
        holds, guard_calls = int(row["guard_holds"]), int(row["guard_calls"])
        left = 0
        for part, count in zip(parts, (hits, rule_calls - hits, holds, guard_calls - holds)):
            if count:
                ax.barh(y, count, left=left, height=0.62, color=OUTCOME[part][1], edgecolor="white", linewidth=0.6)
            left += count
        ax.text(left, y, f" {rule_calls} + {guard_calls}", va="center", fontsize=SMALL, color=INK)
    ax.set_xlim(0, ax.get_xlim()[1] * 1.15)
    ax.set_xlabel(f"matcher calls of one session from scratch (rule + guard calls)")
    ax.set_title("Where the matcher calls go", color=INK, loc="left")
    horizontal_style(ax)
    ax.tick_params(axis="y", labelleft=True)
    fig.legend(handles=[Patch(color=OUTCOME[p][1], label=OUTCOME[p][0]) for p in parts], loc="lower center", ncol=4,
               frameon=False, bbox_to_anchor=(0.5, 0.0), handlelength=1.4, columnspacing=1.2)
    fig.tight_layout(rect=(0, 0.035, 1, 1), h_pad=1.2)
    save(fig, out, "ablation")


def plot_modes(jmh, args, out, tables):
    scratch = [r for r in jmh if r["file"].startswith("modes-scratch")]
    edit = [r for r in jmh if r["file"].startswith("modes-incremental")]
    if not scratch:
        return
    modes = [m for m in MODES if any(r["mode"] == m for r in scratch)]
    fig, axes = plt.subplots(2, 1, figsize=(WIDTH, 5.2), sharex=True)
    for ax, rows, title in ((axes[0], scratch, "From scratch: one inspection"),
                            (axes[1], edit, "Incremental: per edit and inspection")):
        depths = sorted({r["depth"] for r in rows})
        extremes = grouped_bars(ax, modes, depths, lambda mode, depth: one(rows, config="default", mode=mode, depth=depth))
        width = 0.84 / len(depths)
        for index, depth in enumerate(depths):
            for x, mode in enumerate(modes):
                domain = one(rows, config="domain", mode=mode, depth=depth)
                if domain:
                    position = x + (index - (len(depths) - 1) / 2) * width
                    ax.plot([position - width * 0.42, position + width * 0.42], [domain["ms"]] * 2, color=INK,
                            linewidth=1.6, solid_capstyle="butt")
                    extremes.append(domain["ms"])
        log_axis(ax, min(extremes), max(extremes))
        legend_nfr = nfr_line(ax, args)
        ax.set_title(title, color=INK, loc="left")
        ax.set_ylabel("time (log)")
        style(ax)
    axes[1].set_xticks(range(len(modes)))
    axes[1].set_xticklabels([MODE_LABELS[m] for m in modes])
    domain_mark = Line2D([], [], color=INK, linewidth=1.6, label="domain analyzer")
    size_legend(fig, sorted({r["depth"] for r in scratch}), (legend_nfr, domain_mark))
    fig.tight_layout(rect=(0, 0.04, 1, 1), h_pad=1.0)
    save(fig, out, "modes")

    rows = [["mode", "popp_nodes", "domain_ms", "default_scratch_ms", "default_incremental_ms", "domain_incremental_ms",
             "scratch_vs_domain", "incremental_vs_domain", "default_scratch_alloc_kib", "default_incremental_alloc_kib",
             "domain_alloc_kib"]]
    for mode in modes:
        for depth in sorted({r["depth"] for r in scratch}):
            domain, default = one(scratch, config="domain", mode=mode, depth=depth), one(scratch, config="default", mode=mode, depth=depth)
            incremental = one(edit, config="default", mode=mode, depth=depth)
            domain_edit = one(edit, config="domain", mode=mode, depth=depth)

            def fmt(row, key="ms", scale=1.0):
                return f"{row[key] / scale:.2f}" if row and row.get(key) is not None else ""

            rows.append([mode, nodes_of(depth), fmt(domain), fmt(default), fmt(incremental), fmt(domain_edit),
                         f"{default['ms'] / domain['ms']:.0f}" if default and domain else "",
                         f"{incremental['ms'] / domain_edit['ms']:.0f}" if incremental and domain_edit else "",
                         fmt(default, "alloc", 1024), fmt(incremental, "alloc", 1024), fmt(domain, "alloc", 1024)])
    tables["domain-vs-default"] = rows


def plot_edits(jmh, args, out, tables):
    rows = [r for r in jmh if r["file"] == "edits.json"]
    if not rows:
        return
    actions = [a for a in ACTIONS if any(r["action"] == a for r in rows)]
    depths = sorted({r["depth"] for r in rows})
    fig, axes = plt.subplots(2, 1, figsize=(WIDTH, 5.0), sharex=True)
    for ax, analysis, title in ((axes[0], "incremental", "Incremental (the editor's analyzer)"),
                                (axes[1], "scratch", "From scratch")):
        extremes = grouped_bars(ax, actions, depths,
                                lambda action, depth: one(rows, analysis=analysis, action=action, depth=depth))
        log_axis(ax, min(extremes), max(extremes))
        legend_nfr = nfr_line(ax, args)
        ax.set_title(title, color=INK, loc="left")
        ax.set_ylabel("edit + inspection (log)")
        style(ax)
    axes[1].set_xticks(range(len(actions)))
    axes[1].set_xticklabels([a.capitalize() for a in actions])
    size_legend(fig, depths, (legend_nfr,))
    fig.tight_layout(rect=(0, 0.04, 1, 1), h_pad=1.0)
    save(fig, out, "edits")
    table = [["action", "popp_nodes", "incremental_ms", "scratch_ms", "speedup"]]
    for action in actions:
        for depth in depths:
            incremental, scratch = one(rows, analysis="incremental", action=action, depth=depth), one(rows, analysis="scratch", action=action, depth=depth)
            if incremental and scratch:
                table.append([action, nodes_of(depth), f"{incremental['ms']:.2f}", f"{scratch['ms']:.2f}",
                              f"{scratch['ms'] / incremental['ms']:.1f}"])
    tables["edits"] = table


def plot_encodings(jmh, src, args, out, tables):
    static_path = os.path.join(src, "encodings-static.csv")
    runtime = [r for r in jmh if r["file"].startswith("encodings")]
    if not os.path.exists(static_path) and not runtime:
        return
    fig, axes = plt.subplots(2, 1, figsize=(WIDTH, 5.4), gridspec_kw={"height_ratios": [1, 1.1]})
    ax = axes[0]
    if os.path.exists(static_path):
        static = list(csv.DictReader(open(static_path)))
        for offset, key, color, label in ((-0.2, "enables_controls", "#7fb0e6", "control-pair analysis"),
                                          (0.2, "enables_overlap", "#143f77", "overlap analysis")):
            for i, r in enumerate(static):
                total, queued = int(r["rules"]), float(r[key])
                share = 100 * queued / total
                ax.barh(i + offset, share, height=0.38, color=color, label=label if i == 0 else None)
                ax.text(share, i + offset, f" {share:.1f} %  ({queued:.1f} of {total})", va="center", fontsize=SMALL,
                        color=INK)
        ax.set_yticks(range(len(static)))
        ax.set_yticklabels([r["encoding"].replace(", ", ",\n") for r in static])
        ax.invert_yaxis()
        ax.set_xlim(0, 125)
        ax.set_xticks([0, 25, 50, 75, 100])
        ax.set_xlabel("rules a rewrite queues, share of all rules (%)")
        ax.legend(frameon=False, loc="lower right")
        ax.set_title("Rules a rewrite enables (static)", color=INK, loc="left")
        horizontal_style(ax)
    ax = axes[1]
    scratch = [r for r in runtime if r["phase"] == "scratch"]
    if scratch:
        depth = 2 if any(r["depth"] == 2 for r in scratch) else max(r["depth"] for r in scratch)
        combos = ["threshold-overlap", "threshold-controls", "combination-overlap", "combination-controls"]
        labels = {"threshold-overlap": "threshold rules,\noverlap (editor)", "threshold-controls": "threshold rules,\ncontrol pairs",
                  "combination-overlap": "combination rules,\noverlap", "combination-controls": "combination rules,\ncontrol pairs"}
        extremes = []
        for offset, suffix, color in ((-0.2, "worklist", "#7fb0e6"), (0.2, "default", "#143f77")):
            for x, combo in enumerate(combos):
                row = one(scratch, config=f"{combo}-{suffix}", depth=depth)
                if row is None:
                    continue
                ax.bar(x + offset, row["ms"], width=0.38, color=color)
                ax.errorbar(x + offset, row["ms"], yerr=[[row["ms"] - row["low"]], [row["high"] - row["ms"]]],
                            fmt="none", ecolor=INK2, elinewidth=0.6, capsize=1.2, capthick=0.6)
                ax.text(x + offset, row["high"] * 1.04, time_label(row["ms"]), ha="center", va="bottom",
                        fontsize=SMALL, color=INK)
                extremes += [row["low"], row["high"]]
        ax.set_xticks(range(len(combos)))
        ax.set_xticklabels([labels[c] for c in combos])
        ax.set_ylim(0, max(extremes) * 1.18)
        ax.yaxis.set_major_formatter(FuncFormatter(lambda value, _: time_label(value) if value else "0"))
        ax.legend(handles=[Patch(color="#7fb0e6", label="worklist only"),
                           Patch(color="#143f77", label="with the other optimizations")], frameon=False,
                  loc="upper left")
        ax.set_ylabel("time per inspection")
        ax.set_title(f"Time from scratch, {nodes_of(depth)} nodes", color=INK, loc="left")
        style(ax)
    fig.tight_layout(h_pad=1.2)
    save(fig, out, "encodings")
    table = [["config", "phase", "popp_nodes", "ms", "ci_low", "ci_high"]]
    for r in sorted(runtime, key=lambda r: (r["phase"], r["config"], r["depth"])):
        table.append([r["config"], r["phase"], nodes_of(r["depth"]), f"{r['ms']:.2f}", f"{r['low']:.2f}", f"{r['high']:.2f}"])
    tables["encodings"] = table


def memory_table(jmh, src, tables):
    path = os.path.join(src, "memory.csv")
    if not os.path.exists(path):
        return
    memory = list(csv.DictReader(open(path)))
    components = list(dict.fromkeys(r["component"] for r in memory))
    sizes = sorted({int(r["popp_nodes"]) for r in memory})
    table = [["component"] + [f"{n} nodes (KiB)" for n in sizes]]
    for component in components:
        table.append([component] + [next((r["retained_kib"] for r in memory if r["component"] == component
                                          and int(r["popp_nodes"]) == n), "") for n in sizes])
    for label, phase in (("allocated per inspection from scratch, VERIFY", "scratch"),
                         ("allocated per edit and inspection, incremental, VERIFY", "edit")):
        row = [label]
        for n in sizes:
            found = next((r for r in jmh if r["file"].startswith("modes-") and r["phase"] == phase
                          and r["config"] == "default" and r["mode"] == "VERIFY" and nodes_of(r["depth"]) == n
                          and r["alloc"] is not None), None)
            row.append(f"{found['alloc'] / 1024:.0f}" if found else "")
        table.append(row)
    tables["memory"] = table


def write_tables(tables, out):
    os.makedirs(out, exist_ok=True)
    for name, rows in tables.items():
        with open(os.path.join(out, f"{name}.csv"), "w", newline="") as handle:
            csv.writer(handle).writerows(rows)
        with open(os.path.join(out, f"{name}.tex"), "w") as handle:
            handle.write("% " + " & ".join(rows[0]) + "\n")
            for row in rows[1:]:
                handle.write(" & ".join(str(cell).replace("_", r"\_").replace("%", r"\%") for cell in row) + r" \\" + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("src")
    parser.add_argument("out", nargs="?")
    parser.add_argument("--mode", default="VERIFY")
    parser.add_argument("--nfr-ms", type=float, default=500)
    parser.add_argument("--nfr-nodes", type=int, default=42)
    args = parser.parse_args()
    out = args.out or os.path.join(args.src, "figures")
    os.makedirs(out, exist_ok=True)
    jmh = load_jmh(args.src)
    calls = load_calls(args.src)
    tables = {}
    plot_ladder(jmh, calls, args, out, tables)
    plot_ablation(jmh, calls, args, out, tables)
    plot_modes(jmh, args, out, tables)
    plot_edits(jmh, args, out, tables)
    plot_encodings(jmh, args.src, args, out, tables)
    memory_table(jmh, args.src, tables)
    write_tables(tables, os.path.join(args.src, "tables"))
    print(f"figures in {out}, tables in {os.path.join(args.src, 'tables')}")


if __name__ == "__main__":
    main()
