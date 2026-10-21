"""Shared look of the benchmark and trace figures: one colour per strategy family (an analyzer keeps its colour in
every figure), told apart within a family by marker and line style, and always labelled (legend or direct label)."""
import matplotlib
import matplotlib.pyplot as plt

INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"
BLUE, ORANGE, AQUA, GREEN, VIOLET, MAGENTA, GREY = "#2a78d6", "#eb6834", "#1baf7a", "#008300", "#6250d6", "#e87ba4", "#52514e"

BROWN = "#8a5a2b"

# analyzer -> (label, colour, marker, linestyle). Colour = the lever that prunes (none, guards, prefilter, both);
# solid = with workset and seed, dashed = without (every rule every round, or the workset without seed).
STYLE = {
    "rounds":                                ("Rounds (no optimisation, baseline)",        BROWN,   "v", "--"),
    "workset":                               ("Workset",                                   BLUE,    "o", "--"),
    "workset-seeded":                        ("Workset + seed",                            BLUE,    "s", "-"),
    "guards-first":                          ("Guards (matchFirst)",                       ORANGE,  "o", "--"),
    "workset-guards-seeded-first":           ("Workset + seed + guards (matchFirst)",      ORANGE,  "s", "-"),
    "prefilter":                             ("Prefilter",                                 AQUA,    "o", "--"),
    "workset-prefilter-seeded":              ("Workset + seed + prefilter (default)",      AQUA,    "s", "-"),
    "workset-guards-seeded-first-prefilter": ("Workset + seed + guards (matchFirst) + prefilter", VIOLET, "D", "-"),
    "incremental":                           ("Incremental: Workset + seed + prefilter (default, the extension)", MAGENTA, "P", "-"),
    "oracle":                                ("Hits only (perfect pruning)",               GREY,    "x", "-"),
    "domain":                                ("Domain analyzer",                           INK,     ".", "-"),
}

# per schedule: its hits-only oracle (grey family, the schedule's marker) and the incremental analyzer with it
# (the schedule's colour, dotted); incremental-workset-prefilter-seeded is the extension's analyzer, "incremental"
for _name, (_label, _colour, _marker, _ls) in list(STYLE.items()):
    if _name in ("incremental", "oracle", "domain"):
        continue
    STYLE["oracle-" + _name] = ("Hits only: " + _label, GREY, _marker, ":")
    STYLE["incremental-" + _name] = ("Incremental: " + _label, _colour, _marker, ":")
STYLE["oracle-workset-prefilter-seeded"] = STYLE["oracle"]
STYLE["incremental-workset-prefilter-seeded"] = STYLE["incremental"]

# the schedules in a fixed order (legends, dot plots)
SCHEDULES = ["rounds", "workset", "workset-seeded", "workset-guards-seeded-first", "prefilter",
             "workset-prefilter-seeded", "workset-guards-seeded-first-prefilter"]


# The non-functional requirement: a coverage answer within 500 ms on a model of 42 POPP nodes.
NFR_MS, NFR_NODES = 500.0, 42
NFR_COLOUR = "#c0392b"


def nfr(ax, horizontal=True, label=True):
    """Marks the NFR on a time-vs-nodes axes (horizontal=True: time on y, nodes on x) or on a time axis alone
    (horizontal=False: a vertical line at 500 ms, for panels of one model size). Below/left of it = met."""
    kw = dict(color=NFR_COLOUR, lw=1.0, zorder=0.5)
    if horizontal:
        ax.axhline(NFR_MS, ls=(0, (5, 3)), **kw)
        ax.axvline(NFR_NODES, ls=":", **kw)
        if label:
            ax.annotate(f"NFR: < 500 ms\nat {NFR_NODES} nodes", (NFR_NODES, NFR_MS), xytext=(3, 3),
                        textcoords="offset points", ha="left", va="bottom", fontsize=6.5, color=NFR_COLOUR,
                        linespacing=0.95)
    else:
        ax.axvline(NFR_MS, ls=(0, (5, 3)), **kw)
        if label:
            ax.annotate("NFR 500 ms", (NFR_MS, 0), xycoords=("data", "axes fraction"), xytext=(3, 3),
                        textcoords="offset points", fontsize=6.5, color=NFR_COLOUR)


def style(analyzer):
    return STYLE.get(analyzer, (analyzer, INK2, "o", "-"))


def setup():
    fonts = {f.name for f in matplotlib.font_manager.fontManager.ttflist}
    family = next((f for f in ["Latin Modern Roman", "LM Roman 10", "CMU Serif", "DejaVu Serif"] if f in fonts), "serif")
    plt.rcParams.update({
        "font.family": family, "font.size": 9, "axes.titlesize": 9.5, "axes.labelsize": 9,
        "legend.fontsize": 7.5, "xtick.labelsize": 8, "ytick.labelsize": 8,
        "axes.edgecolor": INK2, "axes.labelcolor": INK, "xtick.color": INK2, "ytick.color": INK2,
        "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True, "grid.color": GRID,
        "grid.linewidth": 0.6, "axes.axisbelow": True, "lines.linewidth": 1.6, "lines.markersize": 5,
        "savefig.bbox": "tight", "savefig.dpi": 200, "pdf.fonttype": 42,
    })


def ms_label(v, _=None):
    if v >= 1000:
        return f"{v / 1000:.3g} s"
    if v >= 1:
        return f"{v:.3g} ms"
    return f"{v * 1000:.3g} µs"


def save(fig, out, name):
    import os
    for ext in ("pdf", "png"):
        fig.savefig(os.path.join(out, f"{name}.{ext}"))
    plt.close(fig)
    print("wrote", name)
