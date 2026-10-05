"""Turn a bench/results/<run-id> directory into the paper's tables, figures and macros.

usage: python -m causal4j_bench.results_to_tex <run-id> [--base <run-id> ...] [--quick] [--paper-dir ../Causal4j-paper]

Writes bench/out/<run-id>/{tables/*.tex, tables/rq1_tier_a.csv, figures/*.pdf, numbers.tex}.
A run may reuse earlier runs (bench/results/<run>/base_runs.txt, written by BASE_RUN=, or
--base): results are merged per (step, dataset) file and per CSV row, newest run first. Medians and
p99 come from the JMH sample histograms. A (system, dataset, operator) with any
entry in timeouts.csv is censored: drawn as an open marker at the 60 s timeout
and excluded from speedups. With --quick (CI) missing inputs are skipped; without
it every phase-1 input must exist.
"""
import argparse
import csv
import json
import math
import re
import shutil
import statistics
import sys
from collections import defaultdict
from pathlib import Path

from . import BENCH, REPO

TIMEOUT_US = 60e6
SYSTEMS = {"TierABenchmarks": None, "CypherBenchmarks": "cypher", "GdsBenchmarks": "gds",
           "ProcedureBenchmarks": "procedure"}
OPERATORS = ["ancestors", "descendants", "reaches", "confounders", "mediators"]
SYSTEM_LABEL = {"causal4j": "Causal4j", "procedure": "Causal4j (procedure)", "dfs": "DFS",
                "cypher": "Cypher", "gds": "GDS"}
# Systems of the paper table, per operator, in order; "read" is the closure read alone
# (TierABenchmarks.ancestorsCount). descendants mirrors ancestors and is kept in the CSV only.
TABLE_BLOCKS = [
    ("ancestors", ["read", "causal4j", "procedure", "dfs", "cypher", "gds"]),
    ("reaches", ["causal4j", "procedure", "dfs", "cypher", "gds"]),
    ("confounders", ["causal4j", "procedure", "dfs", "cypher"]),
    ("mediators", ["causal4j", "procedure", "dfs", "cypher"]),
]
COLUMN_LABEL = {"read": "read", "causal4j": "in-proc.", "procedure": "proc.",
                "dfs": "DFS", "cypher": "Cypher", "gds": "GDS"}
BNLEARN = ["asia", "sachs", "child", "alarm", "hepar2", "win95pts", "pathfinder",
           "andes", "pigs", "link", "munin"]

# \providecommand{\name}{default} lines of the paper's numbers-defaults.tex.
PROVIDE = re.compile(r"^\\providecommand\{\\([A-Za-z]+)\}\{(.*)\}\s*$", re.M)


# ── loading ─────────────────────────────────────────────────────────────────

def load_jmh(path: Path):
    """Rows {system, dataset, operator, median_us, p99_us, extra params}. TierABenchmarks rows map
    to causal4j (index on), dfs (index off), pruned (budget=0), and read (ancestorsBits/Count)."""
    rows = []
    for b in json.loads(path.read_text()):
        cls, op = b["benchmark"].split(".")[-2:]
        params = b.get("params", {})
        system = SYSTEMS.get(cls, cls)
        if cls == "TierABenchmarks":
            system = "causal4j" if params.get("index") == "on" else "dfs"
            if params.get("budget", "unlimited") != "unlimited":
                system = "pruned"
            if op in ("ancestorsBits", "ancestorsCount"):
                system, op = ("read", "ancestors") if op == "ancestorsCount" else ("bits", "ancestors")
        pm = b["primaryMetric"]
        scale = {"us/op": 1.0, "ms/op": 1e3, "ns/op": 1e-3, "s/op": 1e6}[pm["scoreUnit"]]
        rows.append({"system": system, "dataset": params.get("dataset"), "operator": op,
                     "branch": params.get("branch"),
                     "median_us": pm["scorePercentiles"]["50.0"] * scale,
                     "p99_us": pm["scorePercentiles"]["99.0"] * scale})
    return rows


