"""Internal causal-model representation shared by every stage.

A :class:`CausalModel` is a DAG (networkx) plus per-variable metadata and
optional linear-Gaussian SEM parameters. It is the single in-memory format:
readers produce it, discovery/resolution/fitting transform it, the emitter
serializes it.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Dict, FrozenSet, List, Optional, Set, Tuple

import networkx as nx

# DSL grammar constants (must mirror dsl/src/main/antlr4/.../CausalDSL.g4)
VARIABLE_TYPES = {"binary", "continuous", "categorical", "event", "latent"}
VARIABLE_ROLES = {
    "treatment",
    "outcome",
    "covariate",
    "mediator",
    "collider",
    "confounder",
    "unknown",
}

# Lowercase tokens that would collide with DSL keywords if used as IDs.
_LOWERCASE_KEYWORDS = VARIABLE_TYPES | VARIABLE_ROLES | {"true", "false"}
_ID_RE = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")


def sanitize_id(name: str, taken: Optional[Set[str]] = None) -> str:
    """Coerce an arbitrary column/variable name into a valid DSL ID.

    Replaces illegal characters with ``_``, prefixes a leading digit, and
    dodges DSL keyword collisions. If ``taken`` is given, guarantees the
    result is unique w.r.t. that set (and adds it).
    """
    out = re.sub(r"[^a-zA-Z0-9_]", "_", str(name).strip())
    if not out:
        out = "_v"
    if out[0].isdigit():
        out = "_" + out
    if out in _LOWERCASE_KEYWORDS or out.upper() == out and not _ID_RE.match(out):
        out = out + "_"
    if not _ID_RE.match(out):
        out = "_" + out
    if taken is not None:
        base, i = out, 2
        while out in taken:
            out = f"{base}_{i}"
            i += 1
        taken.add(out)
    return out


@dataclass
class VariableSpec:
    """Per-variable metadata mapping 1:1 to the DSL's variableDecl."""

    name: str
    unit: Optional[str] = None
    observed_as: Optional[str] = None
    var_type: Optional[str] = None  # binary|continuous|categorical|event|latent
    role: Optional[str] = None  # treatment|outcome|covariate|mediator|collider|confounder|unknown
    mean: Optional[float] = None  # intercept mu_0
    noise: Optional[float] = None  # noise variance sigma^2

    def __post_init__(self):
        if self.var_type is not None and self.var_type not in VARIABLE_TYPES:
            raise ValueError(f"Invalid TYPE '{self.var_type}' for {self.name}: expected one of {sorted(VARIABLE_TYPES)}")
        if self.role is not None and self.role not in VARIABLE_ROLES:
            raise ValueError(f"Invalid ROLE '{self.role}' for {self.name}: expected one of {sorted(VARIABLE_ROLES)}")

    @property
    def latent(self) -> bool:
        return self.var_type == "latent"


@dataclass
class EdgeSpec:
    """A directed causal edge, optionally carrying the linear coefficient."""

    source: str
    target: str
    weight: Optional[float] = None  # beta


@dataclass
class CausalModel:
    """DAG + variable metadata + (optional) SEM parameters."""

    name: str = "Model"
    variables: Dict[str, VariableSpec] = field(default_factory=dict)
    edges: List[EdgeSpec] = field(default_factory=list)
    # Edges discovery could not orient (CPDAG remnants). Must be empty before emit.
    undirected: Set[FrozenSet[str]] = field(default_factory=set)

    # ── construction helpers ────────────────────────────────────────────────

    def add_variable(self, spec: VariableSpec) -> None:
        self.variables[spec.name] = spec

    def ensure_variable(self, name: str) -> VariableSpec:
        if name not in self.variables:
            self.variables[name] = VariableSpec(name=name)
        return self.variables[name]

    def add_edge(self, source: str, target: str, weight: Optional[float] = None) -> None:
        self.ensure_variable(source)
        self.ensure_variable(target)
        for e in self.edges:
            if e.source == source and e.target == target:
                if weight is not None:
                    e.weight = weight
                return
        self.edges.append(EdgeSpec(source, target, weight))

    # ── views ───────────────────────────────────────────────────────────────

    def graph(self) -> nx.DiGraph:
        g = nx.DiGraph()
        g.add_nodes_from(self.variables)
        for e in self.edges:
            g.add_edge(e.source, e.target, weight=e.weight)
        return g

    def parents(self, name: str) -> List[str]:
        return sorted(e.source for e in self.edges if e.target == name)

    def topo_order(self) -> List[str]:
        return list(nx.lexicographical_topological_sort(self.graph()))

    def edge_weight(self, source: str, target: str) -> Optional[float]:
        for e in self.edges:
            if e.source == source and e.target == target:
                return e.weight
        return None

    # ── validation ──────────────────────────────────────────────────────────

    def validate(
        self,
        required_edges: Optional[List[Tuple[str, str]]] = None,
        forbidden_edges: Optional[List[Tuple[str, str]]] = None,
    ) -> None:
        """Raise ValueError on any structural violation."""
        if self.undirected:
            pairs = ", ".join("{" + ",".join(sorted(p)) + "}" for p in sorted(self.undirected, key=sorted))
            raise ValueError(
                f"Model still has {len(self.undirected)} unoriented edge(s): {pairs}. "
                "Run the resolve stage (see resolve.undirected_policy) before emitting."
            )
        g = self.graph()
        if not nx.is_directed_acyclic_graph(g):
            cycle = nx.find_cycle(g)
            raise ValueError(f"Graph is not a DAG; cycle found: {cycle}")
        edge_set = {(e.source, e.target) for e in self.edges}
        for u, v in required_edges or []:
            if (u, v) not in edge_set:
                raise ValueError(f"Required edge missing: {u} -> {v}")
        for u, v in forbidden_edges or []:
            if (u, v) in edge_set:
                raise ValueError(f"Forbidden edge present: {u} -> {v}")
        for e in self.edges:
            for endpoint in (e.source, e.target):
                if endpoint not in self.variables:
                    raise ValueError(f"Edge endpoint '{endpoint}' is not a declared variable")
        if not self.edges:
            raise ValueError(
                "Model has no edges. The DSL's DEFINE MODEL/DAG blocks require at least one edge."
            )
