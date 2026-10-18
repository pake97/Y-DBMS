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

from . import BENCH, REPO, stats

TIMEOUT_US = 60e6
SYSTEMS = {"TierABenchmarks": None, "CypherBenchmarks": "cypher", "GdsBenchmarks": "gds",
           "ProcedureBenchmarks": "procedure"}
OPERATORS = ["ancestors", "descendants", "reaches", "confounders", "mediators"]
SYSTEM_LABEL = {"causal4j": "Causal4j", "masked": "Causal4j", "unmasked": "Causal4j, no mask",
                "reference": "DFS", "procedure": "Causal4j (procedure)",
                "procids": "Causal4j (procedure, ids)", "dfs": "DFS", "cypher": "Cypher", "gds": "GDS"}
# Systems of the paper table, per operator, in order; "read" is the closure read alone
# (TierABenchmarks.ancestorsCount). descendants mirrors ancestors and is kept in the CSV only.
TABLE_BLOCKS = [
    ("ancestors", ["read", "causal4j", "procedure", "procids", "dfs", "cypher", "gds"]),
    ("reaches", ["causal4j", "procedure", "dfs", "cypher", "gds"]),
    ("confounders", ["causal4j", "procedure", "procids", "dfs", "cypher"]),
    ("mediators", ["causal4j", "procedure", "procids", "dfs", "cypher"]),
]
COLUMN_LABEL = {"read": "read", "causal4j": "in-proc.", "procedure": "proc.", "procids": "proc. (ids)",
                "dfs": "DFS", "cypher": "Cypher", "gds": "GDS"}
BNLEARN = ["asia", "sachs", "child", "alarm", "hepar2", "win95pts", "pathfinder",
           "andes", "pigs", "link", "munin"]
# Every non-synthetic dataset, in table order; covid and policy carry latent variables.
REAL = BNLEARN + ["covid", "policy"]

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
        if cls == "TierBCBenchmarks":
            # dsep -> masked/unmasked, dsepReference -> reference (July Bayes-Ball), both as
            # dsep_sep / dsep_conn by verdict; decideReference -> legacy decide; the rest causal4j.
            verdict = "dsep_" + ("sep" if params.get("verdict") == "separated" else "conn")
            system, op = {"dsep": ("masked" if params.get("mask") == "true" else "unmasked", verdict),
                          "dsepReference": ("reference", verdict),
                          "decideReference": ("legacy", "decide")}.get(op, ("causal4j", op))
        if cls == "ProcedureBenchmarks" and op.endswith("Ids"):
            system, op = "procids", op[:-3]  # causal.*Ids: one id column, no node binding
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


def load_roundtrip(runs, step):
    """roundtrip.py CSVs of one step as rows like load_jmh's (system = library); a (library,
    operator) whose first query already hit the budget is infinite (t/o)."""
    rows = []
    for f in merged_files(runs, step):
        with open(f, newline="") as fh:
            for r in csv.DictReader(fh):
                timed_out = r["censored"] == "True" and int(r["queries"]) <= 1
                rows.append({"system": r["library"], "dataset": r["dataset"], "operator": r["operator"],
                             "median_us": math.inf if timed_out else float(r["query_ms_median"]) * 1e3,
                             "p99_us": math.inf if timed_out else float(r["query_ms_p99"]) * 1e3})
    return rows


def legacy_pairs(runs):
    """legacy_pairs.csv rows, the newest run per dataset, one row per (dataset, t, y)."""
    out, seen_ds = {}, set()
    for run in runs:
        p = run / "legacy_pairs.csv"
        if not p.exists():
            continue
        with open(p, newline="") as f:
            rows = list(csv.DictReader(f))
        fresh = {r["dataset"] for r in rows} - seen_ds
        for r in rows:
            if r["dataset"] in fresh:
                out.setdefault((r["dataset"], r["t"], r["y"]), r)
        seen_ds |= fresh
    return list(out.values())


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


def fmt_pair(a, b):
    """separated / connected medians in one cell."""
    return "--" if a is None and b is None else f"{fmt_us(a)}\\,/\\,{fmt_us(b)}"


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
    if dataset == "policy":
        return "Policy"
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
             "% Synthetic rows: one timed graph per (model, |V|, d), seed 0.",
             f"\\begin{{tabular}}{{@{{}}l{'r' * len(cols)}@{{}}}}", "\\toprule",
             f"DAG & {head1} \\\\", " ".join(rules), f" & {head2} \\\\", "\\midrule"]
    for ds in datasets:
        if not any(k[1] == ds for k in lat):
            continue
        cells = " & ".join(fmt_us(lat.get((s, ds, op))) for op, s in cols)
        lines.append(f"{label(ds)} & {cells} \\\\")
    lines += ["\\bottomrule", "\\end{tabular}"]
    return "\n".join(lines) + "\n"


