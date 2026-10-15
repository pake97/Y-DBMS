"""Structure-only .bif parser: variable names and parent lists, tables ignored."""
import gzip
import re
from pathlib import Path

import networkx as nx

VARIABLE = re.compile(r"^\s*variable\s+([^\s{]+)", re.M)
# probability ( child | p1, p2 ) or probability ( root )
PROBABILITY = re.compile(r"^\s*probability\s*\(\s*([^|)\s]+)\s*(?:\|\s*([^)]*))?\)", re.M)


def parse(text: str) -> nx.DiGraph:
    g = nx.DiGraph()
    g.add_nodes_from(VARIABLE.findall(text))
    for child, parents in PROBABILITY.findall(text):
        if child not in g:
            raise ValueError(f"probability block for undeclared variable {child}")
        for p in filter(None, (s.strip() for s in parents.split(","))):
            if p not in g:
                raise ValueError(f"undeclared parent {p} of {child}")
            g.add_edge(p, child)
    if not nx.is_directed_acyclic_graph(g):
        raise ValueError("parsed network is not a DAG")
    return g


def parse_file(path: Path) -> nx.DiGraph:
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt") as f:
        return parse(f.read())
