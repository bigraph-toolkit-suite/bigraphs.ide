#!/usr/bin/env python3
"""
Plots the trace round written by CoverageTraceRound (calls.csv, sessions.csv).

    python3 plot_traces.py <trace-dir> [<out-dir>] [--step N]

Figure (PNG + PDF, default <trace-dir>/figures):
  trace-timeline  every matcher call of one session per configuration (ladder), on a common linear time axis. The upper lane of a
                  configuration is the session from scratch, the lower lane the session after one edit: by default the
                  edit of median reach (states reset by the sync) among the edits that changed something, as in the
                  earlier trace report (--step picks another).
                  The hits-only lane puts the hitting calls of the default configuration back to back.
"""
import argparse
import csv
import os

import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter

from bench_style import INK, INK2, LABELS, ONE_LINE, OUTCOME, SMALL, WIDTH, ordered, save, style, time_label


def outcome(call):
    if call["kind"] == "GUARD":
        return "guard-pass" if call["matched"] == "true" else "guard-fail"
    return "hit" if call["matched"] == "true" else "miss"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("src")
    parser.add_argument("out", nargs="?")
    parser.add_argument("--step")
    args = parser.parse_args()
    out = args.out or os.path.join(args.src, "figures")
    os.makedirs(out, exist_ok=True)
    calls = list(csv.DictReader(open(os.path.join(args.src, "calls.csv"))))
    sessions = list(csv.DictReader(open(os.path.join(args.src, "sessions.csv"))))
    configs = ordered({s["config"] for s in sessions})
    reference = "baseline" if "baseline" in configs else configs[0]
    if args.step:
        step = args.step
    else:
        # the edit of median reach (states the sync reset, ties to the earlier edit) among the edits that changed
        # something, as in the earlier trace report. The reach depends on the edit only.
        changed = sorted((s for s in sessions if s["config"] == reference and s["phase"] == "incremental"
                          and int(s["operations"]) > 0), key=lambda s: (int(s["resets"]), int(s["step"])))
        step = changed[len(changed) // 2]["step"]
    edit = next(s["edit"] for s in sessions if s["step"] == step and s["phase"] == "incremental")
    first = sessions[0]

    by_lane = {}
    for c in calls:
        by_lane.setdefault((c["config"], c["step"]), []).append(c)

    fig, ax = plt.subplots(figsize=(WIDTH, 0.62 * len(configs) + 0.9))
    lane_height, gap = 0.36, 0.06
    longest = 0
    yticks = []
    for index, config in enumerate(configs):
        center = index
        yticks.append(center)
        for lane, (lane_step, label) in enumerate((("0", "from scratch"), (step, "incremental, one edit"))):
            session = next((s for s in sessions if s["config"] == config and s["step"] == lane_step), None)
            if session is None:
                continue
            y = center - (lane_height + gap) / 2 if lane == 0 else center + (lane_height + gap) / 2
            duration = float(session["session_ms"])
            longest = max(longest, duration)
            ax.barh(y, duration, height=lane_height, color="#eeeeec", edgecolor="#c8c8c4", linewidth=0.5)
            for c in by_lane.get((config, lane_step), []):
                ax.barh(y, max(float(c["duration_ms"]), longest * 0.0005), left=float(c["start_ms"]),
                        height=lane_height * 0.92, color=OUTCOME[outcome(c)][1], linewidth=0)
            ax.text(duration, y, f" {time_label(duration)}", va="center", fontsize=SMALL, color=INK)
    ax.set_yticks(yticks)
    ax.set_yticklabels([ONE_LINE.get(v, v) for v in configs])
    ax.invert_yaxis()
    ax.set_xlim(0, longest * 1.12)
    ax.xaxis.set_major_formatter(FuncFormatter(lambda value, _: time_label(value) if value else "0"))
    ax.set_xlabel("time since the start of the session")
    style(ax)
    ax.grid(axis="y", visible=False)
    ax.grid(axis="x", color="#e4e3df", linewidth=0.5)
    ax.tick_params(axis="y", length=0)
    # what the lanes are: in the caption of the thesis (upper lane from scratch, lower lane after one edit)
    print(f"traced edit of the lower lanes: {edit}")
    fig.legend(handles=[Patch(color=color, label=label) for label, color in OUTCOME.values()], loc="lower center",
               ncol=4, frameon=False, bbox_to_anchor=(0.5, 0.0), handlelength=1.4, columnspacing=1.2)
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    save(fig, out, "trace-timeline")
    print(f"figures in {out}")


if __name__ == "__main__":
    main()