STRATA = ["0", "1", "2", "3+", "none"]
STRATUM_LABEL = {"0": "$k{=}0$", "1": "$k{=}1$", "2": "$k{=}2$", "3+": "$k{\\ge}3$", "none": "none / cens."}


def stratum(row):
    """k of the smallest backdoor set the July enumeration found: 0, 1, 2, 3+, or none (no set
    found, or censored)."""
    if row["censored"] == "true" or row["k"] == "none":
        return "none"
    return row["k"] if int(row["k"]) < 3 else "3+"


def enumeration_table(rows):
    """decide vs the July enumeration per dataset, stratified by k: pairs, median legacy and
    decide µs per stratum (finished pairs), and the pairs censored at 60 s."""
    by = defaultdict(lambda: defaultdict(list))
    for r in rows:
        by[r["dataset"]][stratum(r)].append(r)
    lines = ["% Generated by causal4j-bench results_to_tex. decide vs the July level-wise enumeration, by k =",
             "% size of the smallest backdoor set the enumeration finds; per stratum: pairs, median enumeration",
             "% and decide latency in µs over the finished pairs; cens. = pairs censored at 60 s (in none / cens.).",
             f"\\begin{{tabular}}{{@{{}}l{'rrr' * len(STRATA)}r@{{}}}}", "\\toprule",
             "DAG & " + " & ".join(f"\\multicolumn{{3}}{{c}}{{{STRATUM_LABEL[k]}}}" for k in STRATA) + " & \\\\",
             " ".join(f"\\cmidrule(lr){{{2 + 3 * i}-{4 + 3 * i}}}" for i in range(len(STRATA))),
             " & " + " & ".join("pairs & enum. & decide" for _ in STRATA) + " & cens. \\\\", "\\midrule"]
    for ds in [d for d in REAL if d in by] + sorted(d for d in by if d not in REAL):
        cells = []
        for k in STRATA:
            rs = by[ds][k]
            done = [r for r in rs if r["censored"] == "false"]
            med = lambda c: fmt_us(statistics.median(float(r[c]) for r in done)) if done else "--"  # noqa: E731
            cells += [str(len(rs)), med("legacy_us"), med("decide_us")]
        cells.append(str(sum(r["censored"] == "true" for r in by[ds]["none"])))
        lines.append(f"{label(ds)} & " + " & ".join(cells) + " \\\\")
    lines += ["\\bottomrule", "\\end{tabular}"]
    return "\n".join(lines) + "\n"


BC_DSEP = ["masked", "unmasked", "reference", "pgmpy", "dagitty"]
BC_DECIDE = ["causal4j", "legacy", "pgmpy", "dagitty", "dowhy"]
BC_LABEL = {"masked": "masked", "unmasked": "unmasked", "reference": "DFS", "pgmpy": "pgmpy",
            "dagitty": "dagitty", "causal4j": "Causal4j", "legacy": "enum.", "dowhy": "DoWhy"}


