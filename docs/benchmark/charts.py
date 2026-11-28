# /// script
# requires-python = ">=3.11"
# dependencies = ["matplotlib>=3.9"]
# ///
"""Turns the JMH results of ./gradlew :core:benchmark into the SVGs of docs/benchmark.md.

    uv run docs/benchmark/charts.py core/build/results/jmh

Copies the results to docs/benchmark/results/ (committed, so every chart can be rebuilt) and writes each chart twice,
<name>.svg (light) and <name>-dark.svg, for GitHub's <picture> theme switch. Prints the numbers as Markdown tables.
"""
import json
import shutil
import sys
from pathlib import Path

import matplotlib

matplotlib.use("svg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter  # noqa: E402

HERE = Path(__file__).parent
# Categorical slots 1-4, validated for both modes (dataviz validate_palette.js, adjacent pairs).
THEMES = {
    "": dict(series=["#257de0", "#f47230", "#00b894", "#f2a600"], ink="#191919", ink2="#666666",
             grid="#e7e7e7", axis="#c2c2c2"),
    "-dark": dict(series=["#338ced", "#e76624", "#00a888", "#d28e05"], ink="#efefef", ink2="#9c9c9c",
                  grid="#303030", axis="#474747"),
}
MARKERS = ["o", "s", "^", "D"]


# ---------- results ----------

def load(src: Path):
    rows = []
    for f in sorted(src.glob("*.json")):
        if f.name == "env.json":
            continue
        for r in json.loads(f.read_text() or "[]"):
            r["run"] = f.stem
            rows.append(r)
    return rows


def find(rows, method, **params):
    for r in rows:
        if ("." + r["benchmark"]).endswith("." + method) and all(str(r["params"].get(k)) == str(v) for k, v in params.items()):
            return r
    return None


def p(r, q="50.0"):
    """Percentile in the result's unit (sample mode), else the score."""
    if r is None:
        return None
    pct = r["primaryMetric"].get("scorePercentiles", {})
    return pct.get(q, r["primaryMetric"]["score"]) if r["mode"] == "sample" else r["primaryMetric"]["score"]


def jsonl(src: Path, name):
    f = src / name
    return [json.loads(line) for line in f.read_text().splitlines() if line.strip()] if f.exists() else []


# ---------- formatting ----------

def fmt_us(us):
    if us is None:
        return "–"
    if us == 0:
        return "0"
    if us < 1:
        return f"{us * 1000:.0f} ns"
    if us < 1000:
        return f"{us:.0f} µs"
    return f"{us / 1000:.1f} ms" if us < 100_000 else f"{us / 1000:.0f} ms"


def fmt_ns(ns):
    return fmt_us(ns / 1000)


def fmt_n(v):
    if v >= 1_000_000:
        return f"{v / 1_000_000:g} Mio."
    if v >= 1000:
        return f"{v / 1000:g}k"
    return f"{v:g}"


def style(ax, t, title, xlabel=None, ylabel=None):
    ax.set_facecolor("none")
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(t["axis"])
    ax.tick_params(colors=t["ink2"], labelsize=9, length=0)
    ax.grid(True, color=t["grid"], linewidth=0.6)
    ax.set_axisbelow(True)
    ax.set_title(title, color=t["ink"], fontsize=11, loc="left", pad=10)
    if xlabel:
        ax.set_xlabel(xlabel, color=t["ink2"], fontsize=9)
    if ylabel:
        ax.set_ylabel(ylabel, color=t["ink2"], fontsize=9)


def legend(ax, t, **kw):
    """Below the plot, never on the data."""
    below = 0.6 / (ax.get_position().height * ax.figure.get_figheight())  # 0.6 in under the axes, any chart height
    ax.legend(frameon=False, fontsize=9, labelcolor=t["ink2"], loc="upper center", bbox_to_anchor=(0.5, -below),
              ncol=4, **kw)


def fig_legend(fig, ax, t):
    """One legend under a row of panels (taken from the first panel)."""
    handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, frameon=False, fontsize=9, labelcolor=t["ink2"], loc="upper center",
               bbox_to_anchor=(0.5, 0.0), ncol=len(labels))


