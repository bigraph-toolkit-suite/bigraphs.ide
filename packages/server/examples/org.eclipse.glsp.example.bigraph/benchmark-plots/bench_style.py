"""
Shared look of the benchmark figures. The figures are drawn at the size they are printed (WIDTH, the text width of
the thesis), so their text keeps its size on paper: 8 pt, ticks and annotations 7 and 6.5 pt, in the thesis font
(Open Sans, if installed, otherwise DejaVu Sans). Include them with width=\\textwidth.
"""
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

WIDTH = 6.2  # inches, the text width of the thesis (A4, about 15.7 cm)
SMALL = 6.5  # pt, annotations (values next to bars)

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["Open Sans", "DejaVu Sans"],
    "font.size": 8,
    "axes.titlesize": 8.5,
    "axes.labelsize": 8,
    "xtick.labelsize": 7,
    "ytick.labelsize": 7,
    "legend.fontsize": 7,
    "axes.linewidth": 0.6,
    "xtick.major.width": 0.6,
    "ytick.major.width": 0.6,
    "xtick.major.size": 2.5,
    "ytick.major.size": 2.5,
    "axes.edgecolor": "#52514e",
    "xtick.color": "#52514e",
    "ytick.color": "#52514e",
    "pdf.fonttype": 42,
})

INK = "#0b0b0b"
INK2 = "#52514e"
GRID = "#e4e3df"
VARIANTS = ["baseline", "+overlap", "+topological", "+seed", "+per-mode", "+guards", "default", "hits-only"]
LABELS = {
    "baseline": "baseline",
    "+overlap": "+ overlap",
    "+topological": "+ topo.\norder",
    "+seed": "+ seed",
    "+per-mode": "+ rules\nper mode",
    "+guards": "+ guards\n(cached)",
    "default": "+ counted\n(default)",
    "hits-only": "hits only\n(bound)",
    "no-seed": "without seed",
    "no-per-mode": "without rules per mode",
    "fifo": "FIFO, not topological",
    "no-overlap": "without overlap analysis",
    "guards-cached": "guards cached, not counted",
    "guards-uncached": "guards matched every time",
    "no-guards": "without guards",
    "match-all": "matchAll, not matchFirst",
    "domain": "domain analyzer",
}
ONE_LINE = {key: value.replace("\n", " ") for key, value in LABELS.items()}
# one sequential hue for model size, light to dark (blue ramp)
SIZES = ["#c6dbf2", "#7fb0e6", "#2a78d6", "#143f77"]
OUTCOME = {
    "hit": ("rule call: hit", "#2a78d6"),
    "miss": ("rule call: miss", "#d03b3b"),
    "guard-pass": ("guard call: holds", "#9b9a95"),
    "guard-fail": ("guard call: fails", "#f2b8b5"),
}


def ordered(present):
    return [v for v in VARIANTS if v in present] + sorted(set(present) - set(VARIANTS))


def time_label(ms):
    if ms >= 100_000:
        return f"{ms / 1000:.0f} s"
    if ms >= 1000:
        return f"{ms / 1000:.3g} s"
    if ms >= 1:
        return f"{ms:.3g} ms"
    return f"{ms:.2g} ms"


def style(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", color=GRID, linewidth=0.5)
    ax.set_axisbelow(True)


def save(fig, out, name):
    fig.savefig(f"{out}/{name}.png", dpi=300, bbox_inches="tight", pad_inches=0.02)
    fig.savefig(f"{out}/{name}.pdf", bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)
