"""Shared look of the benchmark figures: serif text, recessive axes, one color per matcher call outcome."""
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams.update({
    "font.family": "serif",
    "font.size": 9,
    "axes.titlesize": 10.5,
    "axes.edgecolor": "#52514e",
    "xtick.color": "#52514e",
    "ytick.color": "#52514e",
})

INK = "#0b0b0b"
INK2 = "#52514e"
GRID = "#e4e3df"
VARIANTS = ["baseline", "worklist", "topological", "guards", "counted", "hits-only"]
LABELS = {
    "baseline": "Baseline\n(no analysis)",
    "worklist": "+ overlap\nworklist",
    "topological": "+ topological\norder",
    "guards": "+ guards\n(cached)",
    "counted": "+ guards counted\n(default)",
    "hits-only": "Hits only\n(perfect pruning)",
}
ONE_LINE = {key: value.replace("\n", " ") for key, value in LABELS.items()}
# one sequential hue for model size, light to dark (blue ramp)
SIZES = ["#c6dbf2", "#7fb0e6", "#2a78d6", "#143f77"]
OUTCOME = {
    "hit": ("rule call: hit (raised a node)", "#2a78d6"),
    "miss": ("rule call: miss (no match)", "#d03b3b"),
    "guard-pass": ("guard call: holds", "#9b9a95"),
    "guard-fail": ("guard call: fails (rules dropped)", "#f2b8b5"),
}


def ordered(present):
    return [v for v in VARIANTS if v in present] + sorted(set(present) - set(VARIANTS))


def time_label(ms):
    if ms >= 1000:
        return f"{ms / 1000:.3g} s"
    return f"{ms:.3g} ms"


def style(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.tick_params(labelsize=8.5)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)


def save(fig, out, name):
    fig.savefig(f"{out}/{name}.png", dpi=160, bbox_inches="tight")
    fig.savefig(f"{out}/{name}.pdf", bbox_inches="tight")
    plt.close(fig)