def save(fig, name, suffix):
    fig.patch.set_alpha(0)
    fig.savefig(HERE / f"{name}{suffix}.svg", bbox_inches="tight", transparent=True)
    plt.close(fig)


def hbars(ax, t, labels, values, colors, fmt, whiskers=None):
    """Horizontal bars, top to bottom, value label at the end; optional p99 whisker."""
    y = list(range(len(labels)))[::-1]
    ax.barh(y, values, color=colors, height=0.6, edgecolor="none")
    if whiskers:
        for yi, v, w, c in zip(y, values, whiskers, colors):
            if w and w > v:
                ax.plot([v, w], [yi, yi], color=c, linewidth=2, solid_capstyle="round")
                ax.plot([w], [yi], marker="|", color=c, markersize=10)
    for yi, v, w in zip(y, values, whiskers or [None] * len(values)):
        ax.annotate(fmt(v), (max(v, w or 0), yi), xytext=(6, 0), textcoords="offset points", va="center",
                    fontsize=8.5, color=t["ink"])
    ax.set_yticks(y, labels)
    ax.grid(axis="y", visible=False)


# ---------- charts ----------

def main_thread(rows, t, s):
    items = [("Pantheon get", "pantheonGet", 0), ("Pantheon inc", "pantheonInc", 0),
             ("Pantheon inc im Dauerfeuer", "pantheonIncFlood", 0),
             ("JDBC SELECT", "jdbcSelect", 1), ("JDBC UPDATE", "jdbcUpdate", 1)]
    items = [(l, r, c) for l, m, c in items for r in [find(rows, "MainThreadBench." + m)] if r]
    if not items:
        return
    fig, ax = plt.subplots(figsize=(7.5, 2.9))
    hbars(ax, t, [l for l, _, _ in items], [p(r) for _, r, _ in items],
          [t["series"][c] for _, _, c in items], fmt_ns)
    ax.set_xscale("log")
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: fmt_ns(v)))
    style(ax, t, "Kosten pro Zugriff im Server-Thread (log)", xlabel="Zeit pro Aufruf (get und Dauerfeuer: Mittelwert; sonst Median, ein Aufruf pro Tick)")
    handles = [plt.Rectangle((0, 0), 1, 1, color=t["series"][i]) for i in (0, 1)]
    legend(ax, t, handles=handles, labels=["Pantheon", "JDBC direkt"])
    save(fig, "main-thread", s)


LATENCY = [("gRPC-Aufruf ohne Datenbank", "grpcHealthCheck", None), ("inc", "pantheonInc", "jdbcInc"), ("withdraw", "pantheonWithdraw", "jdbcWithdraw"),
           ("transfer", "pantheonTransfer", "jdbcTransfer"), ("Spieler lesen", "pantheonLoad", "jdbcSelect"),
           ("Login (loadPlayer)", "pantheonLoadPlayer", None), ("Store-Dokument laden", "pantheonLoadStoreDoc", None),
           ("100× inc bis bestätigt", "pantheonBurst100", "jdbcBurst100")]


def op(rows, m):
    return find(rows, "OpLatencyBench." + m)


def latency(rows, t, s):
    labels, values, whiskers, colors = [], [], [], []
    for name, pm, jm in LATENCY:
        floor = pm == "grpcHealthCheck"
        for impl, m, c in (("Pantheon", pm, 2 if floor else 0), ("JDBC", jm, 1)):
            r = op(rows, m) if m else None
            if r:
                labels.append(name if floor else f"{name} · {impl}")
                values.append(p(r))
                whiskers.append(p(r, "99.0"))
                colors.append(t["series"][c])
    if not labels:
        return
    fig, ax = plt.subplots(figsize=(7.5, 0.32 * len(labels) + 1))
    hbars(ax, t, labels, values, colors, fmt_us, whiskers)
    ax.set_xscale("log")
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: fmt_us(v)))
    style(ax, t, "Bis die Datenbank bestätigt: Median, Strich bis p99 (log)", xlabel="Latenz")
    handles = [plt.Rectangle((0, 0), 1, 1, color=t["series"][i]) for i in (0, 1, 2)]
    legend(ax, t, handles=handles, labels=["Pantheon", "JDBC direkt", "nur gRPC (Boden)"])
    save(fig, "latency", s)


