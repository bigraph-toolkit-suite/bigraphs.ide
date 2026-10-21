#!/usr/bin/env python3
"""
Plots the coverage benchmarks (JMH JSON from run-benchmarks.sh) for the thesis.

    python3 plot_benchmarks.py <results-dir> [<out-dir>]

Reads every *.json in <results-dir> (run-benchmarks.sh writes default.json, schedules.json, oracle.json).
Writes PDF (for LaTeX) and PNG figures plus summary.csv to <out-dir> (default: <results-dir>/figures).
"""
import csv
import glob
import json
import os
import sys
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter, NullLocator

NODES = {0: 6, 1: 18, 2: 42, 3: 90}

from bench_style import SCHEDULES, style, INK, INK2, GRID, ms_label, save, nfr, NFR_NODES, setup as setup_style

BENCH_TITLE = {
    "inspectRoot": "Inspect problem root (warm)",
    "inspectAllNodes": "Inspect all nodes (warm)",
    "editThenInspect": "Edit, then inspect (warm)",
    "editOnly": "Edit only (warm)",
    "coldFirstSession": "First session in a fresh JVM",
}
EDITS = defaultdict(dict)  # (action, length, analyzer) -> {nodes: (ms per step, error per step)}


def load(results):
    """{(benchmark, analyzer, complete): {nodes: (score, error, unit)}}; edit chains go to EDITS."""
    data = defaultdict(dict)
    for path in sorted(glob.glob(os.path.join(results, "*.json"))):
        for r in json.load(open(path)):
            p = r["params"]
            bench = r["benchmark"].rsplit(".", 1)[-1]
            m = r["primaryMetric"]
            if bench == "editInspectChain":
                try:
                    e = float(m["scoreError"])
                except (TypeError, ValueError):
                    e = 0.0
                n = int(p["length"])
                EDITS[(p["action"], n, p["analyzer"])][NODES[int(p["depth"])]] = (m["score"] / n, (e if e == e else 0.0) / n, "ms")
                continue
            try:
                err = float(m["scoreError"])   # "NaN" (a string) for single samples, e.g. the cold start
            except (TypeError, ValueError):
                err = 0.0
            if err != err:
                err = 0.0
            data[(bench, p["analyzer"], p["complete"] == "true")][NODES[int(p["depth"])]] = (m["score"], err, m["scoreUnit"])
    return data


def log_axes(ax, xs):
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks(xs)
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
    ax.xaxis.set_minor_locator(NullLocator())
    ax.yaxis.set_major_formatter(FuncFormatter(ms_label))
    ax.yaxis.set_minor_formatter(NullFormatter())
    ax.set_xlabel("POPP nodes (balanced model, all six trees)")


def y_ticks(ax):
    """Decade ticks, or 1-2-5 ticks when the data spans less than about two decades (call after plotting)."""
    lo, hi = ax.get_ylim()
    ax.yaxis.set_major_locator(LogLocator(base=10, subs=(1.0,) if hi / lo > 100 else (1.0, 2.0, 5.0)))
    ax.yaxis.set_minor_formatter(NullFormatter())
    ax.set_ylabel("Time per operation")


def line(ax, series, analyzer, dashed=False, label=""):
    """label "" = the analyzer's name; None = no legend entry."""
    label_, colour, marker, ls = style(analyzer)
    label = label_ if label == "" else ("_nolegend_" if label is None else label)
    xs = sorted(series)
    ys = [series[x][0] for x in xs]
    es = [series[x][1] for x in xs]
    # on a log axis an error bar below zero is meaningless: clip the lower half at 90 % of the value
    es = [[min(e, 0.9 * y) for e, y in zip(es, ys)], es]
    ax.errorbar(xs, ys, yerr=es, color=colour, marker=marker, linestyle="--" if dashed else ls,
                label=label, capsize=0, elinewidth=0.8, markeredgewidth=1.0,
                markerfacecolor=colour if not dashed else "white")
    return xs, ys


def fig_overview(data, out, bench="inspectRoot", name="01-overview-inspect-root"):
    """All analyzers on the complete model: the headline scaling figure."""
    order = SCHEDULES + ["oracle"]
    if not any(data.get((bench, a, True)) for a in order):
        return
    fig, ax = plt.subplots(figsize=(6.2, 4.0))
    xs_all = set()
    for a in order:
        s = data.get((bench, a, True))
        if not s:
            continue
        xs, ys = line(ax, s, a)
        xs_all |= set(xs)
        ax.annotate(ms_label(ys[-1]), (xs[-1], ys[-1]), xytext=(4, 0), textcoords="offset points",
                    va="center", fontsize=7, color=INK2)
    log_axes(ax, sorted(xs_all))
    nfr(ax)
    y_ticks(ax)
    ax.set_title(BENCH_TITLE[bench] + ", coverage mode VERIFY")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.16), frameon=False, ncol=2)
    save(fig, out, name)