def rq1_bc_table(lat, datasets, legacy_censored):
    """Tiers (b) and (c): d-separation cells are separated / connected medians (balanced
    triples); identification over pairs.csv. enum. = the July level-wise enumeration over the
    pairs that finished within 60 s, with the number of censored pairs in parentheses."""
    def decide_cell(s, ds):
        v = lat.get((s, ds, "adjustment" if s in ("pgmpy", "dagitty", "dowhy") else "decide"))
        if s != "legacy":
            return fmt_us(v)
        k = legacy_censored.get(ds, 0)
        if v is None and not k:
            return "--"
        return ("t/o" if v is None else fmt_us(v)) + (f" ({k})" if k else "")
    lines = ["% Generated by causal4j-bench results_to_tex. Median latency in µs; t/o = 60 s timeout.",
             "% d-separation: separated / connected triples of triples_balanced.csv (masked and unmasked",
             "% ancestral masking; DFS = the July Bayes-Ball over id sets). identification: decide =",
             "% Adjustment.decide; enum. = July level-wise enumeration, (k) = pairs censored at 60 s;",
             "% pgmpy = get_minimal_adjustment_set, dagitty = canonical set, DoWhy = identify_effect.",
             "% Synthetic rows: one timed graph per (model, |V|, d), seed 0.",
             f"\\begin{{tabular}}{{@{{}}l{'r' * (len(BC_DSEP) + len(BC_DECIDE) + 2)}@{{}}}}", "\\toprule",
             f"DAG & \\multicolumn{{{len(BC_DSEP)}}}{{c}}{{d-separation}} & "
             f"\\multicolumn{{{len(BC_DECIDE)}}}{{c}}{{identification}} & \\multicolumn{{2}}{{c}}{{minimal sets}} \\\\",
             f"\\cmidrule(lr){{2-{1 + len(BC_DSEP)}}} \\cmidrule(lr){{{2 + len(BC_DSEP)}-{1 + len(BC_DSEP) + len(BC_DECIDE)}}} "
             f"\\cmidrule(lr){{{2 + len(BC_DSEP) + len(BC_DECIDE)}-{3 + len(BC_DSEP) + len(BC_DECIDE)}}}",
             " & " + " & ".join(BC_LABEL[s] for s in BC_DSEP + BC_DECIDE) + " & list $\\le$100 & find \\\\",
             "\\midrule"]
    for ds in datasets:
        if not any(k[1] == ds and (k[2].startswith("dsep_") or k[2] in ("decide", "adjustment")) for k in lat):
            continue
        cells = [fmt_pair(lat.get((s, ds, "dsep_sep")), lat.get((s, ds, "dsep_conn"))) for s in BC_DSEP]
        cells += [decide_cell(s, ds) for s in BC_DECIDE]
        cells += [fmt_us(lat.get(("causal4j", ds, "listMinimal100"))), fmt_us(lat.get(("causal4j", ds, "findMinimal")))]
        lines.append(f"{label(ds)} & " + " & ".join(cells) + " \\\\")
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
             "% latency in µs (median). c = closures, p = layer-pruned. Synthetic rows: seed 0.",
             "\\begin{tabular}{@{}lrrrrrrrr@{}}", "\\toprule",
             "DAG & \\multicolumn{2}{c}{build (ms)} & heap (KB) & closures (KB) & "
             "\\multicolumn{2}{c}{ancestors (µs)} & \\multicolumn{2}{c}{reaches (µs)} \\\\",
             " & c & p & c & & c & p & c & p \\\\", "\\midrule"]
    order = [d for d in REAL if d in by] + sorted(
        (d for d in by if d not in REAL
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


RQ5_INT = ["triples", "triples_agree", "pairs", "canonical_agree", "identifiable", "not_identifiable",
           "families_compared", "families_equal", "families_validated", "sets_validated", "sets_failing"]


def fmt_n(v):
    """Integer with thin-space thousands separators."""
    return f"{v:,}".replace(",", "\\,")


def rq5_rows(runs):
    """rq5/rq5.csv of the newest run that has one, as {dataset: {column: int}}."""
    for run in runs:
        p = run / "rq5" / "rq5.csv"
        if p.exists():
            with open(p, newline="") as f:
                return {r["dataset"]: {k: int(r[k] or 0) for k in RQ5_INT} for r in csv.DictReader(f)}
    return {}


def rq5_table(rq5):
    """One row per bnlearn network, covid and policy, one per synthetic size, and a total."""
    def add(rows):
        return {k: sum(r[k] for r in rows) for k in RQ5_INT}
    groups = [(label(d), [rq5[d]]) for d in REAL if d in rq5]
    syn = defaultdict(list)
    for d, r in rq5.items():
        if d not in REAL:
            syn[meta(d)["n"]].append((d, r))
    for n in sorted(syn):
        groups.append((f"synthetic $n{{=}}{n}$ ({len(syn[n])})", [r for _, r in syn[n]]))
    lines = ["% Generated by causal4j-bench results_to_tex. RQ5: Causal4j against the dagitty/pgmpy reference.",
             "% dsep: triples.csv + triples_balanced.csv; canonical: identifiable and canonical set equal;",
             "% families: listMinimal equal to dagitty's minimal family; validated: pairs whose family exceeded",
             "% WP5a's limits, every set of listMinimal(100) checked with isAdjustmentSet and removal-minimality.",
             "\\begin{tabular}{@{}lrrrrrrrrrr@{}}", "\\toprule",
             "DAG & \\multicolumn{2}{c}{d-separation} & \\multicolumn{3}{c}{identification} & "
             "\\multicolumn{2}{c}{families} & \\multicolumn{3}{c}{validated per set} \\\\",
             "\\cmidrule(lr){2-3} \\cmidrule(lr){4-6} \\cmidrule(lr){7-8} \\cmidrule(lr){9-11}",
             " & triples & agree & pairs & agree & not id. & compared & equal & pairs & sets & failing \\\\",
             "\\midrule"]
    cols = ["triples", "triples_agree", "pairs", "canonical_agree", "not_identifiable", "families_compared",
            "families_equal", "families_validated", "sets_validated", "sets_failing"]
    for name, rows in groups + [("total", list(rq5.values()))]:
        if name == "total":
            lines.append("\\midrule")
        t = add(rows)
        lines.append(f"{name} & " + " & ".join(fmt_n(t[c]) for c in cols) + " \\\\")
    lines += ["\\bottomrule", "\\end{tabular}"]
    return "\n".join(lines) + "\n"


# ── figures ─────────────────────────────────────────────────────────────────

STYLE = {"causal4j": ("black", "-", "o"), "dfs": ("0.45", "--", "s"),
         "cypher": ("0.25", ":", "^"), "gds": ("0.6", "-.", "D"),
         "masked": ("black", "-", "o"), "unmasked": ("0.6", "-.", "v"), "reference": ("0.45", "--", "s")}


def _plt():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.size": 8, "axes.titlesize": 8, "legend.fontsize": 7,
                         "pdf.fonttype": 42, "font.family": "serif"})
    return plt


