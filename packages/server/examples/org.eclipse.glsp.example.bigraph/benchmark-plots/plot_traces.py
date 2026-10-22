#!/usr/bin/env python3
"""
Plots the session traces written by RewritingTraceReport (rounds.csv, calls.csv) side by side per variant.

    python3 plot_traces.py <trace-dir> [<out-dir>]

Figures (PDF + PNG, default <trace-dir>/figures):
  trace-rounds                  per round: active rules, matcher calls, misses, hits; one column per model size
  trace-cumulative              over time: hits and misses so far, per variant (log time axis); one column per size
  trace-timeline-<n>-nodes      every matcher call of one session per variant, on a common time axis
"""
import csv
import os
import sys
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter, MaxNLocator

from bench_style import INK, INK2, SCHEDULES, style, setup, save, ms_label

ORDER = SCHEDULES + ["oracle"]
OUTCOME = {  # call outcome -> (label, colour)
    "hit": ("rule call: hit (upgraded a node)", "#2a78d6"),
    "miss": ("rule call: miss (no match)", "#d03b3b"),
    "stale": ("rule call: only stale matches", "#fab219"),
    "guard-pass": ("guard call: holds", "#9b9a95"),
    "guard-fail": ("guard call: fails (rules dropped)", "#f2b8b5"),
}


