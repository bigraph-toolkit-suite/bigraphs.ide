#!/usr/bin/env python3
"""The rule worklist under different rule sets and encodings (the encodings step of run-benchmarks.sh).

    python3 plot_encodings.py <results-dir>/encodings [out-dir]

08-encodings-static: per encoding, the number of rules and how many rules a rule enables (control-pair analysis vs.
overlap analysis), incl. the two earlier encodings that exist only as rule sets.
09-encodings-worklist: per gate encoding x enabling analysis, (a) the worklist schedules from scratch (JMH inspectRoot),
(b) queued rule calls per session after the seed vs. hits (WorklistPrecision, worklist + seed, all modes, complete and
partial models), (c) the incremental worklist schedules per edit step on identical edits (EditChainBreakdown, MIXED).
"""
import csv
import glob
import json
import os
import sys
from collections import defaultdict

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import LogLocator

from bench_style import canon, INK, INK2, GRID, BLUE, ORANGE, AQUA, GREY, ms_label, save, nfr, setup as setup_style

NODES = {0: 6, 1: 18, 2: 42, 3: 90}
COMBOS = [  # (gate rules, enabling), label, colour
    (("enumerated", "controls"), "combination gate rules, control pairs (before)", GREY),
    (("enumerated", "overlap"), "combination gate rules, overlap", BLUE),
    (("threshold", "controls"), "threshold gate rules, control pairs", ORANGE),
    (("threshold", "overlap"), "threshold gate rules, overlap (now)", AQUA),
]
SCHEDULE_STYLE = {"rounds": ("rounds", ":", "v"), "worklist": ("worklist", "--", "o"),
                  "worklist-seeded": ("worklist + seed", "-", "s"),
                  "worklist-guards-seeded-first": ("worklist + seed + guards", "-.", "D"),
                  "incremental-worklist": ("worklist", "--", "o"), "incremental-worklist-seeded": ("worklist + seed", "-", "s"),
                  "incremental-worklist-guards-seeded-first": ("worklist + seed + guards", "-.", "D")}


def log_axes(ax, xs):
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks(xs, [str(x) for x in xs])
    ax.minorticks_off()
    ax.set_xlabel("POPP nodes")
    ax.grid(color=GRID, lw=0.6)
    ax.set_axisbelow(True)
    ax.yaxis.set_major_locator(LogLocator(base=10, subs=(1, 2, 5)))
    ax.yaxis.set_major_formatter(plt.FuncFormatter(ms_label))


def static_figure(d, out):
    path = os.path.join(d, "encodings-static.csv")
    if not os.path.exists(path):
        return
    rows = list(csv.DictReader(open(path)))
    names = [r["encoding"] for r in rows]
    fig, (a, b) = plt.subplots(1, 2, figsize=(7.4, 2.3 + 0.32 * len(rows)), sharey=True)
    ys = range(len(rows))
    a.barh(ys, [int(r["rules"]) for r in rows], color=INK2, height=0.6)
    for y, r in zip(ys, rows):
        a.text(int(r["rules"]) + 4, y, r["rules"], va="center", fontsize=8)
    a.set_yticks(list(ys), names)
    a.invert_yaxis()
    a.set_xlabel("rules")
    a.set_title("Size of the rule set")
    h = 0.36
    for off, col, label, colour in [(-h / 2, "enables_controls", "control pairs", GREY), (h / 2, "enables_overlap", "overlap", AQUA)]:
        share = [100 * float(r[col]) / int(r["rules"]) for r in rows]
        b.barh([y + off for y in ys], share, height=h, color=colour, label=label)
        for y, s, r in zip(ys, share, rows):
            b.text(s + 1, y + off, f"{float(r[col]):.1f}", va="center", fontsize=7.5)
    b.set_xlabel("% of all rules a rule enables (label: number of rules)")
    b.set_title("What one rewrite queues")
    b.legend(frameon=False, loc="lower right")
    for ax in (a, b):
        ax.grid(axis="x", color=GRID, lw=0.6)
        ax.set_axisbelow(True)
    fig.tight_layout()
    save(fig, out, "08-encodings-static")


def load_jmh(d):
    data = defaultdict(dict)  # (combo, analyzer) -> {nodes: ms}
    for (combo, _, _) in COMBOS:
        path = os.path.join(d, f"{combo[0]}-{combo[1]}.json")
        if not os.path.exists(path):
            continue
        for r in json.load(open(path)):
            p = r["params"]
            data[(combo, canon(p["analyzer"]))][NODES[int(p["depth"])]] = r["primaryMetric"]["score"]
    return data


def load_precision(d):
    data = {}  # combo -> {nodes: (queued per session, hits per session)}
    for (combo, _, _) in COMBOS:
        path = os.path.join(d, f"precision-{combo[0]}-{combo[1]}", "worklist-precision.csv")
        if not os.path.exists(path):
            path = path.replace("worklist-precision", "workset-precision")  # results before the rename
        if not os.path.exists(path):
            continue
        acc = defaultdict(lambda: [0, 0, 0])
        for r in csv.DictReader(open(path)):
            a = acc[int(r["popp_nodes"])]
            a[0] += int(r["queued_after_seed"])
            a[1] += int(r["hits"])
            a[2] += 1
        data[combo] = {n: (q / k, h / k) for n, (q, h, k) in acc.items()}
    return data


