"""causal4j-bench fetch|covid|synth|workloads|stats|reference|all"""
import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path

import networkx as nx

from . import DATASETS, REFERENCE, REPO, WORKLOADS, bif, reference, stats, synthetic, workloads
from .io import git_commit, read_csv, read_dataset, read_json, write_csv, write_edges, write_json, write_rows_json

BNLEARN = ["asia", "sachs", "child", "alarm", "hepar2", "win95pts", "pathfinder",
           "andes", "pigs", "link", "munin"]
URL = "https://www.bnlearn.com/bnrepository/{0}/{0}.bif.gz"
# Table III as first typed into paper/sections/08_evaluation.tex (from memory).
PAPER_COUNTS = {"asia": (8, 8), "sachs": (11, 17), "child": (20, 25), "alarm": (37, 46),
                "hepar2": (70, 123), "win95pts": (76, 112), "pathfinder": (109, 195),
                "andes": (223, 338), "pigs": (441, 592), "link": (724, 1125), "munin": (1041, 1397)}
REFERENCE_MAX_N = 1000  # dagitty in R is slow beyond this; synthetic graphs only
MINIMAL_MAX_N = 316     # minimal families explode on larger synthetic graphs; dsep + canonical only above


def datasets(only=None):
    """(name, family, dir) for every generated dataset, bnlearn first, then covid, then synthetic."""
    found = [(d.name, "bnlearn", d) for d in (DATASETS / "bnlearn").glob("*") if (d / "edges.csv").exists()]
    found.sort(key=lambda t: BNLEARN.index(t[0]) if t[0] in BNLEARN else len(BNLEARN))
    if (DATASETS / "covid" / "edges.csv").exists():
        found.append(("covid", "covid", DATASETS / "covid"))
    syn = {synthetic.name(*p): p for p in synthetic.grid()}
    found += [(n, "synthetic", DATASETS / "synthetic" / n) for n in syn
              if (DATASETS / "synthetic" / n / "edges.csv").exists()]
    return [t for t in found if only is None or t[0] in only]


# ── steps ──────────────────────────────────────────────────────────────────

def cmd_fetch(only):
    names = [n for n in BNLEARN if only is None or n in only]
    subprocess.run([str(DATASETS / "fetch.sh"), *names], check=True)
    diff = []
    for name in names:
        d = DATASETS / "bnlearn" / name
        gz = d / f"{name}.bif.gz"
        g = bif.parse_file(gz)
        write_edges(d / "edges.csv", g)
        write_json(d / "meta.json", {
            "source": "bnlearn", "name": name, "url": URL.format(name),
            "sha256": hashlib.sha256(gz.read_bytes()).hexdigest(),
            "n": g.number_of_nodes(), "m": g.number_of_edges(), "nodes": list(g.nodes)})
        got, paper = (g.number_of_nodes(), g.number_of_edges()), PAPER_COUNTS[name]
        diff.append(f"  {name:<11} parsed {got[0]:>5}/{got[1]:<5} paper {paper[0]:>5}/{paper[1]:<5}"
                    + ("" if got == paper else "  <-- differs"))
    print("bnlearn |V|/|E|, parsed vs paper Table III (parsed wins):")
    print("\n".join(diff))


SEED_ID = re.compile(r"\{id:\s*'([^']+)'[^}]*?latent:\s*(true|false)")
SEED_EDGE = re.compile(r"\{from:\s*'([^']+)',\s*to:\s*'([^']+)'")


def cmd_covid():
    src = REPO / "dev" / "seed-dag.cypher"
    text = src.read_text()
    g = nx.DiGraph()
    latent = []
    for v, lat in SEED_ID.findall(text):
        g.add_node(v)
        if lat == "true":
            latent.append(v)
    g.add_edges_from(SEED_EDGE.findall(text))
    assert (g.number_of_nodes(), g.number_of_edges()) == (25, 78), (g.number_of_nodes(), g.number_of_edges())
    d = DATASETS / "covid"
    write_edges(d / "edges.csv", g)
    write_json(d / "meta.json", {
        "source": "dev/seed-dag.cypher", "sha256": hashlib.sha256(text.encode()).hexdigest(),
        "n": g.number_of_nodes(), "m": g.number_of_edges(), "nodes": list(g.nodes), "latent": latent})


def cmd_synth(only_n):
    commit = git_commit(Path(synthetic.__file__))
    for model, n, deg, seed in synthetic.grid():
        if only_n is not None and n != only_n:
            continue
        g = synthetic.generate(n, deg, model, seed)
        d = DATASETS / "synthetic" / synthetic.name(model, n, deg, seed)
        write_edges(d / "edges.csv", g)
        write_json(d / "meta.json", {
            "source": "synthetic", "model": model, "n": n, "avg_degree": deg, "seed": seed,
            "m": g.number_of_edges(), "generator_commit": commit})


def cmd_workloads(only, balanced=False):
    for name, family, d in datasets(only):
        g = read_dataset(d)
        out = WORKLOADS / name
        if balanced:  # tiers (b)/(c) and RQ5 (WP5b-2); leaves the other workload files alone
            write_csv(out / "triples_balanced.csv", ["x", "y", "z", "separated"],
                      [(x, y, ";".join(z), str(s).lower()) for x, y, z, s in workloads.balanced_triples(g)])
            print(f"workloads {name}: triples_balanced.csv", flush=True)
            continue
        ps = workloads.pairs(g)
        ts = workloads.triples(g)
        write_csv(out / "pairs.csv", ["t", "y"], ps)
        write_csv(out / "triples.csv", ["x", "y", "z"], [(x, y, ";".join(z)) for x, y, z in ts])
        write_json(out / "meta.json", {
            "dataset": name, "seed": workloads.SEED, "pairs": len(ps),
            "triples_per_z_size": workloads.N_TRIPLES, "z_sizes": workloads.z_sizes(g.number_of_nodes()),
            "z_sizes_requested": list(workloads.Z_SIZES)})


