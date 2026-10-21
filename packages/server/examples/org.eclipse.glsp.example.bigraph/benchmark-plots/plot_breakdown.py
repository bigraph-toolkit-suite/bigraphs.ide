#!/usr/bin/env python3
"""Where the time of an edit step goes: stacked phases per analyzer, one panel per model size.

    python3 plot_breakdown.py <results-dir> [out-dir]

Reads every breakdown-*.csv in <results-dir> (written by EditChainBreakdown via run-benchmarks.sh). One figure per
file and action: a bar per analyzer, split into the phases of a traced step (build, sync, prefilter, matcher calls that
hit, matcher calls that miss, guards, rewrite, readout); the grey tick is the untraced step time. The hits-only bound
is everything but the misses and the guards (and the prefilter).
"""
import csv
import glob
import os
import sys
from collections import defaultdict

import matplotlib.pyplot as plt

from bench_style import SCHEDULES, style, INK, INK2, GRID, save, nfr, NFR_NODES, setup as setup_style

PHASES = [  # column, label, colour
    ("build_ms", "build", "#b9b8b3"),
    ("sync_ms", "sync", "#8c8b86"),
    ("prefilter_ms", "prefilter", "#1baf7a"),
    ("hit_match_ms", "matcher: hits", "#2a78d6"),
    ("rewrite_ms", "rewrite", "#6250d6"),
    ("readout_ms", "readout", "#52514e"),
    ("miss_match_ms", "matcher: misses", "#eb6834"),
    ("guard_ms", "guards", "#e8b04b"),
]


def order_key(analyzer):
    base = analyzer[len("incremental-"):] if analyzer.startswith("incremental-") else analyzer
    if analyzer == "incremental":
        base = "workset-prefilter-seeded"
    return SCHEDULES.index(base) if base in SCHEDULES else len(SCHEDULES)


def plot(path, out):
    rows = list(csv.DictReader(open(path)))
    if not rows:
        return
    name = os.path.splitext(os.path.basename(path))[0]
    by_action = defaultdict(list)
    for r in rows:
        by_action[r["action"]].append(r)
    for action, rs in by_action.items():
        depths = sorted({int(r["depth"]) for r in rs})
        analyzers = sorted({r["analyzer"] for r in rs}, key=order_key)
        fig, axes = plt.subplots(1, len(depths), figsize=(3.1 * len(depths) + 1.6, 0.42 * len(analyzers) + 1.4),
                                 sharey=True, squeeze=False)
        for ax, d in zip(axes[0], depths):
            here = {r["analyzer"]: r for r in rs if int(r["depth"]) == d}
            for y, a in enumerate(analyzers):
                r = here.get(a)
                if not r:
                    continue
                left = 0.0
                for col, label, colour in PHASES:
                    v = float(r[col])
                    ax.barh(y, v, left=left, color=colour, height=0.7, label=label if y == 0 else None)
                    left += v
                ax.plot([float(r["step_ms"])] * 2, [y - 0.42, y + 0.42], color=INK, lw=1.2)
            nodes = next(iter(here.values()))["popp_nodes"] if here else "?"
            if str(nodes) == str(NFR_NODES):
                nfr(ax, horizontal=False)
            ax.set_title(f"{nodes} POPP nodes")
            ax.set_xlabel("ms per step")
            ax.set_xlim(left=0)
            ax.grid(axis="x", color=GRID, lw=0.6)
            ax.set_axisbelow(True)
        axes[0][0].set_yticks(range(len(analyzers)), [style(a)[0] for a in analyzers])
        axes[0][0].invert_yaxis()
        handles, labels = axes[0][0].get_legend_handles_labels()
        seen = {}
        for h, l in zip(handles, labels):
            seen.setdefault(l, h)
        from matplotlib.lines import Line2D
        seen["step (untraced)"] = Line2D([], [], color=INK, lw=1.2)
        fig.legend(seen.values(), seen.keys(), loc="lower center", ncol=5, frameon=False, bbox_to_anchor=(0.5, -0.02))
        fig.suptitle(f"Edit step ({action.lower()}): where the time goes, VERIFY", fontsize=9.5)
        fig.tight_layout(rect=(0, 0.12, 1, 0.95))
        save(fig, out, f"07-{name}-{action.lower()}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    results = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(results, "figures")
    os.makedirs(out, exist_ok=True)
    setup_style()
    for path in sorted(glob.glob(os.path.join(results, "breakdown-*.csv"))):
        plot(path, out)


if __name__ == "__main__":
    main()
