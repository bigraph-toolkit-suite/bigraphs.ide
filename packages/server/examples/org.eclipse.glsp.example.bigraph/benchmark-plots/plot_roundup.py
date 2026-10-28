#!/usr/bin/env python3
"""
Plots the roundup written by CoverageBenchmark (roundup.csv).

    python3 plot_roundup.py <result-dir> [<out-dir>] [--mode VERIFY] [--nfr-ms 500 --nfr-nodes 42]

Figures (PNG + PDF, default <result-dir>/figures):
  roundup        each variant per model size, log scale. From scratch: the session in the mode (median of the runs,
                 bars from the fastest to the slowest run). Incremental: one edit, then the session (mean over the
                 edits of their medians, bars a 99.9 % confidence interval over the edits). The dashed line is the
                 response-time requirement.
  roundup-calls  where the matcher calls go on the model of --nfr-nodes POPP nodes: rule hits, rule misses, guard calls
Also writes summary.csv (per variant, phase and model size: time and speed-up over the baseline).
"""
import argparse
import csv
import math
import os
import statistics
from collections import defaultdict

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter

from bench_style import INK, INK2, LABELS, ONE_LINE, OUTCOME, SIZES, ordered, save, style, time_label

Z_999 = 3.29  # two-sided 99.9 % of the normal distribution


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
    rows = list(csv.DictReader(open(os.path.join(args.src, "roundup.csv"))))
    variants = ordered({r["variant"] for r in rows})
    sizes = sorted({int(r["popp_nodes"]) for r in rows})

    # per (variant, nodes): value, lower, upper, calls
    scratch, incremental = {}, {}
    edits = defaultdict(list)
    calls = defaultdict(lambda: [0, 0, 0, 0])
    for r in rows:
        key = (r["variant"], int(r["popp_nodes"]))
        if r["phase"] == "scratch" and r["mode"] == args.mode:
            scratch[key] = (float(r["median_ms"]), float(r["min_ms"]), float(r["max_ms"]))
        elif r["phase"] == "incremental":
            edits[key].append(float(r["median_ms"]))
        if r["phase"] == "scratch" and r["mode"] != args.mode:
            continue
        hits, rule_calls = int(r["hits"]), int(r["rule_calls"])
        holds, guard_calls = int(r["guard_holds"]), int(r["guard_calls"])
        counts = calls[(r["phase"],) + key]
        counts[0] += hits
        counts[1] += rule_calls - hits
        counts[2] += holds
        counts[3] += guard_calls - holds
    for key, times in edits.items():
        mean = statistics.mean(times)
        half = Z_999 * statistics.stdev(times) / math.sqrt(len(times)) if len(times) > 1 else 0
        incremental[key] = (mean, max(mean - half, mean * 0.05), mean + half)

    with open(os.path.join(args.src, "summary.csv"), "w") as summary:
        summary.write("variant,phase,popp_nodes,ms,speedup_vs_baseline\n")
        for phase, data in (("scratch", scratch), ("incremental", incremental)):
            for (variant, nodes), (value, _, _) in sorted(data.items(), key=lambda item: (item[0][1], variants.index(item[0][0]))):
                base = data.get(("baseline", nodes))
                summary.write(f"{variant},{phase},{nodes},{value:.1f},{base[0] / value if base else float('nan'):.2f}\n")

    fig, axes = plt.subplots(1, 2, figsize=(12.5, 4.8), sharey=True,
                             gridspec_kw={"width_ratios": [len(variants), max(1, len(variants) - (0 if any(v == "hits-only" for v, _ in incremental) else 1))]})
    panels = [(axes[0], scratch, f"From scratch: the {args.mode} session"),
              (axes[1], incremental, "Incremental: one MIXED edit, then the session")]
    for ax, data, title in panels:
        shown = [v for v in variants if any((v, n) in data for n in sizes)]
        shown_sizes = [n for n in sizes if any((v, n) in data for v in shown)]
        width = 0.8 / len(sizes)
        for i, nodes in enumerate(sizes):
            if nodes not in shown_sizes:
                continue
            for x, variant in enumerate(shown):
                if (variant, nodes) not in data:
                    continue
                value, low, high = data[(variant, nodes)]
                position = x + (i - (len(sizes) - 1) / 2) * width
                ax.bar(position, value, width=width * 0.9, color=SIZES[i % len(SIZES)])
                ax.errorbar(position, value, yerr=[[value - low], [high - value]], fmt="none", ecolor=INK2,
                            elinewidth=0.9, capsize=2)
                ax.text(position, high * 1.12, time_label(value), rotation=90, ha="center", va="bottom", fontsize=7.5,
                        color=INK2)
        ax.set_xticks(range(len(shown)))
        ax.set_xticklabels([LABELS.get(v, v) for v in shown], fontsize=8.5)
        ax.set_yscale("log")
        ax.yaxis.set_major_locator(LogLocator(base=10))
        ax.yaxis.set_major_formatter(FuncFormatter(lambda value, _: time_label(value)))
        ax.yaxis.set_minor_formatter(NullFormatter())
        ax.axhline(args.nfr_ms, color="#d03b3b", linestyle="--", linewidth=1.2)
        ax.set_title(title, color=INK)
        style(ax)
    axes[0].set_ylabel("time (log)", fontsize=10)
    low = min(v[0] for data in (scratch, incremental) for v in data.values())
    high = max(v[2] for data in (scratch, incremental) for v in data.values())
    axes[0].set_ylim(10 ** math.floor(math.log10(max(low, 0.1))), 10 ** (math.ceil(math.log10(high)) + 0.6))
    # a confidence interval reaching below the axis is cut there
    handles = [Line2D([], [], color="#d03b3b", linestyle="--", label=f"NFR: < {args.nfr_ms:g} ms at {args.nfr_nodes} nodes")]
    handles += [Patch(color=SIZES[i % len(SIZES)], label=f"{n} POPP nodes") for i, n in enumerate(sizes)]
    fig.legend(handles=handles, loc="lower center", ncol=len(handles), frameon=False, bbox_to_anchor=(0.5, -0.04))
    runs = "median of the runs, fastest to slowest; incremental: mean over the edits, 99.9 % CI"
    fig.suptitle(f"Each optimisation per model size, {args.mode} ({runs})", fontsize=11, color=INK)
    fig.tight_layout(rect=(0, 0.05, 1, 0.95))
    save(fig, out, "roundup")

    # where the matcher calls go
    nodes = args.nfr_nodes if args.nfr_nodes in sizes else max(sizes)
    fig, axes = plt.subplots(1, 2, figsize=(12, 3.8))
    parts = ["hit", "miss", "guard-pass", "guard-fail"]
    for ax, phase, title in ((axes[0], "scratch", f"From scratch: the {args.mode} session"),
                             (axes[1], "incremental", "Incremental: all edits")):
        shown = [v for v in variants if (phase, v, nodes) in calls]
        for y, variant in enumerate(shown):
            left = 0
            for index, part in enumerate(parts):
                value = calls[(phase, variant, nodes)][index]
                if value:
                    ax.barh(y, value, left=left, height=0.62, color=OUTCOME[part][1], edgecolor="white", linewidth=1)
                left += value
            ax.text(left, y, f"  {left}", va="center", fontsize=8.5, color=INK2)
        ax.set_yticks(range(len(shown)))
        ax.set_yticklabels([ONE_LINE.get(v, v) for v in shown], fontsize=8.5)
        ax.invert_yaxis()
        ax.set_xlabel("matcher calls")
        ax.set_title(f"{title}, {nodes} nodes", color=INK)
        style(ax)
        ax.grid(axis="y", visible=False)
        ax.grid(axis="x", color="#e4e3df")
    fig.legend(handles=[Patch(color=OUTCOME[p][1], label=OUTCOME[p][0]) for p in parts], loc="lower center", ncol=4,
               frameon=False, bbox_to_anchor=(0.5, -0.06))
    fig.suptitle("Where the matcher calls go", fontsize=11, color=INK)
    fig.tight_layout(rect=(0, 0.05, 1, 0.94))
    save(fig, out, "roundup-calls")
    print(f"figures in {out}")


if __name__ == "__main__":
    main()