def runs_of(run: Path, extra_bases):
    """This run, then its base runs (base_runs.txt, then --base), newest first."""
    bases = list(extra_bases)
    if (run / "base_runs.txt").exists():
        bases = [b.strip() for b in (run / "base_runs.txt").read_text().split() if b.strip()] + bases
    out = [run]
    for b in bases:
        d = BENCH / "results" / b
        if not d.is_dir():
            raise SystemExit(f"base run {d} does not exist")
        if d not in out:
            out.append(d)
    return out


def merged_files(runs, step):
    """<step>/*.json and <step>/*.csv across runs; for each file name the newest run wins."""
    seen = {}
    for r in runs:
        for f in sorted((r / step).glob("*")) if (r / step).is_dir() else []:
            if f.suffix in (".json", ".csv") and f.name not in seen:
                seen[f.name] = f
    return [seen[k] for k in sorted(seen)]


def load_censored(runs):
    out = set()
    for run in runs:
        p = run / "timeouts.csv"
        if p.exists():
            with open(p, newline="") as f:
                out |= {(r["system"], r["dataset"], r["operator"]) for r in csv.DictReader(f)}
    return out


def meta(dataset):
    for d in (BENCH / "datasets" / "bnlearn" / dataset, BENCH / "datasets" / "synthetic" / dataset,
              BENCH / "datasets" / dataset):
        if (d / "meta.json").exists():
            return json.loads((d / "meta.json").read_text())
    raise KeyError(dataset)


# ── formatting ──────────────────────────────────────────────────────────────

def fmt_us(v):
    if v is None:
        return "--"
    if v == math.inf:
        return "t/o"
    if v < 0.1:
        return f"{v:.3f}"  # closure reads take a few ns
    return f"{v:.2f}" if v < 10 else f"{v:.1f}" if v < 100 else f"{v:,.0f}".replace(",", "\\,")


def fmt_x(v):
    return f"{v:.1f}" if v < 10 else f"{v:.0f}"


def geomean(xs):
    xs = [x for x in xs if x and x > 0 and math.isfinite(x)]
    return math.exp(sum(map(math.log, xs)) / len(xs)) if xs else None


def label(dataset):
    if dataset in BNLEARN:
        return f"\\textsc{{{dataset}}}"
    if dataset == "covid":
        return "COVID-19"
    m = meta(dataset)
    return f"{m['model'].upper()} $n{{=}}{m['n']}$, $\\bar d{{=}}{m['avg_degree']}$"


# ── tables ──────────────────────────────────────────────────────────────────

def lookup(rows, censored):
    """(system, dataset, operator) -> median µs, inf if censored; the first row per key wins."""
    out = {}
    for r in rows:
        key = (r["system"], r["dataset"], r["operator"])
        out.setdefault(key, math.inf if key in censored else r["median_us"])
    return out


def rq1_table(lat, datasets):
    """Table V: per operator block, the systems of TABLE_BLOCKS that have data."""
    blocks = [(op, [s for s in systems if any(k[0] == s and k[2] == op for k in lat)])
              for op, systems in TABLE_BLOCKS]
    blocks = [(op, systems) for op, systems in blocks if systems]
    cols = [(op, s) for op, systems in blocks for s in systems]
    head1 = " & ".join(f"\\multicolumn{{{len(systems)}}}{{c}}{{{op}}}" for op, systems in blocks)
    head2 = " & ".join(COLUMN_LABEL[s] for _, s in cols)
    rules, c = [], 2
    for _, systems in blocks:
        rules.append(f"\\cmidrule(lr){{{c}-{c + len(systems) - 1}}}")
        c += len(systems)
    lines = ["% Generated by causal4j-bench results_to_tex. Median latency in µs; t/o = 60 s timeout.",
             "% read: closure row popcount (O(w)); in-proc.: Causal4j in the JVM, ids materialized;",
             "% proc.: CALL causal.* through embedded Neo4j. descendants is in rq1_tier_a.csv only.",
             f"\\begin{{tabular}}{{@{{}}l{'r' * len(cols)}@{{}}}}", "\\toprule",
             f"DAG & {head1} \\\\", " ".join(rules), f" & {head2} \\\\", "\\midrule"]
    for ds in datasets:
        if not any(k[1] == ds for k in lat):
            continue
        cells = " & ".join(fmt_us(lat.get((s, ds, op))) for op, s in cols)
        lines.append(f"{label(ds)} & {cells} \\\\")
    lines += ["\\bottomrule", "\\end{tabular}"]
    return "\n".join(lines) + "\n"


