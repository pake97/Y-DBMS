"""DAG readers: edge-list CSV, GML, DOT (minimal), and ``.causal`` round-trip.

All readers produce a :class:`~ydbms_pipeline.model.CausalModel`. Registered
in :data:`ydbms_pipeline.registry.READERS` by format name.
"""

from __future__ import annotations

import csv
import re
from pathlib import Path

import networkx as nx

from ydbms_pipeline.model import CausalModel, VariableSpec
from ydbms_pipeline.registry import READERS

_EXT_TO_FORMAT = {
    ".csv": "edgelist",
    ".tsv": "edgelist",
    ".gml": "gml",
    ".dot": "dot",
    ".gv": "dot",
    ".causal": "causal",
}


def read_dag(path: str | Path, fmt: str | None = None) -> CausalModel:
    """Read a DAG file, inferring the format from the extension when not given."""
    p = Path(path)
    if fmt is None:
        fmt = _EXT_TO_FORMAT.get(p.suffix.lower())
        if fmt is None:
            raise ValueError(
                f"Cannot infer DAG format from extension '{p.suffix}'. "
                f"Pass dag.format explicitly (one of: {', '.join(READERS.names())})."
            )
    return READERS.get(fmt)(p)


@READERS.register("edgelist")
def read_edgelist(path: Path) -> CausalModel:
    """CSV/TSV with a header containing 'source' and 'target' columns.

    Compatible with dev/causal_edges*.csv. An optional 'weight' column carries beta.
    """
    model = CausalModel(name=path.stem)
    delimiter = "\t" if path.suffix.lower() == ".tsv" else ","
    with open(path, newline="") as f:
        reader = csv.DictReader(f, delimiter=delimiter)
        if reader.fieldnames is None:
            raise ValueError(f"{path}: empty file")
        cols = {c.lower().strip(): c for c in reader.fieldnames}
        if "source" not in cols or "target" not in cols:
            raise ValueError(f"{path}: need 'source' and 'target' columns, found {reader.fieldnames}")
        for row in reader:
            src, tgt = row[cols["source"]].strip(), row[cols["target"]].strip()
            if not src or not tgt:
                continue
            weight = None
            if "weight" in cols and row.get(cols["weight"], "").strip():
                weight = float(row[cols["weight"]])
            model.add_edge(src, tgt, weight)
    return model


@READERS.register("gml")
def read_gml(path: Path) -> CausalModel:
    g = nx.read_gml(path)
    if not g.is_directed():
        raise ValueError(f"{path}: GML graph is undirected; a causal DAG must be directed")
    model = CausalModel(name=path.stem)
    for node in g.nodes:
        model.ensure_variable(str(node))
    for u, v, data in g.edges(data=True):
        w = data.get("weight")
        model.add_edge(str(u), str(v), float(w) if w is not None else None)
    return model


_DOT_EDGE_RE = re.compile(r'^\s*"?([\w.\- ]+?)"?\s*->\s*"?([\w.\- ]+?)"?\s*(\[[^\]]*\])?\s*;?\s*$')
_DOT_WEIGHT_RE = re.compile(r'weight\s*=\s*"?(-?[\d.eE+]+)"?')


@READERS.register("dot")
def read_dot(path: Path) -> CausalModel:
    """Minimal DOT reader: handles ``a -> b [weight=0.5];`` lines.

    Deliberately dependency-free (no pydot/graphviz). Statements it does not
    recognize (subgraphs, node styling) are ignored.
    """
    model = CausalModel(name=path.stem)
    text = re.sub(r"/\*.*?\*/", "", path.read_text(), flags=re.S)
    for line in text.splitlines():
        line = line.split("//")[0]
        m = _DOT_EDGE_RE.match(line)
        if not m:
            continue
        src, tgt, attrs = m.group(1).strip(), m.group(2).strip(), m.group(3) or ""
        wm = _DOT_WEIGHT_RE.search(attrs)
        model.add_edge(src, tgt, float(wm.group(1)) if wm else None)
    if not model.edges:
        raise ValueError(f"{path}: no 'a -> b' edges found")
    return model


# ── .causal round-trip reader ────────────────────────────────────────────────
# Parses the subset of the DSL that describes models: DEFINE CAUSAL VARIABLE,
# DEFINE MODEL { VARIABLE... DAG {...} }, DEFINE CAUSAL DAG {...}. Query
# statements are ignored. This is a structural reader for round-trip checks,
# not a replacement for the ANTLR parser.

_VAR_OPTS_RE = re.compile(
    r"(?:FOR\s+UNIT\s+(?P<unit>\w+))?\s*"
    r'(?:OBSERVED\s+AS\s+"(?P<obs>(?:[^"\\]|\\")*)")?\s*'
    r"(?:TYPE\s+(?P<type>binary|continuous|categorical|event|latent))?\s*"
    r"(?:ROLE\s+(?P<role>treatment|outcome|covariate|mediator|collider|confounder|unknown))?\s*"
    r"(?:MEAN\s+(?P<mean>-?\d+(?:\.\d+)?))?\s*"
    r"(?:NOISE\s+(?P<noise>-?\d+(?:\.\d+)?))?\s*$"
)
_EDGE_RE = re.compile(r"^(\w+)\s*->\s*(\w+)(?:\s+WEIGHT\s+(-?\d+(?:\.\d+)?))?$")


def _strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return "\n".join(line.split("//")[0] for line in text.splitlines())


def _parse_variable(model: CausalModel, name: str, rest: str) -> None:
    spec = VariableSpec(name=name)
    m = _VAR_OPTS_RE.match(rest.strip())
    if m:
        spec.unit = m.group("unit")
        spec.observed_as = m.group("obs").replace('\\"', '"') if m.group("obs") else None
        spec.var_type = m.group("type")
        spec.role = m.group("role")
        spec.mean = float(m.group("mean")) if m.group("mean") else None
        spec.noise = float(m.group("noise")) if m.group("noise") else None
    model.add_variable(spec)


@READERS.register("causal")
def read_causal(path: Path) -> CausalModel:
    text = _strip_comments(Path(path).read_text())
    model = CausalModel(name=Path(path).stem)

    m = re.search(r"DEFINE\s+MODEL\s+(\w+)\s*\{", text)
    if m is None:
        m = re.search(r"DEFINE\s+CAUSAL\s+DAG\s+(\w+)\s*\{", text)
    if m:
        model.name = m.group(1)

    # Standalone + in-block variable declarations.
    for vm in re.finditer(
        r"(?:DEFINE\s+CAUSAL\s+)?VARIABLE\s+(\w+)((?:(?!\n\s*(?:VARIABLE|DAG|DEFINE|\}))[^\n])*)",
        text,
    ):
        _parse_variable(model, vm.group(1), vm.group(2))

    # Edges: every 'a -> b (WEIGHT n)?' anywhere in a DAG block body.
    for line in text.splitlines():
        em = _EDGE_RE.match(line.strip())
        if em:
            w = float(em.group(3)) if em.group(3) else None
            model.add_edge(em.group(1), em.group(2), w)

    if not model.edges and not model.variables:
        raise ValueError(f"{path}: no model definition found")
    return model