# RQ4 panels: (operator, title, systems). Systems in SEEDED come from the RQ4 runs (seed 0 per
# (model, |V|, d); a median if a run has more seeds); the others are the seed-0 graphs of the RQ1 runs.
RQ4_PANELS = [("ancestors", "ancestors", ["causal4j", "dfs", "cypher", "gds"]),
              ("reaches", "reaches", ["causal4j", "dfs", "cypher", "gds"]),
              ("dsep_conn", "d-separation (connected)", ["masked", "unmasked", "reference"]),
              ("decide", "decide", ["causal4j"])]
SEEDED = {"causal4j", "masked"}


def rq4_figure(lat, rq4_rows, out: Path):
    """Latency vs |V| on ER d=4 (seed 0), 2x2 panels at \\linewidth: tier (a) ancestors and reaches,
    masked d-separation on connected triples, and decide."""
    plt = _plt()
    fig, axes = plt.subplots(2, 2, figsize=(3.4, 3.3), sharex=True)
    seeds = defaultdict(list)
    for r in rq4_rows:
        m = meta(r["dataset"])
        if m["model"] == "er" and m["avg_degree"] == 4:
            seeds[(r["system"], r["operator"], m["n"])].append(r["median_us"])
    legend = {}
    for ax, (op, title, systems) in zip(axes.flat, RQ4_PANELS):
        for system in systems:
            pts = sorted((n, statistics.median(v)) for (s, o, n), v in seeds.items() if s == system and o == op)
            cens = []
            if not (system in SEEDED and pts):
                pts = []
                for (s, ds, o), v in lat.items():
                    if s != system or o != op or ds in REAL:
                        continue
                    m = meta(ds)
                    if m["model"] == "er" and m["avg_degree"] == 4 and m["seed"] == 0:
                        (cens if v == math.inf else pts).append((m["n"], TIMEOUT_US if v == math.inf else v))
                pts.sort()
            if not pts and not cens:
                continue
            color, ls, mk = STYLE[system]
            if pts:
                line, = ax.plot(*zip(*pts), color=color, ls=ls, marker=mk, ms=3, lw=1)
                legend.setdefault(SYSTEM_LABEL[system], line)
            if cens:
                ax.plot(*zip(*sorted(cens)), ls="none", marker=mk, ms=4, mfc="none", color=color)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_title(title)
        ax.grid(True, which="major", lw=0.3, color="0.85")
    for ax in axes[1]:
        ax.set_xlabel("$|V|$")
    for ax in axes[:, 0]:
        ax.set_ylabel("median latency (µs)")
    fig.legend(list(legend.values()), list(legend), frameon=False, loc="lower center", ncol=3, fontsize=7)
    fig.tight_layout(pad=0.3, rect=(0, 0.1, 1, 1))
    fig.savefig(out)
    plt.close(fig)


# ── RQ3 ─────────────────────────────────────────────────────────────────────

RQ3_TYPES = ["insert", "delete", "active", "latent", "addvar", "remove"]
RQ3_TYPE_LABEL = {"insert": "insert edge", "delete": "delete edge", "active": "\\texttt{active} flip",
                  "latent": "\\texttt{latent} flip", "addvar": "add variable", "remove": "remove variable"}
RQ3_BATCHES = [1, 10, 100, 1000]
RQ3_THRESHOLDS = ["0.125", "0.25", "0.5", "1"]
THRESHOLD_LABEL = {"0.125": "$|V|/8$", "0.25": "$|V|/4$", "0.5": "$|V|/2$", "1": "$|V|$"}