INDEX_SERIES = [("DESC, Index deklariert", "topDescIndexed"), ("DESC, kein Index", "topDescNoIndex"),
                ("ASC, Index deklariert", "topAscIndexed")]
INDEX_COLORS = [0, 3, 2, 1]  # slots: deklariert blue, kein Index yellow, ASC aqua, JDBC orange


def index_lines(rows, target):
    out = []
    for label, m in INDEX_SERIES:
        pts = sorted((int(r["params"]["n"]), p(r)) for r in rows
                     if r["benchmark"].endswith("IndexBench." + m) and r["params"].get("target") == target)
        out.append((label, pts))
    jdbc = sorted((int(r["params"]["n"]), p(r)) for r in rows if r["benchmark"].endswith("JdbcIndexBench.topDesc"))
    out.append(("JDBC, Spalte mit Index", jdbc))
    return out


def index_chart(rows, t, s):
    targets = [("players", "Rangliste über Spieler"), ("store", "Rangliste über Store-Dokumente")]
    if not any(pts for tg, _ in targets for _, pts in index_lines(rows, tg)):
        return
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.8), sharey=True)
    for ax, (tg, title) in zip(axes, targets):
        for i, (label, pts) in enumerate(index_lines(rows, tg)):
            if pts:
                ax.plot([x for x, _ in pts], [y for _, y in pts], color=t["series"][INDEX_COLORS[i]], linewidth=2,
                        marker=MARKERS[i], markersize=6, label=label)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: fmt_n(v)))
        ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: fmt_us(v)))
        style(ax, t, title, xlabel="Anzahl Zeilen (N)", ylabel="Top 10, Median" if tg == "players" else None)
    fig.tight_layout()
    fig_legend(fig, axes[0], t)
    save(fig, "index", s)


def index_write(rows, t, s):
    data = []
    for tg, label in (("players", "Spieler"), ("store", "Store")):
        for m, c in (("incIndexed", 0), ("incNoIndex", 3)):
            r = find(rows, "IndexWriteBench." + m, target=tg)
            if r:
                data.append((f"{label} · {'mit' if c == 0 else 'ohne'} Index", p(r), p(r, "99.0"), c))
    if not data:
        return
    fig, ax = plt.subplots(figsize=(7.5, 2.4))
    hbars(ax, t, [d[0] for d in data], [d[1] for d in data], [t["series"][d[3]] for d in data], fmt_us,
          [d[2] for d in data])
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: fmt_us(v)))
    style(ax, t, "Preis des Index beim Schreiben: inc, Median, Strich bis p99", xlabel="Latenz")
    handles = [plt.Rectangle((0, 0), 1, 1, color=t["series"][i]) for i in (0, 3)]
    legend(ax, t, handles=handles, labels=["mit Index", "ohne Index"])
    save(fig, "index-write", s)


CONC = [("pantheon", "Pantheon (2 Nodes)"), ("jdbcAtomic", "JDBC atomar"), ("jdbcNaive", "JDBC naiv (verliert Updates)")]