def load_breakdown(d):
    data = defaultdict(dict)  # (combo, analyzer) -> {nodes: step ms}
    for (combo, _, _) in COMBOS:
        path = os.path.join(d, f"breakdown-{combo[0]}-{combo[1]}.csv")
        if not os.path.exists(path):
            continue
        for r in csv.DictReader(open(path)):
            data[(combo, canon(r["analyzer"]))][int(r["popp_nodes"])] = float(r["step_ms"])
    return data


def worklist_figure(d, out):
    jmh, prec, bd = load_jmh(d), load_precision(d), load_breakdown(d)
    if not (jmh or prec or bd):
        return
    fig, axes = plt.subplots(1, 3, figsize=(10.5, 3.6))
    ax = axes[0]
    xs = set()
    for (combo, analyzer), s in sorted(jmh.items(), key=lambda kv: kv[0][1]):
        colour = next(c for k, _, c in COMBOS if k == combo)
        _, ls, marker = SCHEDULE_STYLE.get(analyzer, (analyzer, "-", "o"))
        n = sorted(s)
        ax.plot(n, [s[x] for x in n], color=colour, ls=ls, marker=marker, ms=3.5, lw=1.3)
        xs |= set(n)
    if xs:
        log_axes(ax, sorted(xs))
        nfr(ax)
    ax.set_ylabel("time")
    ax.set_title("From scratch: inspect problem root", fontsize=9)

    ax = axes[1]
    xs = set()
    for combo, label, colour in COMBOS:
        s = prec.get(combo)
        if not s:
            continue
        n = sorted(s)
        ax.plot(n, [s[x][0] for x in n], color=colour, marker="s", ms=3.5, lw=1.3)
        xs |= set(n)
    if prec:
        # hits depend on the rule family only (threshold gate nodes may take two steps), not on the enabling analysis
        for family in ("enumerated", "threshold"):
            s = next((v for (c, _), v in prec.items() if c == family), None)
            if not s:
                continue
            colour = next(col for (c, e), _, col in COMBOS if c == family and e == "overlap")
            n = sorted(x for x in s if s[x][1] > 0)
            ax.plot(n, [s[x][1] for x in n], color=colour, ls=":", marker="x", ms=4, lw=1.2)
        n = sorted(next(iter(prec.values())))
        ax.set_xscale("log")
        ax.set_ylim(bottom=0)  # linear: the gap between queued calls and hits is the point, log hid its size
        ax.set_xticks(n, [str(x) for x in n])
        ax.minorticks_off()
        ax.set_xlabel("POPP nodes")
        ax.grid(color=GRID, lw=0.6)
    ax.set_ylabel("rule calls per session after the seed")
    ax.set_title("Worklist + seed: queued calls vs. hits (dotted)", fontsize=9)

    ax = axes[2]
    xs = set()
    for (combo, analyzer), s in sorted(bd.items(), key=lambda kv: kv[0][1]):
        colour = next(c for k, _, c in COMBOS if k == combo)
        _, ls, marker = SCHEDULE_STYLE.get(analyzer, (analyzer, "-", "o"))
        n = sorted(s)
        ax.plot(n, [s[x] for x in n], color=colour, ls=ls, marker=marker, ms=3.5, lw=1.3)
        xs |= set(n)
    if xs:
        log_axes(ax, sorted(xs))
        nfr(ax, label=False)
    ax.set_title("Incremental: one edit step (MIXED)", fontsize=9)

    handles = [Line2D([], [], color=c, lw=2) for _, _, c in COMBOS] + \
              [Line2D([], [], color=INK2, ls=ls, marker=m, ms=3.5) for (_, ls, m) in
               [SCHEDULE_STYLE["rounds"], SCHEDULE_STYLE["worklist"], SCHEDULE_STYLE["worklist-seeded"],
                SCHEDULE_STYLE["worklist-guards-seeded-first"]]] + \
              [Line2D([], [], color=INK, ls=":", marker="x")]
    labels = [l for _, l, _ in COMBOS] + ["rounds (no worklist)", "worklist", "worklist + seed", "worklist + seed + guards (default)", "hits (per gate rule family)"]
    fig.legend(handles, labels, loc="lower center", ncol=4, frameon=False, bbox_to_anchor=(0.5, -0.02), fontsize=8)
    fig.suptitle("The rule worklist depends on the rule set and the encoding", fontsize=10)
    fig.tight_layout(rect=(0, 0.13, 1, 0.95))
    save(fig, out, "09-encodings-worklist")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    d = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(d, "figures")
    os.makedirs(out, exist_ok=True)
    setup_style()
    static_figure(d, out)
    worklist_figure(d, out)


if __name__ == "__main__":
    main()