def cmd_stats():
    rows = [stats.row(name, family, read_dataset(d)) for name, family, d in datasets()]
    write_csv(DATASETS / "datasets.csv", stats.HEADER, [[r[h] for h in stats.HEADER] for r in rows])


def workload_hash(w: Path) -> str:
    h = hashlib.sha256()
    for f in ("pairs.csv", "triples.csv"):
        h.update((w / f).read_bytes())
    return h.hexdigest()


def cmd_reference(only, use_dagitty, force=False):
    """Timeouts make answers machine-dependent, so an existing result is kept unless
    its workload changed (hash in meta.json) or --force is given."""
    version = reference.dagitty_version() if use_dagitty else None
    if use_dagitty and version is None:
        print("dagitty unavailable (R, rpy2 or the dagitty package missing): writing dagitty = null",
              file=sys.stderr)
    import pgmpy
    for name, family, d in datasets(only):
        meta = read_json(d / "meta.json")
        if family == "synthetic" and meta["n"] > REFERENCE_MAX_N:
            continue
        w = WORKLOADS / name
        wh = workload_hash(w)
        old = REFERENCE / name / "meta.json"
        if not force and old.exists() and read_json(old).get("workload_sha256") == wh:
            continue
        g = read_dataset(d)
        pairs = [(r["t"], r["y"]) for r in read_csv(w / "pairs.csv")]
        triples = [(r["x"], r["y"], [v for v in r["z"].split(";") if v]) for r in read_csv(w / "triples.csv")]
        latent = frozenset(meta.get("latent", []))
        with_minimal = version is not None and (family != "synthetic" or meta["n"] <= MINIMAL_MAX_N)
        dsep, adj, counters = reference.compute(g, pairs, triples, latent, use_dagitty=version is not None,
                                                with_minimal=with_minimal)
        out = REFERENCE / name
        dag = str((d / "edges.csv").relative_to(REPO))
        ext = ".json.gz" if family == "synthetic" else ".json"  # synthetic families are large
        head = {"dataset": name, "dag": dag}
        write_rows_json(out / f"dsep{ext}", head, "triples", dsep)
        write_rows_json(out / f"adjustment{ext}", head, "pairs", adj)
        write_json(out / "meta.json", {
            "dataset": name,
            "workload": str(w.relative_to(REPO)),
            "workload_sha256": wh,
            "latent": sorted(latent),
            "pgmpy": pgmpy.__version__,
            "dagitty": version or "unavailable",
            "dsep": "pgmpy DAG.is_dconnected (negated)" + (", dagitty dseparated; verdicts agree" if version else ""),
            "canonical": "definition (Perkovic et al. 2018), validated with pgmpy in the proper backdoor graph"
                         + ("; equal to dagitty adjustmentSets(type='canonical')" if version else ""),
            "minimal": (f"dagitty adjustmentSets(type='minimal', effect='total'); {reference.PER_PAIR_SECONDS} s "
                        f"per pair (timeout: minimal = null, minimal_count = null); families over "
                        f"{reference.MAX_FAMILY} sets stored by size only (minimal = null, minimal_count = size)")
                       if with_minimal else None,
            "minimal_skipped": None if with_minimal else
                               ("dagitty unavailable" if version is None else f"synthetic n > {MINIMAL_MAX_N}"),
            "minimal_timeouts": counters["minimal_timeouts"] if with_minimal else None,
            "minimal_too_many": counters["minimal_too_many"] if with_minimal else None,
        })
        print(f"reference {name}: {len(dsep)} triples, {len(adj)} pairs, "
              f"{counters['minimal_timeouts']} timeouts, {counters['minimal_too_many']} families > "
              f"{reference.MAX_FAMILY}", flush=True)


def main(argv=None):
    p = argparse.ArgumentParser(prog="causal4j-bench", description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    for c in ("fetch", "workloads", "reference"):
        s = sub.add_parser(c)
        s.add_argument("--only", help="comma-separated dataset names")
        if c == "workloads":
            s.add_argument("--balanced", action="store_true",
                           help="write only triples_balanced.csv (25 separated + 25 connected per |z|)")
        if c == "reference":
            s.add_argument("--no-dagitty", action="store_true")
            s.add_argument("--force", action="store_true", help="recompute even if the workload is unchanged")
    sub.add_parser("covid")
    s = sub.add_parser("synth")
    s.add_argument("--only", help="n=<N> to generate one size of the grid")
    sub.add_parser("stats")
    sub.add_parser("all")
    a = p.parse_args(argv)
    only = set(a.only.split(",")) if getattr(a, "only", None) and a.cmd != "synth" else None

    if a.cmd == "fetch":
        cmd_fetch(only)
    elif a.cmd == "covid":
        cmd_covid()
    elif a.cmd == "synth":
        cmd_synth(int(a.only.split("=")[1]) if a.only else None)
    elif a.cmd == "workloads":
        cmd_workloads(only, a.balanced)
    elif a.cmd == "stats":
        cmd_stats()
    elif a.cmd == "reference":
        cmd_reference(only, not a.no_dagitty, a.force)
    elif a.cmd == "all":
        cmd_fetch(None)
        cmd_covid()
        cmd_synth(None)
        cmd_workloads(None)
        cmd_stats()
        cmd_reference(None, True)


if __name__ == "__main__":
    main()