def rq1_csv(rows, censored, path: Path):
    """Every tier (a) measurement, including descendants and the in-process ancestorsBits."""
    seen = set()
    with open(path, "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["system", "dataset", "operator", "median_us", "p99_us", "censored"])
        for r in sorted(rows, key=lambda r: (r["dataset"], r["operator"], r["system"])):
            key = (r["system"], r["dataset"], r["operator"])
            if key in seen:
                continue
            seen.add(key)
            w.writerow([*key, f"{r['median_us']:.4f}", f"{r['p99_us']:.4f}", key in censored])


def rq2_rows(runs):
    """rq2.csv rows from the newest run that has the dataset."""
    out, seen = [], set()
    for run in runs:
        p = run / "rq2.csv"
        if p.exists():
            with open(p, newline="") as f:
                for row in csv.DictReader(f):
                    if (row["dataset"], row["branch"]) not in seen:
                        seen.add((row["dataset"], row["branch"]))
                        out.append(row)
    return out


def rq2_table(rq2, compile_rows, lat):
    """Build (JMH), heap and closure size per branch; ancestors/reaches latency from the same
    JMH class as RQ1: closures = TierABenchmarks index=on, pruned = the same with budget=0."""
    build = {(r["dataset"], r["branch"]): r["median_us"] / 1e3 for r in compile_rows}
    by = defaultdict(dict)
    for r in rq2:
        by[r["dataset"]][r["branch"]] = r
    lines = ["% Generated by causal4j-bench results_to_tex. Build in ms (JMH median), heap and closures in KB,",
             "% latency in µs (median). c = closures, p = layer-pruned.",
             "\\begin{tabular}{@{}lrrrrrrrr@{}}", "\\toprule",
             "DAG & \\multicolumn{2}{c}{build (ms)} & heap (KB) & closures (KB) & "
             "\\multicolumn{2}{c}{ancestors (µs)} & \\multicolumn{2}{c}{reaches (µs)} \\\\",
             " & c & p & c & & c & p & c & p \\\\", "\\midrule"]
    order = [d for d in BNLEARN + ["covid"] if d in by] + sorted(
        (d for d in by if d not in BNLEARN and d != "covid"
         and meta(d)["avg_degree"] == 4 and meta(d)["model"] == "er"), key=lambda d: meta(d)["n"])
    for ds in order:
        c, p = by[ds].get("closures"), by[ds].get("pruned")
        if not c or not p:
            continue
        lines.append(" & ".join([
            label(ds),
            fmt_us(build.get((ds, "closures"))), fmt_us(build.get((ds, "pruned"))),
            f"{int(c['heap_bytes']) / 1e3:,.0f}".replace(",", "\\,"),
            f"{int(c['closure_bytes']) / 1e3:,.1f}".replace(",", "\\,"),
            fmt_us(lat.get(("causal4j", ds, "ancestors"))), fmt_us(lat.get(("pruned", ds, "ancestors"))),
            fmt_us(lat.get(("causal4j", ds, "reaches"))), fmt_us(lat.get(("pruned", ds, "reaches"))),
        ]) + " \\\\")
    lines += ["\\bottomrule", "\\end{tabular}"]
    return "\n".join(lines) + "\n"


def heap_model(rq2):
    """Heap of a closure-branch snapshot as base(n) + closures(n): the pruned branch's heap
    per node at the largest measured graph, plus the measured closure overhead per analytic
    closure byte (closure heap - pruned heap) / closure_bytes. None without data."""
    big = max((r for r in rq2 if r["branch"] == "closures"), key=lambda r: int(r["n"]), default=None)
    if big is None:
        return None
    pruned = next((r for r in rq2 if r["dataset"] == big["dataset"] and r["branch"] == "pruned"), None)
    if pruned is None:
        return None
    n = int(big["n"])
    base_per_node = int(pruned["heap_bytes"]) / n
    overhead = (int(big["heap_bytes"]) - int(pruned["heap_bytes"])) / max(1, int(big["closure_bytes"]))
    return lambda m: base_per_node * m + overhead * 2 * m * math.ceil(m / 64) * 8


def crossover(rq2, budget):
    """Smallest n whose modelled closure-branch heap exceeds `budget` (see heap_model)."""
    model = heap_model(rq2)
    if model is None:
        return None, None
    n = 64
    while model(n) <= budget:
        n = int(n * 1.01) + 1
    return n, model


# ── figures ─────────────────────────────────────────────────────────────────

STYLE = {"causal4j": ("black", "-", "o"), "dfs": ("0.45", "--", "s"),
         "cypher": ("0.25", ":", "^"), "gds": ("0.6", "-.", "D")}


def _plt():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.size": 8, "axes.titlesize": 8, "legend.fontsize": 7,
                         "pdf.fonttype": 42, "font.family": "serif"})
    return plt


