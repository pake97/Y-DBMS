"""Query workloads: (t, y) pairs with y ∈ de(t), and (x, y, z) d-separation triples."""
import networkx as nx
import numpy as np

SEED = 20261111
N_PAIRS = 200
N_TRIPLES = 50
Z_SIZES = (0, 1, 3, 10)


def pairs(g: nx.DiGraph, seed: int = SEED, k: int = N_PAIRS):
    """k distinct pairs: t uniform among nodes with a descendant, then y uniform in de(t).

    If fewer than k distinct pairs exist, all of them (in node order).
    """
    nodes = list(g.nodes)
    pos = {v: i for i, v in enumerate(nodes)}
    sources = [v for v in nodes if g.out_degree(v) > 0]
    # m edges are m distinct ancestor pairs, so only a graph with m < k can run short.
    if g.number_of_edges() < k:
        every = [(t, y) for t in sources for y in sorted(nx.descendants(g, t), key=pos.get)]
        if len(every) <= k:
            return every
    rng = np.random.default_rng(seed)
    de = {}
    out, seen = [], set()
    while len(out) < k:
        t = sources[rng.integers(len(sources))]
        if t not in de:
            de[t] = sorted(nx.descendants(g, t), key=pos.get)
        y = de[t][rng.integers(len(de[t]))]
        if (t, y) not in seen:
            seen.add((t, y))
            out.append((t, y))
    return out


def z_sizes(n: int):
    """Z_SIZES, each capped at n - 2 (z must avoid x and y)."""
    return [min(s, n - 2) for s in Z_SIZES]


def triples(g: nx.DiGraph, seed: int = SEED, k: int = N_TRIPLES):
    """k triples per |z| in z_sizes(n): x ≠ y uniform, z uniform among the rest."""
    nodes = list(g.nodes)
    n = len(nodes)
    rng = np.random.default_rng(seed)
    out = []
    for size in z_sizes(n):
        for _ in range(k):
            x, y = rng.choice(n, size=2, replace=False)
            rest = [i for i in range(n) if i != x and i != y]
            z = sorted(rng.choice(rest, size=size, replace=False)) if size else []
            out.append((nodes[x], nodes[y], [nodes[i] for i in z]))
    return out
