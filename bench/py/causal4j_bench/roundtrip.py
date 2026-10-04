"""External round-trip baseline (paper baseline d): export the DAG from Neo4j over Bolt,
build each library's graph object, run the workload, and time the three phases.

usage: python -m causal4j_bench.roundtrip <dataset> <out.csv> [--libs pgmpy,dagitty,causallearn,dowhy]
Reads BOLT_URI (set by the Java ServeBolt driver). Appends one CSV row per
(library, operator): export_ms, build_ms, queries, query_ms_median, query_ms_p99, censored.
A (library, operator) stops after BUDGET_S seconds of queries, or as soon as one query
exceeds BUDGET_S (SIGALRM; DoWhy's identification can run for hours on andes), and is
marked censored. A query inside R (dagitty) is only interrupted when it returns to Python.
"""
import argparse
import csv
import os
import signal
import statistics
import time
from pathlib import Path

import networkx as nx

from . import WORKLOADS
from .io import read_csv

BUDGET_S = 60.0
EXPORT = ("MATCH (a:Variable)-[r:CAUSES]->(b:Variable) WHERE coalesce(r.active, true) "
          "RETURN a.id AS a, b.id AS b")
NODES = "MATCH (v:Variable) RETURN v.id AS id, coalesce(v.latent, false) AS latent ORDER BY id"
class QueryTimeout(Exception):
    pass


def _alarm(signum, frame):
    raise QueryTimeout()


HEADER = ["library", "dataset", "operator", "export_ms", "build_ms", "queries",
          "query_ms_median", "query_ms_p99", "censored"]


def export(uri):
    from neo4j import GraphDatabase
    t0 = time.perf_counter()
    with GraphDatabase.driver(uri, auth=None) as driver:
        nodes = driver.execute_query(NODES).records
        edges = driver.execute_query(EXPORT).records
    ms = (time.perf_counter() - t0) * 1e3
    return [r["id"] for r in nodes], [(r["a"], r["b"]) for r in edges], \
        {r["id"] for r in nodes if r["latent"]}, ms


# ── per-library adapters: build(nodes, edges, latent) -> object; ops take (obj, item) ──

def _pgmpy_build(nodes, edges, latent):
    from pgmpy.base import DAG
    g = DAG(edges)
    g.add_nodes_from(nodes)
    return g


def _pgmpy_ops():
    def adjust(g, p):
        from pgmpy.inference import CausalInference
        return CausalInference(g).get_minimal_adjustment_set(p[0], p[1])
    return {
        "ancestors": lambda g, p: nx.ancestors(g, p[1]),
        "reaches": lambda g, p: nx.has_path(g, p[0], p[1]),
        "dsep": lambda g, q: not g.is_dconnected(q[0], q[1], observed=q[2]),
        "adjustment": adjust,
    }


def _dagitty_build(nodes, edges, latent):
    from .reference import Dagitty
    g = nx.DiGraph()
    g.add_nodes_from(nodes)
    g.add_edges_from(edges)
    d = Dagitty(g, latent)
    ro = d.ro
    d.anc = ro.r("function(g, v) dagitty::ancestors(g, v)")
    d.desc = ro.r("function(g, v) dagitty::descendants(g, v)")
    return d


def _dagitty_ops():
    return {
        "ancestors": lambda d, p: list(d.anc(d.g, d.alias[p[1]])),
        "reaches": lambda d, p: d.alias[p[1]] in set(d.desc(d.g, d.alias[p[0]])),
        "dsep": lambda d, q: d.separated(q[0], q[1], q[2]),
        "adjustment": lambda d, p: d.sets(p[0], p[1], "canonical"),
    }


def _causallearn_build(nodes, edges, latent):
    from causallearn.graph.Dag import Dag
    from causallearn.graph.GraphNode import GraphNode
    gn = {v: GraphNode(v) for v in nodes}
    g = Dag(list(gn.values()))
    for a, b in edges:
        g.add_directed_edge(gn[a], gn[b])
    g.gn = gn
    return g


def _causallearn_ops():
    return {
        "ancestors": lambda g, p: g.get_ancestors([g.gn[p[1]]]),
        "reaches": lambda g, p: g.is_ancestor_of(g.gn[p[0]], g.gn[p[1]]),
    }


def _dowhy_build(nodes, edges, latent):
    import pandas as pd
    g = nx.DiGraph()
    g.add_nodes_from(nodes)
    g.add_edges_from(edges)
    g.frame = pd.DataFrame(columns=nodes)
    return g


def _dowhy_ops():
    def adjust(g, p):
        import logging
        from dowhy import CausalModel
        logging.getLogger("dowhy").setLevel(logging.ERROR)
        m = CausalModel(data=g.frame, treatment=p[0], outcome=p[1], graph=g)
        return m.identify_effect(proceed_when_unidentifiable=True)
    return {"adjustment": adjust}


LIBS = {
    "pgmpy": (_pgmpy_build, _pgmpy_ops),
    "dagitty": (_dagitty_build, _dagitty_ops),
    "causallearn": (_causallearn_build, _causallearn_ops),
    "dowhy": (_dowhy_build, _dowhy_ops),
}


def workload(dataset):
    w = WORKLOADS / dataset
    pairs = [(r["t"], r["y"]) for r in read_csv(w / "pairs.csv")]
    triples = [(r["x"], r["y"], [v for v in r["z"].split(";") if v]) for r in read_csv(w / "triples.csv")]
    return pairs, triples


def main(argv=None):
    a = argparse.ArgumentParser()
    a.add_argument("dataset")
    a.add_argument("out")
    a.add_argument("--libs", default=",".join(LIBS))
    args = a.parse_args(argv)
    uri = os.environ["BOLT_URI"]
    pairs, triples = workload(args.dataset)
    out = Path(args.out)
    new = not out.exists()
    with open(out, "a", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        if new:
            w.writerow(HEADER)
        for lib in args.libs.split(","):
            build, ops = LIBS[lib]
            nodes, edges, latent, export_ms = export(uri)  # every library pays its own export
            t0 = time.perf_counter()
            obj = build(nodes, edges, latent)
            build_ms = (time.perf_counter() - t0) * 1e3
            for op, fn in ops().items():
                items = triples if op == "dsep" else pairs
                times, spent, censored = [], 0.0, False
                for item in items:
                    s = time.perf_counter()
                    signal.signal(signal.SIGALRM, _alarm)
                    signal.setitimer(signal.ITIMER_REAL, BUDGET_S)
                    try:
                        fn(obj, item)
                    except QueryTimeout:
                        censored = True
                        times.append(BUDGET_S * 1e3)
                        break
                    finally:
                        signal.setitimer(signal.ITIMER_REAL, 0)
                    dt = time.perf_counter() - s
                    times.append(dt * 1e3)
                    spent += dt
                    if spent > BUDGET_S:
                        censored = len(times) < len(items)
                        break
                times.sort()
                p99 = times[min(len(times) - 1, int(0.99 * len(times)))]
                w.writerow([lib, args.dataset, op, f"{export_ms:.3f}", f"{build_ms:.3f}", len(times),
                            f"{statistics.median(times):.4f}", f"{p99:.4f}", censored])
                f.flush()
                print(f"roundtrip {lib:<11} {args.dataset:<14} {op:<10} {len(times):>4} queries, "
                      f"median {statistics.median(times):.3f} ms{' (censored)' if censored else ''}", flush=True)


if __name__ == "__main__":
    main()