def rq4_figure(lat, rq4_rows, out: Path):
    """Latency vs |V| on ER d=4: Causal4j (median over seeds 0-4) and the seed-0 baselines."""
    plt = _plt()
    fig, axes = plt.subplots(1, 2, figsize=(3.4, 2.1), sharey=True)
    seeds = defaultdict(list)
    for r in rq4_rows:
        m = meta(r["dataset"])
        if m["model"] == "er" and m["avg_degree"] == 4:
            seeds[(r["operator"], m["n"])].append(r["median_us"])
    for ax, op in zip(axes, ("ancestors", "reaches")):
        for system in ("causal4j", "dfs", "cypher", "gds"):
            if system == "causal4j" and seeds:
                pts = sorted((n, statistics.median(v)) for (o, n), v in seeds.items() if o == op)
                cens = []
            else:
                pts, cens = [], []
                for (s, ds, o), v in lat.items():
                    if s != system or o != op or ds in BNLEARN or ds == "covid":
                        continue
                    m = meta(ds)
                    if m["model"] == "er" and m["avg_degree"] == 4 and m["seed"] == 0:
                        (cens if v == math.inf else pts).append((m["n"], TIMEOUT_US if v == math.inf else v))
                pts.sort()
            if not pts and not cens:
                continue
            color, ls, mk = STYLE[system]
            if pts:
                ax.plot(*zip(*pts), color=color, ls=ls, marker=mk, ms=3, lw=1, label=SYSTEM_LABEL[system])
            if cens:
                ax.plot(*zip(*sorted(cens)), ls="none", marker=mk, ms=4, mfc="none", color=color)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_title(op)
        ax.set_xlabel("$|V|$")
        ax.grid(True, which="major", lw=0.3, color="0.85")
    axes[0].set_ylabel("median latency (µs)")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, frameon=False, loc="lower center", ncol=len(labels), fontsize=7)
    fig.tight_layout(pad=0.3, rect=(0, 0.08, 1, 1))
    fig.savefig(out)
    plt.close(fig)


def rq2_figure(rq2, out: Path):
    plt = _plt()
    fig, ax = plt.subplots(figsize=(3.4, 2.0))
    for branch, (color, ls, mk) in (("closures", STYLE["causal4j"]), ("pruned", STYLE["dfs"])):
        pts = sorted((int(r["n"]), int(r["heap_bytes"])) for r in rq2
                     if r["branch"] == branch and int(r["heap_bytes"]) > 0)
        if pts:
            ax.plot(*zip(*pts), color=color, ls="none", marker=mk, ms=3,
                    label=f"heap, {'closures' if branch == 'closures' else 'layer-pruned'}")
    ns = [int(10 ** (k / 20)) for k in range(20, 111)]
    ax.plot(ns, [2 * n * math.ceil(n / 64) * 8 for n in ns], color="0.3", lw=0.8, label="closure size $n^2/4$ B")
    model = heap_model(rq2)
    if model:
        ax.plot(ns, [model(n) for n in ns], color="black", lw=0.8, ls="--", label="modelled heap, closures")
    for budget, name in ((64e6, "64 MB"), (256e6, "256 MB"), (1e9, "1 GB")):
        ax.axhline(budget, color="0.6", lw=0.6, ls=":")
        n, _ = crossover(rq2, budget)
        ax.text(12, budget * 1.15, f"{name}" + (f": $n\\approx${n:,}" if n else ""), fontsize=6, color="0.3")
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("$|V|$")
    ax.set_ylabel("bytes")
    ax.legend(frameon=False, loc="lower right", fontsize=6)
    ax.grid(True, which="major", lw=0.3, color="0.85")
    fig.tight_layout(pad=0.3)
    fig.savefig(out)
    plt.close(fig)