def fig_whole_agent(data, out):
    """Every schedule and the hits-only bound, one panel per model size."""
    order = SCHEDULES + ["oracle"]
    present = [a for a in order if data.get(("inspectRoot", a, True))]
    if not present:
        return
    sizes = sorted({n for a in present for n in data[("inspectRoot", a, True)]})
    fig, axes = plt.subplots(1, len(sizes), figsize=(2.0 * len(sizes) + 2.6, 3.2), sharex=True)
    if len(sizes) == 1:
        axes = [axes]
    for ax, n in zip(axes, sizes):
        vals = [(a, data[("inspectRoot", a, True)].get(n)) for a in present]
        vals = [(a, v) for a, v in vals if v]
        ys = range(len(vals))
        # dots, not bars: on a log axis a bar's length means nothing
        for y, (a, v) in zip(ys, vals):
            ax.errorbar(v[0], y, xerr=v[1], color=style(a)[1], marker=style(a)[2], markersize=6,
                        elinewidth=0.8, linestyle="none")
        ax.set_xscale("log")
        ax.xaxis.set_major_formatter(FuncFormatter(ms_label))
        ax.set_xticks([1, 100, 10000])
        ax.xaxis.set_minor_locator(NullLocator())
        ax.grid(axis="x", visible=True)
        ax.set_yticks(list(ys))
        ax.set_yticklabels([style(a)[0] for a, _ in vals] if ax is axes[0] else [])
        ax.grid(axis="y", visible=False)
        ax.set_title(f"{n} nodes")
        for y, (a, v) in zip(ys, vals):
            ax.annotate(ms_label(v[0]), (v[0], y), xytext=(7, 0), textcoords="offset points", va="center",
                        fontsize=6.5, color=INK2)
        ax.set_xlim(1, 2e5)
        ax.set_ylim(len(vals) - 0.5, -0.5)
        if n == NFR_NODES:
            nfr(ax, horizontal=False)
    fig.suptitle("Schedules, inspect problem root (warm), VERIFY", fontsize=9.5, y=1.02)
    fig.tight_layout()
    save(fig, out, "02-schedules")


def fig_gap_to_oracle(data, out):
    """Factor above the hits-only bound: how much of each analyzer's time is spent on misses (and pruning)."""
    oracle = data.get(("inspectRoot", "oracle", True))
    if not oracle:
        return
    order = SCHEDULES
    fig, ax = plt.subplots(figsize=(6.2, 3.8))
    xs_all = set()
    ends = []
    for a in order:
        s = data.get(("inspectRoot", a, True))
        if not s:
            continue
        common = {n: (s[n][0] / oracle[n][0], 0, "") for n in s if n in oracle}
        xs, ys = line(ax, common, a)
        xs_all |= set(xs)
        ends.append((ys[-1], xs[-1]))
    # end labels, nudged apart when two lines end close together
    ends.sort()
    last = None
    for y, x in ends:
        dy = 0 if last is None or y / last > 1.25 else 7
        ax.annotate(f"{y:.1f}×", (x, y), xytext=(5, dy), textcoords="offset points", va="center", fontsize=7,
                    color=INK2)
        last = y if dy == 0 else last
    ax.axhline(1, color=style("oracle")[1], linewidth=1.2, linestyle=":")
    ax.annotate("hits only (perfect pruning) = 1×", (max(xs_all), 1), xytext=(0, 5), textcoords="offset points",
                fontsize=7, color=INK2, ha="right")
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks(sorted(xs_all))
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
    ax.xaxis.set_minor_locator(NullLocator())
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}×"))
    ax.set_xlabel("POPP nodes")
    ax.set_ylabel("Time / hits-only time")
    ax.set_title("Distance to the hits-only bound (inspect root, warm, VERIFY)")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.18), frameon=False, ncol=2)
    save(fig, out, "03-gap-to-hits-only")


def fig_cold(data, out):
    order = SCHEDULES
    if not any(data.get(("coldFirstSession", a, True)) for a in order):
        return
    fig, ax = plt.subplots(figsize=(6.2, 4.0))
    xs_all = set()
    labelled = {"rounds", "workset", "guards-first", "workset-prefilter-seeded"}
    for a in order:
        s = data.get(("coldFirstSession", a, True))
        if s:
            xs, ys = line(ax, s, a)
            xs_all |= set(xs)
            if a in labelled:
                ax.annotate(ms_label(ys[-1]), (xs[-1], ys[-1]), xytext=(5, 0), textcoords="offset points",
                            va="center", fontsize=7, color=INK2)
    log_axes(ax, sorted(xs_all))
    nfr(ax)
    y_ticks(ax)
    ax.set_ylabel("Time of the first session")
    ax.set_title(BENCH_TITLE["coldFirstSession"] + ", VERIFY")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.16), frameon=False, ncol=2)
    save(fig, out, "04-cold-first-session")