def concurrency(rows, t, s):
    conc = [r for r in rows if r["run"].startswith("ConcurrencyBench-t")]
    if not conc:
        return
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.8))
    for ax, (rw, title) in zip(axes, (("hot", "Eine heiße Zeile"), ("spread", "Verteilt auf 10k Zeilen"))):
        for i, (impl, label) in enumerate(CONC):
            pts = sorted((r["threads"], r["primaryMetric"]["score"]) for r in conc
                         if r["params"]["impl"] == impl and r["params"]["rows"] == rw)
            if pts:
                ax.plot([x for x, _ in pts], [y for _, y in pts], color=t["series"][i], linewidth=2,
                        marker=MARKERS[i], markersize=6, label=label)
        ax.set_xscale("log", base=2)
        ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
        ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v / 1000:g}k" if v >= 1000 else f"{v:g}"))
        ax.set_ylim(bottom=0)
        style(ax, t, title + " (eigene Skala)", xlabel="Threads",
              ylabel="bestätigte +1 pro Sekunde" if rw == "hot" else None)
    fig.tight_layout()
    fig_legend(fig, axes[0], t)
    save(fig, "concurrency", s)


def propagation(rows, t, s):
    items = [("Spielerdaten → anderer Server", "PropagationBench", "playerToHolder"),
             ("cached() Store → anderer Server", "PropagationBench", "storeToWatcher"),
             ("Nachricht → Proxy des Spielers", "MessagingBench", "deliverToProxy"),
             ("location(uuid) über Agora", "MessagingBench", "location")]
    data = [(l, r) for l, b, m in items for r in [find(rows, b + "." + m)] if r]
    if not data:
        return
    fig, ax = plt.subplots(figsize=(7.5, 2.4))
    hbars(ax, t, [l for l, _ in data], [p(r) for _, r in data], [t["series"][0]] * len(data), fmt_us,
          [p(r, "99.0") for _, r in data])
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: fmt_us(v)))
    style(ax, t, "Vom Schreiben auf Server A bis zur Ankunft: Median, Strich bis p99", xlabel="Latenz")
    save(fig, "propagation", s)


def audit_rate(r):
    return int(r["params"]["bundle"]) / (r["primaryMetric"]["score"] / 1e6)  # score: mean µs per call


def audit(rows, t, s):
    data = sorted(((int(r["params"]["bundle"]), audit_rate(r)) for r in rows
                   if r["benchmark"].endswith("AuditBench.append")))
    if not data:
        return
    fig, ax = plt.subplots(figsize=(7.5, 2.2))
    hbars(ax, t, [f"Bündel mit {b} Einträgen" for b, _ in data], [v for _, v in data],
          [t["series"][0]] * len(data), lambda v: f"{v:,.0f} /s".replace(",", "."))
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:,.0f}".replace(",", ".")))
    style(ax, t, "Audit-Einträge pro Sekunde, ein Node, ein Aufruf nach dem anderen", xlabel="Einträge/s")
    save(fig, "audit", s)


# ---------- tables for docs/benchmark.md ----------