# ── macros ──────────────────────────────────────────────────────────────────

def numbers(values: dict) -> str:
    lines = ["% Generated by causal4j-bench results_to_tex; overwritten by bench/export_paper.sh.",
             "% Defines every macro of the paper's numbers-defaults.tex: the measured value, or that",
             "% file's \\TODO default when this run does not measure it (yet)."]
    for k, v in values.items():
        lines.append(f"\\providecommand{{\\{k}}}{{{v}}}")
    return "\n".join(lines) + "\n"


def paper_defaults(paper_dir: Path, quick: bool):
    """Macro name -> default from <paper>/numbers-defaults.tex (the list of macros the paper uses)."""
    p = paper_dir / "numbers-defaults.tex"
    if not p.exists():
        if quick:
            print(f"warning: {p} not found; emitting measured macros only", file=sys.stderr)
            return {}
        raise SystemExit(f"{p} not found: pass --paper-dir <paper checkout>")
    return dict(PROVIDE.findall(p.read_text()))


# ── main ────────────────────────────────────────────────────────────────────

def main(argv=None):
    a = argparse.ArgumentParser()
    a.add_argument("run_id")
    a.add_argument("--base", action="append", default=[], help="earlier run to merge (repeatable)")
    a.add_argument("--quick", action="store_true", help="tolerate missing inputs (CI subset)")
    a.add_argument("--paper-dir", default=str(REPO.parent / "Causal4j-paper"))
    args = a.parse_args(argv)
    run = BENCH / "results" / args.run_id
    out = BENCH / "out" / args.run_id
    if not run.is_dir():
        raise SystemExit(f"{run} does not exist")

    runs = runs_of(run, args.base)
    print("runs: " + ", ".join(r.name for r in runs))

    def need(name, optional=False):
        """All JMH results of one step across the runs: <name>/<dataset>.json, newest run first."""
        files = [f for f in merged_files(runs, name) if f.suffix == ".json"]
        if not files and not (args.quick or optional):
            raise SystemExit(f"no run has {name}/ results: run the script that produces it (see bench/README.md)")
        return [r for f in files for r in load_jmh(f)]

    censored = load_censored(runs)
    tier_rows = (need("tier_a") + need("closure_read", optional=args.quick) + need("procedure", optional=args.quick)
                 + need("cypher") + (need("gds") if not args.quick else []))
    rq4_rows = need("rq4") if not args.quick else []
    pruned_rows = need("rq2_pruned") if not args.quick else []
    # tier (a) first, then RQ4 (Causal4j index=on for the remaining synthetic graphs), then pruned
    lat = lookup(tier_rows + rq4_rows + pruned_rows, censored)
    compile_rows = need("compile") if not args.quick else []
    rq2 = rq2_rows(runs)
    if not rq2 and not args.quick:
        raise SystemExit(f"{run / 'rq2.csv'} missing: run bench/scripts/rq2.sh")

    (out / "tables").mkdir(parents=True, exist_ok=True)
    (out / "figures").mkdir(parents=True, exist_ok=True)
    shutil.copy(REPO / "paper" / "tables" / "datasets.tex", out / "tables" / "datasets.tex")

    tier_datasets = [d for d in BNLEARN + ["covid"]] + sorted(
        {r["dataset"] for r in tier_rows if r["dataset"] not in BNLEARN and r["dataset"] != "covid"
         and meta(r["dataset"])["n"] == 1000 and meta(r["dataset"])["model"] == "er"},
        key=lambda d: meta(d)["avg_degree"])
    if not args.quick or tier_rows:
        quick_extra = [r["dataset"] for r in tier_rows if args.quick]
        (out / "tables" / "rq1_tier_a.tex").write_text(
            rq1_table(lat, list(dict.fromkeys(tier_datasets + quick_extra))))
        rq1_csv(tier_rows, censored, out / "tables" / "rq1_tier_a.csv")
    if rq2:
        (out / "tables" / "rq2.tex").write_text(rq2_table(rq2, compile_rows, lat))
        rq2_figure(rq2, out / "figures" / "rq2_crossover.pdf")
    if tier_rows or rq4_rows:
        rq4_figure(lat, rq4_rows, out / "figures" / "rq4_latency.pdf")

    def speedup(baseline, system="causal4j"):
        """Geometric mean over bnlearn + covid x 5 operators of baseline / system median latency."""
        return geomean([lat[(baseline, ds, op)] / lat[(system, ds, op)]
                        for ds in BNLEARN + ["covid"] for op in OPERATORS
                        if (system, ds, op) in lat and (baseline, ds, op) in lat
                        and math.isfinite(lat[(baseline, ds, op)]) and math.isfinite(lat[(system, ds, op)])])

    values = {}
    s_dfs, s_cy, s_pc = speedup("dfs"), speedup("cypher"), speedup("cypher", "procedure")
    values["speedupTierAvsDfs"] = fmt_x(s_dfs) if s_dfs else "\\TODO{x}"
    values["speedupTierAvsCypher"] = fmt_x(s_cy) if s_cy else "\\TODO{x}"
    values["speedupProcedureVsCypher"] = fmt_x(s_pc) if s_pc else "\\TODO{x}"
    overhead = [lat[("procedure", "munin", op)] - lat[("causal4j", "munin", op)] for op in OPERATORS
                if ("procedure", "munin", op) in lat and ("causal4j", "munin", op) in lat]
    values["procedureOverheadUs"] = fmt_us(statistics.median(overhead)) if overhead else "\\TODO{x}"
    read = lat.get(("read", "munin", "ancestors"))
    values["closureReadUsMunin"] = fmt_us(read) if read is not None else "\\TODO{x}"
    munin_build = next((r["median_us"] / 1e3 for r in compile_rows
                        if r["dataset"] == "munin" and r["branch"] == "closures"), None)
    values["buildMsMunin"] = f"{munin_build:.1f}" if munin_build else "\\TODO{ms}"
    values["closureKbMunin"] = f"{2 * 1041 * math.ceil(1041 / 64) * 8 / 1e3:.0f}"
    n256, _ = crossover(rq2, 256e6) if rq2 else (None, None)
    values["crossoverNodes"] = f"{n256:,}".replace(",", "{,}") if n256 else "\\TODO{N}"
    for budget, name in ((64e6, "SixtyFourMB"), (1e9, "OneGB")):
        n, _ = crossover(rq2, budget) if rq2 else (None, None)
        values[f"crossoverNodes{name}"] = f"{n:,}".replace(",", "{,}") if n else "\\TODO{N}"
    # pruned / closure reaches (JMH) at the largest graph measured on both branches
    both = [r["dataset"] for r in rq2 if r["branch"] == "closures"
            and ("pruned", r["dataset"], "reaches") in lat and ("causal4j", r["dataset"], "reaches") in lat]
    if both:
        big = max(both, key=lambda d: meta(d)["n"])
        values["crossoverLatencyRatio"] = fmt_x(lat[("pruned", big, "reaches")] / lat[("causal4j", big, "reaches")])
    else:
        values["crossoverLatencyRatio"] = "\\TODO{x}"

    # Every macro the paper defines a default for, in its order; measured value or its default.
    defaults = paper_defaults(Path(args.paper_dir), args.quick)
    measured = {k: v for k, v in values.items() if not v.startswith("\\TODO")}
    unknown = sorted(set(values) - set(defaults)) if defaults else []
    if unknown:
        print(f"warning: computed macros the paper does not declare: {', '.join(unknown)}", file=sys.stderr)
    final = {k: measured.get(k, d) for k, d in defaults.items()} if defaults else values
    missing = [k for k in defaults if k not in final]
    if missing:
        raise SystemExit(f"numbers.tex would miss macros: {', '.join(missing)}")
    values = final
    (out / "numbers.tex").write_text(numbers(values))
    print(f"wrote {out}")
    for k, v in values.items():
        if not v.startswith("\\TODO"):
            print(f"  \\{k} = {v}")


if __name__ == "__main__":
    main()
