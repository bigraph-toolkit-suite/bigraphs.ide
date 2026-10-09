#!/usr/bin/env python3
"""
Diagramme aus JMH-JSON-Ergebnissen von CoverageBenchmark.

Beispiele (fish):
    python3 plot_benchmarks.py benchmark-results/*.json -o plots
    python3 plot_benchmarks.py domain.json rewriting.json fragment.json -o plots --format png --lang de
    python3 plot_benchmarks.py *.json -o plots --only domain,rewriting,fragment --baseline rewriting

Erzeugt pro Benchmark (inspectRoot, editThenInspect, ...):
  <bench>.pdf          Laufzeit über POPP-Knoten, log-log, mit 99,9-%-Konfidenzintervall und 500-ms-Linie
  <bench>-speedup.pdf  Faktor gegenüber --baseline (Standard: rewriting), falls die Baseline enthalten ist
und zusätzlich
  levers-<bench>.pdf   Balken je Analyzer bei der größten Tiefe (Hebel-Vergleich), log-Skala
  summary.md           alle Werte als Markdown-Tabellen (Mittelwert ± Fehler, ms)

Analyzer werden über den JMH-Parameter "analyzer" identifiziert. Kommt derselbe Analyzer in mehreren
Dateien vor, gewinnt die zuletzt genannte Datei (es wird eine Warnung ausgegeben); mit --keep-variant
wird stattdessen das -Dvariant-Label angehängt, z. B. "rewriting (current)" und "rewriting (rerun)".
Benötigt nur matplotlib (pip install matplotlib).
"""
import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter  # noqa: E402

# ---------------------------------------------------------------------------------------------------------------
# Darstellung: feste Farbe, Marker und Linie pro Analyzer (Farbe folgt dem Analyzer, nicht der Reihenfolge).
# Die Fragment-Hebel sind eine geordnete Reihe und bekommen daher Stufen EINES Blautons (hell -> dunkel);
# alle anderen Analyzer je einen eigenen Farbton. Marker + Linienstil tragen die Identität zusätzlich,
# damit die Diagramme auch in Graustufen / bei Farbsehschwäche lesbar bleiben.
STYLE = {
    "domain":            dict(color="#1baf7a", marker="o", ls="-"),
    "rewriting-naive":   dict(color="#e34948", marker="d", ls=":"),
    "rewriting-census":  dict(color="#eda100", marker="D", ls="--"),
    "rewriting":         dict(color="#eb6834", marker="s", ls="-"),
    "initial":           dict(color="#008300", marker="*", ls="-"),
    "legacy":            dict(color="#6250d6", marker="^", ls="-"),
    "legacy-unpruned":   dict(color="#e87ba4", marker="v", ls="-"),
    "fragment-rounds":   dict(color="#86b6ef", marker="<", ls="-"),
    "fragment-pruned":   dict(color="#5598e7", marker=">", ls="-"),
    "fragment-worklist": dict(color="#2a78d6", marker="p", ls="-"),
    "fragment-slim":     dict(color="#1c5cab", marker="h", ls="-"),
    "fragment":          dict(color="#104281", marker="P", ls="-"),
    "fragment-memo":     dict(color="#0d366b", marker="X", ls="--"),
}
FALLBACK_STYLES = [dict(color=c, marker=m, ls=":") for c, m in
                   zip(["#008300", "#e34948", "#6250d6", "#eda100"], ["*", "d", "1", "2"])]
ORDER = list(STYLE)  # Legenden- und Balkenreihenfolge

LABELS = {
    "en": {
        "domain": "Domain", "rewriting-naive": "Rewriting, naive", "rewriting-census": "Rewriting, census",
        "rewriting": "Rewriting, census + link guard", "initial": "Rewriting (initial)",
        "legacy": "Legacy encoding", "legacy-unpruned": "Legacy encoding, unpruned",
        "fragment-rounds": "Fragment: rounds", "fragment-pruned": "Fragment: + local prune",
        "fragment-worklist": "Fragment: + worklist", "fragment-slim": "Fragment: + slim",
        "fragment": "Fragment (default)", "fragment-memo": "Fragment: + memo",
        "x": "POPP nodes", "y": "time per operation", "speedup": "speedup vs.\n{base}",
        "limit": "500 ms limit", "levers": "{bench}, {nodes} POPP nodes", "time": "time per operation",
    },
    "de": {
        "domain": "Domain", "rewriting-naive": "Rewriting, naiv", "rewriting-census": "Rewriting, Zensus",
        "rewriting": "Rewriting, Zensus + Link-Prüfung", "initial": "Rewriting (initial)",
        "legacy": "Alte Kodierung", "legacy-unpruned": "Alte Kodierung, ohne Pruning",
        "fragment-rounds": "Fragment: Runden", "fragment-pruned": "Fragment: + lokales Pruning",
        "fragment-worklist": "Fragment: + Worklist", "fragment-slim": "Fragment: + schlank",
        "fragment": "Fragment (Standard)", "fragment-memo": "Fragment: + Memo",
        "x": "POPP-Knoten", "y": "Zeit pro Operation", "speedup": "Beschleunigung ggü.\n{base}",
        "limit": "500-ms-Grenze", "levers": "{bench}, {nodes} POPP-Knoten", "time": "Zeit pro Operation",
    },
}