def load_rq3(runs, step):
    """MaintenanceBenchmarks rows of one step (rq3/b<batch> or rq3_threshold), newest run per file."""
    out = []
    for run in runs:
        d = run / step
        if not d.is_dir():
            continue
        seen = {(r["dataset"], r["batch"]) for r in out}
        for f in sorted(d.rglob("*.json")):
            for b in json.loads(f.read_text()):
                p = b["params"]
                if (p["dataset"], int(p["batch"])) in seen:
                    continue
                pm = b["primaryMetric"]
                scale = {"us/op": 1.0, "ms/op": 1e3, "ns/op": 1e-3, "s/op": 1e6}[pm["scoreUnit"]]
                out.append({"op": b["benchmark"].split(".")[-1], "dataset": p["dataset"], "type": p["type"],
                            "batch": int(p["batch"]), "threshold": p["threshold"],
                            "median_us": pm["scorePercentiles"]["50.0"] * scale})
    return out


def rq3_ratios(rows):
    """(dataset, type, batch, threshold) -> patch / recompile median."""
    med = {(r["op"], r["dataset"], r["type"], r["batch"], r["threshold"]): r["median_us"] for r in rows}
    return {k[1:]: v / med[("recompile",) + k[1:]] for k, v in med.items()
            if k[0] == "patch" and ("recompile",) + k[1:] in med}


def rq3_group(ds):
    """bnlearn, covid and policy together; synthetic by n."""
    return "bnlearn" if ds in REAL else f"n{meta(ds)['n']}"


def fmt_pct(v):
    return "--" if v is None else (f"{100 * v:.0f}\\%" if v >= 0.095 else f"{100 * v:.1f}\\%")


def rq3_table(ratios, mixed, composed):
    """Patch time as a percentage of recompile time (median over the group's datasets) by edit
    type and batch size, for bnlearn (+ covid, policy) and the synthetic sizes; then query p99 of
    the mixed workload under the three policies."""
    groups = sorted({rq3_group(k[0]) for k in ratios}, key=lambda g: (g != "bnlearn", len(g), g))
    def cell(g, t, b):
        v = [r for (ds, ty, bb, th), r in ratios.items() if rq3_group(ds) == g and ty == t and bb == b and th == "0.5"]
        return fmt_pct(statistics.median(v)) if v else "--"
    title = {g: "bnlearn, COVID-19, Policy" if g == "bnlearn" else f"ER/BA $n{{=}}{g[1:]}$" for g in groups}
    lines = ["% Generated by causal4j-bench results_to_tex. RQ3: SnapshotPatcher.apply time as a percentage of a",
             "% full recompile (median over the group's datasets; a declined patch pays the recompile too),",
             "% by edit type and batch size; causal.patch.threshold = |V|/2. Synthetic: ER and BA, d = 4, seed 0.",
             f"\\begin{{tabular}}{{@{{}}l{'r' * len(RQ3_BATCHES) * len(groups)}@{{}}}}", "\\toprule",
             "edit & " + " & ".join(f"\\multicolumn{{{len(RQ3_BATCHES)}}}{{c}}{{{title[g]}}}" for g in groups) + " \\\\",
             " ".join(f"\\cmidrule(lr){{{2 + len(RQ3_BATCHES) * i}-{1 + len(RQ3_BATCHES) * (i + 1)}}}" for i in range(len(groups))),
             "batch & " + " & ".join(str(b) for _ in groups for b in RQ3_BATCHES) + " \\\\", "\\midrule"]
    for t in RQ3_TYPES:
        lines.append(f"{RQ3_TYPE_LABEL[t]} & " + " & ".join(cell(g, t, b) for g in groups for b in RQ3_BATCHES) + " \\\\")
    if mixed:
        lines += ["\\midrule", f"\\multicolumn{{{1 + len(RQ3_BATCHES) * len(groups)}}}{{@{{}}l}}{{mixed workload: query "
                  "$p_{99}$ (µs), median over datasets; (iii) composed from Phase 1 pgmpy measurements} \\\\"]
        for pol, name in (("patch", "(i) patch"), ("recompile", "(ii) recompile"), ("external", "(iii) re-export")):
            src = composed if pol == "external" else mixed
            cells = []
            for k in (1, 10, 100):
                v = [r["p99_us"] for r in src if r["policy"] == pol and r["k"] == k]
                cells.append(f"$k{{=}}{k}$: {fmt_us(statistics.median(v)) if v else '--'}")
            lines.append(f"{name} & \\multicolumn{{{len(RQ3_BATCHES) * len(groups)}}}{{l}}{{" + ", ".join(cells) + "} \\\\")
    lines += ["\\bottomrule", "\\end{tabular}"]
    return "\n".join(lines) + "\n"