def fig_fast(data, out):
    """The extension's analyzer (incremental) and the default schedule from scratch on every benchmark, with the
    hits-only oracle as the reference where it is measured (inspectRoot); solid = complete, dashed = partial model.
    The domain analyzer (a different algorithm, no rewriting) is only in summary.csv."""
    shown = ["incremental", "workset-prefilter-seeded", "oracle"]
    benches = ["inspectRoot", "inspectAllNodes", "editThenInspect", "editOnly"]
    if not any(data.get((b, a, c)) for b in benches for a in shown for c in (True, False)):
        return
    fig, axes = plt.subplots(2, 2, figsize=(6.4, 5.2), sharex=True)
    for ax, b in zip(axes.flat, benches):
        xs_all = set()
        for a in shown:
            for complete in (True, False):
                s = data.get((b, a, complete))
                if s:
                    xs, _ = line(ax, s, a, dashed=not complete,
                                 label=style(a)[0] if complete else None)
                    xs_all |= set(xs)
        log_axes(ax, sorted(xs_all))
        if b != "editOnly":     # the edit alone takes microseconds; the NFR is about the coverage answer
            nfr(ax, label=ax is axes[0][0])
        y_ticks(ax)
        ax.set_title(BENCH_TITLE[b])
        if ax not in axes[1]:
            ax.set_xlabel("")
        if ax not in axes[:, 0]:
            ax.set_ylabel("")
    handles, labels = axes[0][0].get_legend_handles_labels()
    from matplotlib.lines import Line2D
    handles += [Line2D([], [], color=INK2, linestyle="-"), Line2D([], [], color=INK2, linestyle="--")]
    labels += ["complete model", "partially proven model"]
    fig.legend(handles, labels, loc="lower center", ncol=3, frameon=False, bbox_to_anchor=(0.5, -0.05))
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    save(fig, out, "05-default-analyzer-all-benchmarks")


def fig_edit_chains(out):
    """Edit chains: time per step (edit + inspection), one panel per action and chain length."""
    if not EDITS:
        return
    actions = [a for a in ["LINK", "TYPE", "MOVE", "NODE", "MIXED"] if any(k[0] == a for k in EDITS)]
    lengths = sorted({k[1] for k in EDITS})
    order = ["workset-prefilter-seeded", "incremental", "incremental-rounds"]
    analyzers = [a for a in order if any(k[2] == a for k in EDITS)] + sorted({k[2] for k in EDITS} - set(order))
    fig, axes = plt.subplots(len(lengths), len(actions), figsize=(1.75 * len(actions) + 0.8, 2.1 * len(lengths) + 0.9),
                             sharex=True, sharey=True, squeeze=False)
    for row, n in enumerate(lengths):
        for col, a in enumerate(actions):
            ax = axes[row][col]
            xs_all = set()
            for an in analyzers:
                s = EDITS.get((a, n, an))
                if s:
                    xs, _ = line(ax, s, an)
                    xs_all |= set(xs)
            log_axes(ax, sorted(xs_all))
            nfr(ax, label=(row == 0 and col == 0))
            ax.set_xlabel("POPP nodes" if row == len(lengths) - 1 else "")
            ax.set_ylabel(f"ms per step, chains of {n}" if col == 0 else "")
            if row == 0:
                ax.set_title(a.lower())
    for ax in axes.flat:
        y_ticks(ax)
    for row in range(len(lengths)):
        for col in range(len(actions)):
            axes[row][col].set_ylabel(f"ms per step\n(chains of {lengths[row]})" if col == 0 else "")
    legend = {}
    for ax in axes.flat:
        for h, l in zip(*ax.get_legend_handles_labels()):
            legend.setdefault(l, h)
    fig.legend(list(legend.values()), list(legend.keys()), loc="lower center", ncol=3, frameon=False,
               bbox_to_anchor=(0.5, -0.02), fontsize=7.5)
    fig.suptitle("Edit, then inspect the problem root: time per step, VERIFY", fontsize=9.5)
    fig.tight_layout(rect=(0, 0.04 + 0.025 * ((len(legend) + 2) // 3), 1, 0.97))
    save(fig, out, "06-edit-chains")


def write_summary(data, out):
    path = os.path.join(out, "summary.csv")
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["benchmark", "analyzer", "model", "popp_nodes", "score_ms", "error_ms_99.9"])
        for (b, a, c), s in sorted(data.items()):
            for n in sorted(s):
                w.writerow([b, a, "complete" if c else "partial", n, f"{s[n][0]:.6g}", f"{s[n][1]:.3g}"])
        for (action, length, a), s in sorted(EDITS.items()):
            for n in sorted(s):
                w.writerow([f"editInspectChain-{action}-x{length} (per step)", a, "complete", n,
                            f"{s[n][0]:.6g}", f"{s[n][1]:.3g}"])
    print("wrote summary.csv")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    results = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(results, "figures")
    os.makedirs(out, exist_ok=True)
    setup_style()
    data = load(results)
    fig_overview(data, out)
    fig_whole_agent(data, out)
    fig_gap_to_oracle(data, out)
    fig_cold(data, out)
    fig_fast(data, out)
    fig_edit_chains(out)
    write_summary(data, out)


if __name__ == "__main__":
    main()