TO_MS = {"ns/op": 1e-6, "us/op": 1e-3, "µs/op": 1e-3, "ms/op": 1.0, "s/op": 1e3}

INK = "#1f1f1e"          # Text
INK_MUTED = "#6b6a63"    # Achsen, Hilfslinien
GRID = "#e4e3dd"


def nodes_for_depth(depth: int) -> int:
    """balancedTree(depth): 2^(d+1)-1 Kriterien + 2^d Beweise + 1 Lösung = 3 * 2^d."""
    return 3 * 2 ** depth


# ---------------------------------------------------------------------------------------------------------------
def load(files, keep_variant):
    """-> data[benchmark][analyzer][nodes] = (mean_ms, err_ms, samples)"""
    data = defaultdict(lambda: defaultdict(dict))
    seen = {}
    for f in files:
        for r in json.loads(Path(f).read_text()):
            bench = r["benchmark"].rsplit(".", 1)[-1]
            p = r.get("params", {})
            if "analyzer" not in p or "depth" not in p:
                continue
            key = p["analyzer"]
            if keep_variant:
                variant = next((a.split("=", 1)[1] for a in r.get("jvmArgs", []) if a.startswith("-Dvariant=")), None)
                if variant and variant != key:
                    key = f"{key} ({variant})"
            m = r["primaryMetric"]
            unit = TO_MS.get(m["scoreUnit"])
            if unit is None:
                sys.exit(f"Unbekannte Einheit {m['scoreUnit']} in {f}")
            err = m.get("scoreError")
            try:
                err = float(err)  # JMH writes "NaN" (a string) when there is only one sample
            except (TypeError, ValueError):
                err = 0.0
            if math.isnan(err):
                err = 0.0
            samples = sum(len(fork) for fork in m.get("rawData", [])) or None
            n = nodes_for_depth(int(p["depth"]))
            cell = (bench, key, n)
            if cell in seen and seen[cell] != f:
                print(f"Warnung: {bench}/{key}/{n} Knoten aus {seen[cell]} wird durch {f} ersetzt", file=sys.stderr)
            seen[cell] = f
            data[bench][key][n] = (m["score"] * unit, err * unit, samples)
    return data


def style_for(key, cache):
    base = key.split(" (", 1)[0]
    if key in STYLE:
        return STYLE[key]
    if base in STYLE and key not in cache:
        # gleiche Farbe wie der Basis-Analyzer, aber gestrichelt (z. B. ein zweiter Lauf)
        cache[key] = dict(STYLE[base], ls="--")
    if key not in cache:
        cache[key] = FALLBACK_STYLES[len(cache) % len(FALLBACK_STYLES)]
    return cache[key]


def label_for(key, lang):
    base, _, rest = key.partition(" (")
    name = LABELS[lang].get(base, base)
    return f"{name} ({rest}" if rest else name


def sort_keys(keys):
    return sorted(keys, key=lambda k: (ORDER.index(k.split(" (")[0]) if k.split(" (")[0] in ORDER else 99, k))


def setup_axes(ax):
    ax.set_facecolor("white")
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(INK_MUTED)
        ax.spines[side].set_linewidth(0.8)
    ax.tick_params(colors=INK_MUTED, labelcolor=INK, labelsize=9, width=0.8)
    ax.tick_params(which="minor", length=0)
    ax.grid(True, which="major", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)


def fmt_ms(v, _pos=None):
    """Zeit in ms -> lesbare Einheit, 3 signifikante Stellen."""
    for factor, unit in ((1e-3, "s"), (1, "ms"), (1e3, "µs"), (1e6, "ns")):
        if v * factor >= 1 or unit == "ns":
            return f"{v * factor:.3g} {unit}"


def fmt_factor(v, _pos=None):
    return f"{v:,.0f}×".replace(",", "\u2009") if v >= 1 else f"{v:.2g}×"


