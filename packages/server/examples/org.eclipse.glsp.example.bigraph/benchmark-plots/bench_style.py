"""Shared look of the benchmark and trace figures: one colour per strategy family (an analyzer keeps its colour in
every figure), told apart within a family by marker and line style, and always labelled (legend or direct label)."""
import matplotlib
import matplotlib.pyplot as plt

INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"
BLUE, ORANGE, AQUA, GREEN, VIOLET, MAGENTA, GREY = "#2a78d6", "#eb6834", "#1baf7a", "#008300", "#6250d6", "#e87ba4", "#52514e"

# analyzer -> (label, colour, marker, linestyle). Colour = the lever that prunes (none, guards, prefilter, both);
# solid = with workset and seed, dashed = without (every rule every round, or the workset without seed).
STYLE = {
    "workset":                               ("Workset",                                   BLUE,    "o", "--"),
    "workset-seeded":                        ("Workset + seed",                            BLUE,    "s", "-"),
    "guards-first":                          ("Guards (matchFirst)",                       ORANGE,  "o", "--"),
    "workset-guards-seeded-first":           ("Workset + seed + guards (matchFirst)",      ORANGE,  "s", "-"),
    "prefilter":                             ("Prefilter",                                 AQUA,    "o", "--"),
    "workset-prefilter-seeded":              ("Workset + seed + prefilter (default)",      AQUA,    "s", "-"),
    "workset-guards-seeded-first-prefilter": ("Workset + seed + guards (matchFirst) + prefilter", VIOLET, "D", "-"),
    "incremental":                           ("Incremental (one coverage bigraph, edited in place)", MAGENTA, "P", "-"),
    "oracle":                                ("Hits only (perfect pruning)",               GREY,    "x", "-"),
    "domain":                                ("Domain analyzer",                           INK,     ".", "-"),
}

# the schedules in a fixed order (legends, dot plots)
SCHEDULES = ["workset", "workset-seeded", "guards-first", "workset-guards-seeded-first", "prefilter",
             "workset-prefilter-seeded", "workset-guards-seeded-first-prefilter"]


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