def tables(rows, src):
    print("\n## Latenz (µs)\n\n| Operation | Pantheon p50 | p99 | JDBC p50 | p99 |\n|---|---:|---:|---:|---:|")
    for name, pm, jm in LATENCY:
        pr, jr = op(rows, pm), op(rows, jm) if jm else None
        print(f"| {name} | {fmt_us(p(pr))} | {fmt_us(p(pr, '99.0'))} | {fmt_us(p(jr))} | {fmt_us(p(jr, '99.0'))} |")
    print("\n## Main-Thread (ns)\n")
    for m in ("pantheonGet", "pantheonInc", "pantheonIncFlood", "jdbcSelect", "jdbcUpdate"):
        r = find(rows, "MainThreadBench." + m)
        if r:
            print(f"- {m}: {fmt_ns(p(r))} (p99 {fmt_ns(p(r, '99.0'))})")
    print("\n## Index (p50)\n\n| Ziel | N | DESC Index | DESC ohne | ASC | JDBC |\n|---|---:|---:|---:|---:|---:|")
    for tg in ("players", "store"):
        lines = dict((l, dict(pts)) for l, pts in index_lines(rows, tg))
        for n in sorted({n for pts in lines.values() for n in pts}):
            vals = [fmt_us(lines[l].get(n)) for l, _ in INDEX_SERIES] + [fmt_us(lines["JDBC, Spalte mit Index"].get(n))]
            print(f"| {tg} | {fmt_n(n)} | " + " | ".join(vals) + " |")
    print("\n## Index benutzt?\n")
    for u in jsonl(src, "index-usage.jsonl"):
        what = {"order": "liefert die Reihenfolge", "read": "nur gelesen, danach sortiert", "none": "nicht benutzt"}
        print(f"- {u['target']} n={u['n']} {u['order']}: {what.get(u.get('plan'), u.get('plan'))} ({u['index']})")
    for l in jsonl(src, "index-limits.jsonl"):
        print(f"- GRENZE {l['target']} n={l['n']} {l['method']}: nach {l['afterMs']} ms {l['error']}")
    print("\n## Audit gespeichert\n")
    for a in jsonl(src, "audit.jsonl"):
        print(f"- {a['what']}: bestätigt {a['confirmed']}, gespeichert {a['stored']}")
    print("\n## Gespeichert (DocWriter-Pfad)\n")
    for a in jsonl(src, "stored.jsonl"):
        print(f"- {a['what']}: erwartet {a['expected']}, gespeichert {a['stored']}")
    print("\n## Konsistenz\n\n| impl | rows | pool | threads | bestätigt | gespeichert | verloren |\n|---|---|---:|---:|---:|---:|---:|")
    for c in jsonl(src, "consistency.jsonl"):
        print(f"| {c['impl']} | {c['rows']} | {c['pool']} | {c['threads']} | {c['confirmed']} | {c['stored']} | {c['lost']} |")
    print("\n## Durchsatz (ops/s)\n")
    for r in rows:
        if r["run"].startswith("Concurrency"):
            pr = r["params"]
            print(f"- {r['run']} {pr['impl']} {pr['rows']} pool={pr['pool']}: {r['primaryMetric']['score']:,.0f}")
    print("\n## Audit\n")
    for r in rows:
        if r["benchmark"].endswith("AuditBench.append"):
            print(f"- bundle {r['params']['bundle']}: {audit_rate(r):,.0f} Einträge/s, p50 {fmt_us(p(r))}")
        if r["benchmark"].endswith("AuditTimelineBench.newestPage"):
            print(f"- timeline newest page, {r['params']['entries']} Einträge, {r['params'].get('statistics')}: "
                  f"p50 {fmt_us(p(r))}, p99 {fmt_us(p(r, '99.0'))}")
    print("\n## Verbreitung\n")
    for m in ("PropagationBench.playerToHolder", "PropagationBench.storeToWatcher",
              "MessagingBench.deliverToProxy", "MessagingBench.location"):
        r = find(rows, m)
        if r:
            print(f"- {m}: p50 {fmt_us(p(r))}, p90 {fmt_us(p(r, '90.0'))}, p99 {fmt_us(p(r, '99.0'))}")


def main():
    src = Path(sys.argv[1] if len(sys.argv) > 1 else "core/build/results/jmh")
    dst = HERE / "results"
    if src.resolve() != dst.resolve():
        # copy over, never replace: a filtered rerun only brings its own files
        shutil.copytree(src, dst, dirs_exist_ok=True)
        for f in dst.glob("*.json"):  # the raw sample histograms are megabytes; the percentiles stay
            data = json.loads(f.read_text() or "[]")
            if isinstance(data, list):
                for r in data:
                    r.pop("jvm", None)  # local paths and the test database's credentials stay out of the repo
                    r.pop("jvmArgs", None)
                    for m in [r["primaryMetric"], *r.get("secondaryMetrics", {}).values()]:
                        m.pop("rawData", None)
                        m.pop("rawDataHistogram", None)
                f.write_text(json.dumps(data, indent=1))
    rows = load(dst)
    for suffix, t in THEMES.items():
        plt.rcParams.update({"font.family": "sans-serif", "svg.fonttype": "none", "text.color": t["ink"]})
        for chart in (main_thread, latency, index_chart, index_write, concurrency, propagation, audit):
            chart(rows, t, suffix)
    tables(rows, dst)


if __name__ == "__main__":
    main()