def load_mixed(runs):
    """Rq3Mixed rows: rq3_mixed/<dataset>.csv (newest run per dataset), or a run's single rq3_mixed.csv."""
    files = [f for f in merged_files(runs, "rq3_mixed") if f.suffix == ".csv"]
    if not files:
        files = [run / "rq3_mixed.csv" for run in runs if (run / "rq3_mixed.csv").exists()][:1]
    rows = []
    for p in files:
        with open(p, newline="") as f:
            rows += [{**r, "k": int(r["k"]), "p99_us": float(r["p99_us"]), "median_us": float(r["median_us"])}
                     for r in csv.DictReader(f)]
    return rows


def compose_external(runs, datasets, queries=10_000):
    """Policy (iii) from the Phase 1 pgmpy round trip: every k-th query pays a re-export plus
    rebuild (export_ms + build_ms), each query pgmpy's median for its operator (ancestors for
    ancestors/confounders/mediators, reaches, adjustment for decide), operators uniform as in
    Rq3Mixed. Rows like load_mixed's."""
    cost = defaultdict(dict)
    for f in merged_files(runs, "roundtrip"):
        with open(f, newline="") as fh:
            for r in csv.DictReader(fh):
                if r["library"] == "pgmpy":
                    cost[r["dataset"]][r["operator"]] = float(r["query_ms_median"]) * 1e3
                    cost[r["dataset"]]["stall"] = (float(r["export_ms"]) + float(r["build_ms"])) * 1e3
    out = []
    for ds in datasets:
        c = cost.get(ds, {})
        if not {"ancestors", "reaches", "adjustment", "stall"} <= set(c):
            continue
        ops = [c["ancestors"], c["reaches"], c["ancestors"], c["ancestors"], c["adjustment"]]
        rnd = __import__("random").Random(0)
        for k in (1, 10, 100):
            lat = sorted(ops[rnd.randrange(5)] + (c["stall"] if q % k == 0 else 0) for q in range(queries))
            out.append({"dataset": ds, "policy": "external", "k": k,
                        "median_us": lat[queries // 2], "p99_us": lat[int(0.99 * queries)]})
    return out


def rq3_figure(ratios, mixed, composed, out: Path):
    """Panel 1: patch/recompile vs batch size per edit type (bnlearn group); panel 2: query p99
    vs k under the three policies (median over datasets)."""
    plt = _plt()
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(3.4, 2.3))
    marks = dict(zip(RQ3_TYPES, "osD^v<"))
    for t in RQ3_TYPES:
        pts = []
        for b in RQ3_BATCHES:
            v = [r for (ds, ty, bb, th), r in ratios.items() if rq3_group(ds) == "bnlearn" and ty == t and bb == b and th == "0.5"]
            if v:
                pts.append((b, statistics.median(v)))
        if pts:
            a1.plot(*zip(*pts), marker=marks[t], ms=3, lw=0.8, color="0.2", label=t)
    a1.axhline(1, color="0.6", lw=0.6, ls=":")
    a1.set_xscale("log")
    a1.set_yscale("log")
    a1.set_xlabel("batch size")
    a1.set_ylabel("patch / recompile")
    for pol, (color, ls, mk) in (("patch", STYLE["causal4j"]), ("recompile", STYLE["dfs"]), ("external", STYLE["cypher"])):
        src = composed if pol == "external" else mixed
        pts = [(k, statistics.median(v)) for k in (1, 10, 100)
               if (v := [r["p99_us"] for r in src if r["policy"] == pol and r["k"] == k])]
        if pts:
            a2.plot(*zip(*pts), color=color, ls=ls, marker=mk, ms=3, lw=1, label={"patch": "(i) patch",
                    "recompile": "(ii) recompile", "external": "(iii) re-export"}[pol])
    a2.set_xscale("log")
    a2.set_yscale("log")
    a2.set_xlabel("queries per edit $k$")
    a2.set_ylabel("query $p_{99}$ (µs)")
    a2.legend(frameon=False, fontsize=5)
    for ax in (a1, a2):
        ax.grid(True, which="major", lw=0.3, color="0.85")
    h, l = a1.get_legend_handles_labels()
    fig.legend(h, l, frameon=False, loc="lower center", ncol=6, fontsize=5, handlelength=1.2)
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
    rq4_rows = (need("rq4") + need("rq4_bc", optional=True)) if not args.quick else []
    pruned_rows = need("rq2_pruned") if not args.quick else []
    # Phase 2 (tiers b/c) is optional until its run exists; RQ3 waits for WP3.
    bc_rows = need("tier_bc", optional=True) + need("legacy", optional=True) + load_roundtrip(runs, "roundtrip_bc")
    # The legacy enumeration's censored pairs are counted in its cell, not a censored point.
    legacy_censored = defaultdict(set)
    for run in runs:
        if (run / "timeouts.csv").exists():
            with open(run / "timeouts.csv", newline="") as f:
                for r in csv.DictReader(f):
                    if r["system"] == "legacy":
                        legacy_censored[r["dataset"]].add(r["args"])
    legacy_censored = {ds: len(a) for ds, a in legacy_censored.items()}
    censored = {k for k in censored if k[0] != "legacy"}
    # tier (a) first, then RQ4 (Causal4j index=on for the remaining synthetic graphs), then pruned
    lat = lookup(tier_rows + rq4_rows + pruned_rows + bc_rows, censored)
    # reaches has no ids variant (causal.hasDirectedPath returns a boolean): its ids
    # procedure is the procedure itself, so the ids aggregates cover all 5 operators.
    for (s_, ds, op), v in list(lat.items()):
        if s_ == "procedure" and op == "reaches":
            lat.setdefault(("procids", ds, op), v)
    compile_rows = need("compile") if not args.quick else []
    rq2 = rq2_rows(runs)
    if not rq2 and not args.quick:
        raise SystemExit(f"{run / 'rq2.csv'} missing: run bench/scripts/rq2.sh")

    (out / "tables").mkdir(parents=True, exist_ok=True)
    (out / "figures").mkdir(parents=True, exist_ok=True)
    with open(BENCH / "datasets" / "datasets.csv", newline="") as f:
        (out / "tables" / "datasets.tex").write_text(stats.latex(list(csv.DictReader(f))))

    tier_datasets = list(REAL) + sorted(
        {r["dataset"] for r in tier_rows if r["dataset"] not in REAL
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
    if bc_rows:
        (out / "tables" / "rq1_tier_bc.tex").write_text(
            rq1_bc_table(lat, list(dict.fromkeys(tier_datasets + [r["dataset"] for r in bc_rows])), legacy_censored))

    def speedup(baseline, system="causal4j"):
        """Geometric mean over bnlearn + covid x 5 operators of baseline / system median latency."""
        return geomean([lat[(baseline, ds, op)] / lat[(system, ds, op)]
                        for ds in BNLEARN + ["covid"] for op in OPERATORS
                        if (system, ds, op) in lat and (baseline, ds, op) in lat
                        and math.isfinite(lat[(baseline, ds, op)]) and math.isfinite(lat[(system, ds, op)])])

    values = {}
    s_dfs, s_cy = speedup("dfs"), speedup("cypher")
    s_ids, s_nodes = speedup("cypher", "procids"), speedup("cypher", "procedure")
    values["speedupTierAvsDfs"] = fmt_x(s_dfs) if s_dfs else "\\TODO{x}"
    values["speedupTierAvsCypher"] = fmt_x(s_cy) if s_cy else "\\TODO{x}"
    # The paper's procedure comparison uses the ids variants (what RETURN variable.id needs);
    # the node-returning procedures are reported alongside.
    values["speedupProcedureVsCypher"] = fmt_x(s_ids) if s_ids else "\\TODO{x}"
    values["speedupProcedureNodesVsCypher"] = fmt_x(s_nodes) if s_nodes else "\\TODO{x}"
    # Fixed cost of answering through Neo4j: ids procedure minus in-process, median over 5 operators.
    overhead = [lat[("procids", "munin", op)] - lat[("causal4j", "munin", op)] for op in OPERATORS
                if ("procids", "munin", op) in lat and ("causal4j", "munin", op) in lat]
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

    # Tiers (b)/(c), over bnlearn + covid: unmasked / masked d-separation, median over the
    # (dataset, verdict) cells; legacy / decide, median over the pairs the legacy finished.
    ratios = [lat[("unmasked", ds, v)] / lat[("masked", ds, v)] for ds in BNLEARN + ["covid"]
              for v in ("dsep_sep", "dsep_conn") if ("unmasked", ds, v) in lat and ("masked", ds, v) in lat]
    values["speedupDsepMasked"] = fmt_x(statistics.median(ratios)) if ratios else "\\TODO{x}"
    lp_all = legacy_pairs(runs)
    if lp_all:
        (out / "tables" / "rq1_enumeration.tex").write_text(enumeration_table(lp_all))
    lp = [r for r in lp_all if r["dataset"] in BNLEARN + ["covid"]]
    hard = [float(r["legacy_us"]) / float(r["decide_us"]) for r in lp if stratum(r) in ("2", "3+")]
    values["speedupDecideVsEnumeration"] = fmt_x(statistics.median(hard)) if hard else "\\TODO{x}"
    if lp:
        values["numPairsEnumerationCensored"] = fmt_n(sum(r["censored"] == "true" for r in lp))
        values["numPairsEmptySetSuffices"] = fmt_n(sum(stratum(r) == "0" for r in lp))

    # RQ3
    rq3_rows = [r for b in RQ3_BATCHES for r in load_rq3(runs, f"rq3/b{b}")]
    ratios = rq3_ratios(rq3_rows)
    mixed = load_mixed(runs)
    composed = compose_external(runs, sorted({r["dataset"] for r in mixed}))
    if ratios:
        (out / "tables" / "rq3.tex").write_text(rq3_table(ratios, mixed, composed))
        rq3_figure(ratios, mixed, composed, out / "figures" / "rq3_incremental.pdf")
    for macro, t in (("patchVsRecompileInsert", "insert"), ("patchVsRecompileDelete", "delete")):
        v = [r for (ds, ty, b, th), r in ratios.items() if ds in BNLEARN + ["covid"] and ty == t and b == 1 and th == "0.5"]
        values[macro] = fmt_pct(statistics.median(v)) if v else "\\TODO{x}"
    sweep = rq3_ratios(load_rq3(runs, "rq3_threshold"))
    by_t = {th: statistics.median(v) for th in RQ3_THRESHOLDS
            if (v := [r for (ds, ty, b, t), r in sweep.items() if t == th])}
    if by_t:
        over = [th for th in RQ3_THRESHOLDS if th in by_t and by_t[th] >= 1]
        values["fallbackThreshold"] = THRESHOLD_LABEL[over[0]] if over else "$>|V|$"
        print("threshold sweep (median patch/recompile, delete stream, batch 10): "
              + ", ".join(f"{THRESHOLD_LABEL[t]} {v:.2f}" for t, v in by_t.items()))
    p99 = {(r["dataset"], r["policy"]): r["p99_us"] for r in mixed if r["k"] == 10}
    sp = [p99[(ds, "recompile")] / p99[(ds, "patch")] for ds in {d for d, _ in p99}
          if (ds, "recompile") in p99 and (ds, "patch") in p99]
    values["mixedWorkloadSpeedup"] = fmt_x(statistics.median(sp)) if sp else "\\TODO{x}"

    rq5 = rq5_rows(runs)
    if rq5:
        (out / "tables" / "rq5.tex").write_text(rq5_table(rq5))
        tot = {k: sum(r[k] for r in rq5.values()) for k in RQ5_INT}
        for macro, col in (("numDsepQueries", "triples"), ("numDsepAgree", "triples_agree"),
                           ("numPairs", "pairs"), ("numCanonicalAgree", "canonical_agree"),
                           ("numPairsNotIdentifiable", "not_identifiable"),
                           ("numFamiliesCompared", "families_compared"), ("numFamiliesEqual", "families_equal"),
                           ("numFamiliesChecked", "families_validated")):
            values[macro] = fmt_n(tot[col])

    # Every macro the paper defines a default for, in its order; measured value or its default.
    defaults = paper_defaults(Path(args.paper_dir), args.quick)
    measured = {k: v for k, v in values.items() if not v.startswith("\\TODO")}
    unknown = sorted(set(values) - set(defaults)) if defaults else []
    if unknown:  # emitted anyway (\providecommand), so the paper can add them to numbers-defaults.tex
        print(f"warning: macros the paper's numbers-defaults.tex does not declare yet: {', '.join(unknown)}",
              file=sys.stderr)
    final = {k: measured.get(k, d) for k, d in defaults.items()} if defaults else dict(values)
    final.update({k: values[k] for k in unknown})
    values = final
    (out / "numbers.tex").write_text(numbers(values))
    print(f"wrote {out}")
    for k, v in values.items():
        if not v.startswith("\\TODO"):
            print(f"  \\{k} = {v}")
    todo = [k for k, v in values.items() if v.startswith("\\TODO")]
    if todo and not args.quick:
        raise SystemExit(f"numbers.tex still has \\TODO for: {', '.join(todo)} (run the steps that measure them)")


if __name__ == "__main__":
    main()