def finish(fig, out, name, fmt, dpi):
    path = out / f"{name}.{fmt}"
    fig.savefig(path, format=fmt, dpi=dpi, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    print(f"geschrieben: {path}")


# ---------------------------------------------------------------------------------------------------------------
def plot_scaling(bench, series, args, styles):
    fig, ax = plt.subplots(figsize=(args.width, args.height))
    setup_axes(ax)
    all_nodes = sorted({n for s in series.values() for n in s})
    for key in sort_keys(series):
        pts = sorted(series[key].items())
        xs = [n for n, _ in pts]
        ys = [v[0] for _, v in pts]
        es = [v[1] for _, v in pts]
        st = style_for(key, styles)
        ax.errorbar(xs, ys, yerr=es, color=st["color"], marker=st["marker"], ls=st["ls"], lw=1.6, ms=6,
                    capsize=2.5, elinewidth=0.9, label=label_for(key, args.lang),
                    markeredgecolor="white", markeredgewidth=0.6)
    ax.set_xscale("log", base=2)
    ax.set_yscale("log")
    ax.set_xticks(all_nodes)
    ax.set_xticklabels([str(n) for n in all_nodes])
    ax.xaxis.set_minor_formatter(NullFormatter())
    ax.yaxis.set_major_locator(LogLocator(base=10))
    ax.yaxis.set_major_formatter(FuncFormatter(fmt_ms))
    ax.yaxis.set_minor_formatter(NullFormatter())
    if args.limit and args.limit > 0:
        lo, hi = ax.get_ylim()
        if lo < args.limit * 3:  # nur zeigen, wenn die Grenze in Sichtweite liegt
            ax.axhline(args.limit, color=INK_MUTED, ls=(0, (4, 3)), lw=1)
            ax.annotate(LABELS[args.lang]["limit"], xy=(all_nodes[0], args.limit), xytext=(2, 3),
                        textcoords="offset points", fontsize=8, color=INK_MUTED, va="bottom")
            ax.set_ylim(lo, max(hi, args.limit * 1.5))
    ax.set_xlabel(LABELS[args.lang]["x"], color=INK, fontsize=10)
    ax.set_ylabel(LABELS[args.lang]["y"], color=INK, fontsize=10)
    if args.titles:
        ax.set_title(bench, color=INK, fontsize=11, loc="left")
    ax.legend(frameon=False, fontsize=8, labelcolor=INK, loc="upper left", bbox_to_anchor=(1.01, 1.0))
    finish(fig, args.out, bench, args.format, args.dpi)


def plot_speedup(bench, series, args, styles):
    base = args.baseline
    if base not in series:
        return
    others = [k for k in sort_keys(series) if k != base]
    if not others:
        return
    fig, ax = plt.subplots(figsize=(args.width, args.height))
    setup_axes(ax)
    nodes = sorted(series[base])
    for key in others:
        common = [n for n in nodes if n in series[key]]
        if not common:
            continue
        ys = [series[base][n][0] / series[key][n][0] for n in common]
        st = style_for(key, styles)
        ax.plot(common, ys, color=st["color"], marker=st["marker"], ls=st["ls"], lw=1.6, ms=6,
                label=label_for(key, args.lang), markeredgecolor="white", markeredgewidth=0.6)
    ax.axhline(1, color=INK_MUTED, lw=1)
    ax.set_xscale("log", base=2)
    ax.set_yscale("log")
    ax.set_xticks(nodes)
    ax.set_xticklabels([str(n) for n in nodes])
    ax.xaxis.set_minor_formatter(NullFormatter())
    ax.yaxis.set_major_formatter(FuncFormatter(fmt_factor))
    ax.yaxis.set_minor_formatter(NullFormatter())
    ax.set_xlabel(LABELS[args.lang]["x"], color=INK, fontsize=10)
    ax.set_ylabel(LABELS[args.lang]["speedup"].format(base=label_for(base, args.lang)), color=INK, fontsize=10)
    if args.titles:
        ax.set_title(bench, color=INK, fontsize=11, loc="left")
    ax.legend(frameon=False, fontsize=8, labelcolor=INK, loc="upper left", bbox_to_anchor=(1.01, 1.0))
    finish(fig, args.out, f"{bench}-speedup", args.format, args.dpi)


def plot_levers(bench, series, args, styles):
    """Balken je Analyzer bei der größten gemeinsamen Knotenzahl."""
    if len(series) < 2:
        return
    n = max(max(s) for s in series.values())
    keys = [k for k in sort_keys(series) if n in series[k]]
    if len(keys) < 2:
        return
    vals = [series[k][n][0] for k in keys]
    errs = [series[k][n][1] for k in keys]
    fig, ax = plt.subplots(figsize=(args.width, 0.42 * len(keys) + 1.0))
    setup_axes(ax)
    ax.grid(True, axis="x", which="major", color=GRID, linewidth=0.8)
    ax.grid(False, axis="y")
    ys = list(range(len(keys)))[::-1]
    for y, k, v, e in zip(ys, keys, vals, errs):
        st = style_for(k, styles)
        ax.barh(y, v, height=0.6, color=st["color"], xerr=e, ecolor=INK_MUTED, capsize=2, linewidth=0)
        ax.annotate(fmt_ms(v), xy=(v, y), xytext=(4, 0), textcoords="offset points", va="center",
                    fontsize=8, color=INK)
    ax.set_yticks(ys)
    ax.set_yticklabels([label_for(k, args.lang) for k in keys], fontsize=9, color=INK)
    ax.set_xscale("log")
    ax.xaxis.set_major_formatter(FuncFormatter(fmt_ms))
    ax.xaxis.set_minor_formatter(NullFormatter())
    lo, hi = ax.get_xlim()
    ax.set_xlim(lo, hi * 4)  # Platz für die Werte rechts
    if args.limit and args.limit > 0 and lo < args.limit < hi * 4:
        ax.axvline(args.limit, color=INK_MUTED, ls=(0, (4, 3)), lw=1)
    ax.set_xlabel(LABELS[args.lang]["time"], color=INK, fontsize=10)
    ax.set_title(LABELS[args.lang]["levers"].format(bench=bench, nodes=n), color=INK, fontsize=10, loc="left")
    finish(fig, args.out, f"levers-{bench}", args.format, args.dpi)


def pm(mean, err):
    """Mittelwert ± Fehler, beide auf die Stelle der 2. signifikanten Ziffer des Fehlers gerundet, ohne Exponent."""
    if err <= 0:
        return f"{mean:.4g}"
    decimals = max(0, 1 - math.floor(math.log10(err)))
    return f"{mean:.{decimals}f} ± {err:.{decimals}f}"


def write_summary(data, args):
    lines = ["# Benchmark-Ergebnisse (ms, Mittelwert ± Fehler)", ""]
    for bench in sorted(data):
        series = data[bench]
        nodes = sorted({n for s in series.values() for n in s})
        lines += [f"## {bench}", "", "| Analyzer | " + " | ".join(f"{n} Knoten" for n in nodes) + " |",
                  "|---|" + "---|" * len(nodes)]
        for k in sort_keys(series):
            cells = []
            for n in nodes:
                v = series[k].get(n)
                cells.append("–" if v is None else pm(v[0], v[1]))
            lines.append(f"| {label_for(k, args.lang)} | " + " | ".join(cells) + " |")
        lines.append("")
    path = args.out / "summary.md"
    path.write_text("\n".join(lines))
    print(f"geschrieben: {path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+", help="JMH-JSON-Dateien (-rf json)")
    ap.add_argument("-o", "--out", type=Path, default=Path("plots"), help="Ausgabeordner (Standard: plots)")
    ap.add_argument("--format", default="pdf", choices=["pdf", "png", "svg"], help="Standard: pdf (Vektor, für LaTeX)")
    ap.add_argument("--lang", default="en", choices=["en", "de"], help="Sprache der Beschriftungen")
    ap.add_argument("--only", help="nur diese Analyzer, kommagetrennt (z. B. domain,rewriting,fragment)")
    ap.add_argument("--benchmarks", help="nur diese Benchmarks, kommagetrennt (z. B. inspectRoot,editThenInspect)")
    ap.add_argument("--baseline", default="rewriting", help="Bezug für das Speedup-Diagramm (Standard: rewriting; "
                    "rewriting-naive deckt nur Tiefe 1-2 ab)")
    ap.add_argument("--limit", type=float, default=500, help="Grenzlinie in ms, 0 = keine (Standard: 500)")
    ap.add_argument("--keep-variant", action="store_true", help="gleiche Analyzer aus verschiedenen Läufen getrennt zeigen")
    ap.add_argument("--no-titles", dest="titles", action="store_false", help="keine Diagrammtitel (z. B. für LaTeX-Captions)")
    ap.add_argument("--width", type=float, default=6.4, help="Breite in Zoll (Standard: 6.4)")
    ap.add_argument("--height", type=float, default=3.6, help="Höhe in Zoll (Standard: 3.6)")
    ap.add_argument("--dpi", type=int, default=200, help="nur für PNG (Standard: 200)")
    args = ap.parse_args()

    data = load(args.files, args.keep_variant)
    if args.only:
        wanted = set(args.only.split(","))
        for bench in list(data):
            data[bench] = {k: v for k, v in data[bench].items() if k.split(" (")[0] in wanted or k in wanted}
    if args.benchmarks:
        wanted = set(args.benchmarks.split(","))
        data = {b: s for b, s in data.items() if b in wanted}
    data = {b: s for b, s in data.items() if s}
    if not data:
        sys.exit("Keine passenden Ergebnisse gefunden.")

    args.out.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({"font.family": "sans-serif", "pdf.fonttype": 42, "svg.fonttype": "none"})
    styles = {}
    for bench, series in sorted(data.items()):
        plot_scaling(bench, series, args, styles)
        plot_speedup(bench, series, args, styles)
        plot_levers(bench, series, args, styles)
    write_summary(data, args)


if __name__ == "__main__":
    main()
