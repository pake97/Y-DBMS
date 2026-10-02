"""edges.csv / meta.json I/O shared by every step."""
import csv
import json
import subprocess
from pathlib import Path

import networkx as nx

from . import REPO


def write_edges(path: Path, g: nx.DiGraph) -> None:
    """Header `from,to`, one edge per line, in the graph's edge insertion order.

    Isolated nodes are not representable in an edge list; meta.json carries `n`.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["from", "to"])
        w.writerows(g.edges())


def read_dataset(d: Path) -> nx.DiGraph:
    """edges.csv plus the node list (keeps isolated nodes and node order).

    bnlearn/covid meta.json lists the nodes; synthetic ids are v0..v{n-1}.
    """
    meta = read_json(d / "meta.json")
    g = nx.DiGraph()
    g.add_nodes_from(meta.get("nodes") or (f"v{i}" for i in range(meta["n"])))
    with open(d / "edges.csv", newline="") as f:
        r = csv.reader(f)
        next(r)
        g.add_edges_from((a, b) for a, b in r)
    if g.number_of_nodes() != meta["n"] or g.number_of_edges() != meta["m"]:
        raise ValueError(f"{d}: edges.csv does not match meta.json")
    return g


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, sort_keys=False) + "\n")


def read_json(path: Path):
    return json.loads(path.read_text())


def write_csv(path: Path, header, rows) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def read_csv(path: Path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def git_commit(*paths: Path) -> str:
    """Last commit touching `paths`, with -dirty if they have uncommitted edits."""
    rel = [str(p.relative_to(REPO)) for p in paths]
    try:
        h = subprocess.run(["git", "log", "-1", "--format=%H", "--", *rel], cwd=REPO,
                           capture_output=True, text=True, check=True).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain", "--", *rel], cwd=REPO,
                               capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
    return (h or "uncommitted") + ("-dirty" if dirty else "")