def read(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def variants_in(rows):
    present = {r["variant"] for r in rows}
    return [v for v in ORDER if v in present] + sorted(present - set(ORDER))


def outcome(c):
    if c["kind"] == "GUARD":
        return "guard-pass" if c["matched"] == "true" else "guard-fail"
    if c["upgraded"] == "true":
        return "hit"
    return "stale" if c["matched"] == "true" else "miss"


def fig_rounds(rounds, out):
    sizes = sorted({int(r["popp_nodes"]) for r in rounds})
    metrics = [
        ("active", "Active rules", lambda r: int(r["active"])),
        ("calls", "Matcher calls", lambda r: int(r["guard_calls"]) + int(r["rule_calls"])),
        ("misses", "Wasted calls",
         lambda r: int(r["misses"]) + int(r["stale"]) + int(r["guard_calls"]) - int(r["guard_pass"])),
        ("hits", "Hits", lambda r: int(r["hits"])),
    ]
    fig, axes = plt.subplots(len(metrics), len(sizes), figsize=(2.6 * len(sizes) + 0.6, 2.0 * len(metrics)),
                             sharex="col", squeeze=False)
    by = defaultdict(list)
    for r in rounds:
        by[(r["variant"], int(r["popp_nodes"]))].append(r)
    for col, n in enumerate(sizes):
        for row, (key, title, f) in enumerate(metrics):
            ax = axes[row][col]
            for v in variants_in(rounds):
                rs = sorted(by.get((v, n), []), key=lambda r: int(r["round"]))
                if not rs:
                    continue
                label, colour, marker, ls = style(v)
                ax.plot([int(r["round"]) for r in rs], [f(r) for r in rs], color=colour, marker=marker,
                        linestyle=ls, markersize=4, linewidth=1.3, label=label)
            ax.set_yscale("symlog", linthresh=1)
            ax.yaxis.set_major_formatter(FuncFormatter(lambda y, _: f"{y:g}"))
            ax.xaxis.set_major_locator(MaxNLocator(integer=True))
            if row == 0:
                ax.set_title(f"{n} nodes")
            if col == 0:
                ax.set_ylabel(title, fontsize=8)
            if row == len(metrics) - 1:
                ax.set_xlabel("Round (wave)")
    handles, labels = axes[0][0].get_legend_handles_labels()
    for ax in axes.flat:  # variants missing at the smallest size
        for h, l in zip(*ax.get_legend_handles_labels()):
            if l not in labels:
                handles.append(h); labels.append(l)
    fig.legend(handles, labels, loc="lower center", ncol=2, frameon=False, bbox_to_anchor=(0.5, -0.06))
    fig.suptitle("Per round, VERIFY, complete balanced model. Matcher calls = guard + rule calls; wasted = rule calls "
                 "without an upgrade + failing guards;\nhits = rule calls that upgraded a node; active = rules queued "
                 "(without worklist: all rules)", fontsize=8.5)
    fig.tight_layout(rect=(0, 0.06, 1, 0.98))
    save(fig, out, "trace-rounds")


def fig_cumulative(calls, out):
    sizes = sorted({int(c["popp_nodes"]) for c in calls})
    fig, axes = plt.subplots(2, len(sizes), figsize=(2.7 * len(sizes) + 0.6, 5.0), squeeze=False)
    by = defaultdict(list)
    for c in calls:
        by[(c["variant"], int(c["popp_nodes"]))].append(c)
    for col, n in enumerate(sizes):
        for row, (what, title) in enumerate([("hit", "Hits so far"), ("waste", "Wasted calls so far")]):
            ax = axes[row][col]
            for v in variants_in(calls):
                cs = sorted(by.get((v, n), []), key=lambda c: float(c["start_ms"]))
                if not cs:
                    continue
                xs, ys, k = [], [], 0
                for c in cs:
                    o = outcome(c)
                    if (what == "hit" and o == "hit") or (what == "waste" and o in ("miss", "stale", "guard-fail")):
                        k += 1
                    xs.append(float(c["start_ms"]) + float(c["duration_ms"]))
                    ys.append(k)
                label, colour, marker, ls = style(v)
                ax.step(xs, ys, where="post", color=colour, linestyle=ls, linewidth=1.3, label=label)
                ax.plot(xs[-1:], ys[-1:], color=colour, marker=marker, markersize=4)
            ax.set_xscale("log")
            ax.xaxis.set_major_formatter(FuncFormatter(ms_label))
            if what == "waste":
                ax.set_yscale("symlog", linthresh=10)
                ax.yaxis.set_major_formatter(FuncFormatter(lambda y, _: f"{y:g}"))
                ax.set_ylim(bottom=-0.5)
            if row == 0:
                ax.set_title(f"{n} nodes")
            if col == 0:
                ax.set_ylabel(title)
            if row == 1:
                ax.set_xlabel("Time since session start")
    handles, labels = [], []
    for ax in axes.flat:
        for h, l in zip(*ax.get_legend_handles_labels()):
            if l not in labels:
                handles.append(h); labels.append(l)
    fig.legend(handles, labels, loc="lower center", ncol=2, frameon=False, bbox_to_anchor=(0.5, -0.02))
    fig.suptitle("Over the session, VERIFY (end marker = session end)", fontsize=9.5)
    fig.tight_layout(rect=(0, 0.17, 1, 0.97))
    save(fig, out, "trace-cumulative")


def fig_timeline(calls, rounds, out, n, skip=()):
    """One lane per variant: every matcher call as a bar from its start to its end, coloured by outcome."""
    cs_by = defaultdict(list)
    for c in calls:
        if int(c["popp_nodes"]) == n and c["variant"] not in skip:
            cs_by[c["variant"]].append(c)
    rs_by = defaultdict(list)
    for r in rounds:
        if int(r["popp_nodes"]) == n:
            rs_by[r["variant"]].append(r)
    lanes = [v for v in variants_in(calls) if v in cs_by]
    if not lanes:
        return
    fig, ax = plt.subplots(figsize=(7.0, 0.42 * len(lanes) + 1.4))
    for i, v in enumerate(lanes):
        for kind in OUTCOME:
            segs = [(float(c["start_ms"]), max(float(c["duration_ms"]), 0.0)) for c in cs_by[v] if outcome(c) == kind]
            if segs:
                ax.broken_barh(segs, (i - 0.32, 0.64), facecolors=OUTCOME[kind][1], edgecolors="none", linewidth=0)
        for r in rs_by.get(v, []):  # round boundaries
            end = float(r["start_ms"]) + float(r["duration_ms"])
            ax.plot([end, end], [i - 0.40, i - 0.33], color=INK, linewidth=0.8)
            ax.plot([end, end], [i + 0.33, i + 0.40], color=INK, linewidth=0.8)
        last = max(float(c["start_ms"]) + float(c["duration_ms"]) for c in cs_by[v])
        ax.annotate(ms_label(last), (last, i), xytext=(4, 0), textcoords="offset points", va="center", fontsize=7,
                    color=INK2)
    ax.set_yticks(range(len(lanes)))
    ax.set_yticklabels([style(v)[0] for v in lanes])
    ax.set_ylim(len(lanes) - 0.5, -0.5)
    ax.grid(axis="y", visible=False)
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: "0" if v == 0 else ms_label(v)))
    ax.set_xlabel("Time since session start (linear)")
    ax.set_title(f"Every matcher call of one session, {n} nodes, VERIFY"
                 )
    handles = [Patch(facecolor=OUTCOME[k][1], label=OUTCOME[k][0]) for k in OUTCOME]
    handles.append(Line2D([], [], color=INK, linewidth=0.8, label="end of a round (ticks above and below the lane)"))
    ax.legend(handles=handles, loc="upper center", bbox_to_anchor=(0.5, -0.22), ncol=3, frameon=False)
    save(fig, out, f"trace-timeline-{n}-nodes")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    d = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(d, "figures")
    os.makedirs(out, exist_ok=True)
    setup()
    rounds, calls = read(os.path.join(d, "rounds.csv")), read(os.path.join(d, "calls.csv"))
    fig_rounds(rounds, out)
    fig_cumulative(calls, out)
    for n in sorted({int(c["popp_nodes"]) for c in calls}):
        fig_timeline(calls, rounds, out, n)


if __name__ == "__main__":
    main()
