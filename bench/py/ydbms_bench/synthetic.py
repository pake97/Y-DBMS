"""ER / BA random DAGs, oriented by a random topological rank."""
import networkx as nx
import numpy as np

NS = (100, 316, 1000, 3162, 10000)
DEGREES = (2, 4, 8)
MODELS = ("er", "ba")
SEEDS = (0, 1, 2, 3, 4)


def name(model: str, n: int, d: int, seed: int) -> str:
    return f"{model}-n{n}-d{d}-s{seed}"


def grid():
    for model in MODELS:
        for n in NS:
            for d in DEGREES:
                for s in SEEDS:
                    yield model, n, d, s


def generate(n: int, avg_degree: float, model: str, seed: int) -> nx.DiGraph:
    """Node ids "v0".."v{n-1}" by rank, so every edge vi -> vj has i < j."""
    rng = np.random.default_rng(seed)
    order = rng.permutation(n)  # order[k] = topological rank of generator node k
    if model == "er":
        m = round(avg_degree * n / 2)
        if m > n * (n - 1) // 2:
            raise ValueError("too many edges for n")
        pairs = set()
        while len(pairs) < m:
            a, b = rng.integers(0, n, size=2)
            if a != b:
                pairs.add((min(order[a], order[b]), max(order[a], order[b])))
    elif model == "ba":
        k = round(avg_degree / 2)
        ba = nx.barabasi_albert_graph(n, k, seed=int(rng.integers(2**31)))
        pairs = {(min(order[a], order[b]), max(order[a], order[b])) for a, b in ba.edges()}
    else:
        raise ValueError(f"unknown model {model}")

    g = nx.DiGraph()
    g.add_nodes_from(f"v{i}" for i in range(n))
    g.add_edges_from((f"v{i}", f"v{j}") for i, j in sorted(pairs))
    return g
