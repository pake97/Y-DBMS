"""Stage: causal structure discovery (Mode A).

Adapters over causal-learn, registered in the DISCOVERY registry:

* ``pc``     — constraint-based; returns a CPDAG (some edges unoriented).
* ``ges``    — score-based; returns a CPDAG.
* ``lingam`` — DirectLiNGAM; fully oriented DAG *with* edge coefficients
               (assumes linear non-Gaussian noise).

All adapters return a :class:`CausalModel` whose ``undirected`` set holds the
edges the algorithm could not orient — the resolve stage deals with those.
causal-learn is an optional dependency (``pip install ydbms-pipeline[discovery]``);
imports are lazy so the rest of the tool works without it.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

from ydbms_pipeline.config import KnowledgeSection
from ydbms_pipeline.model import CausalModel
from ydbms_pipeline.registry import DISCOVERY


class DiscoveryUnavailable(ImportError):
    pass


def _require_causallearn():
    try:
        import causallearn  # noqa: F401
    except ImportError as e:
        raise DiscoveryUnavailable(
            "Discovery requires causal-learn: pip install ydbms-pipeline[discovery]"
        ) from e


def discover(
    df: pd.DataFrame,
    algorithm: str,
    knowledge: Optional[KnowledgeSection] = None,
    seed: int = 0,
    **params,
) -> Tuple[CausalModel, Dict[str, Any]]:
    """Run the configured algorithm; returns (model, report)."""
    model, report = DISCOVERY.get(algorithm)(df, knowledge or KnowledgeSection(), seed, **params)
    _apply_knowledge_posthoc(model, knowledge or KnowledgeSection(), report)
    report["algorithm"] = algorithm
    report["params"] = params
    report["seed"] = seed
    report["n_directed"] = len(model.edges)
    report["n_undirected"] = len(model.undirected)
    return model, report


# ── shared conversion ────────────────────────────────────────────────────────

def _from_general_graph(graph_matrix: np.ndarray, names: List[str], model_name: str) -> CausalModel:
    """Convert causal-learn's endpoint matrix to a CausalModel.

    Semantics: ``M[i,j] == -1 and M[j,i] == 1``  =>  i -> j;
    ``M[i,j] == M[j,i] == -1``  =>  i - j (unoriented);
    ``M[i,j] == M[j,i] == 1``   =>  i <-> j (bidirected: latent confounder).
    """
    model = CausalModel(name=model_name)
    for n in names:
        model.ensure_variable(n)
    n = len(names)
    bidirected = []
    for i in range(n):
        for j in range(i + 1, n):
            a, b = graph_matrix[i, j], graph_matrix[j, i]
            if a == 0 and b == 0:
                continue
            if a == -1 and b == 1:
                model.add_edge(names[i], names[j])
            elif a == 1 and b == -1:
                model.add_edge(names[j], names[i])
            elif a == -1 and b == -1:
                model.undirected.add(frozenset((names[i], names[j])))
            elif a == 1 and b == 1:
                bidirected.append((names[i], names[j]))
    if bidirected:
        raise ValueError(
            f"Discovery returned bidirected edge(s) {bidirected} (latent confounding). "
            "The DSL models latents as explicit variables, not ADMG edges — declare the "
            "confounder as a latent variable and re-run, or use a DAG-returning algorithm."
        )
    return model


def _apply_knowledge_posthoc(model: CausalModel, knowledge: KnowledgeSection, report: Dict[str, Any]) -> None:
    """Enforce required/forbidden edges on the result regardless of algorithm support."""
    applied = []
    for u, v in knowledge.forbidden:
        before = len(model.edges)
        model.edges = [e for e in model.edges if not (e.source == u and e.target == v)]
        if len(model.edges) != before:
            applied.append(f"removed forbidden {u}->{v}")
        if frozenset((u, v)) in model.undirected:
            model.undirected.discard(frozenset((u, v)))
            model.add_edge(v, u)
            applied.append(f"oriented {v}->{u} (reverse was forbidden)")
    for u, v in knowledge.required:
        if frozenset((u, v)) in model.undirected:
            model.undirected.discard(frozenset((u, v)))
        if model.edge_weight(u, v) is None and (u, v) not in {(e.source, e.target) for e in model.edges}:
            model.add_edge(u, v)
            applied.append(f"added required {u}->{v}")
    if applied:
        report["knowledge_applied"] = applied


def _background_knowledge(knowledge: KnowledgeSection, names: List[str]):
    """Build causal-learn BackgroundKnowledge (best effort; post-hoc enforcement is the backstop)."""
    if not (knowledge.required or knowledge.forbidden or knowledge.tiers):
        return None
    try:
        from causallearn.graph.GraphNode import GraphNode
        from causallearn.utils.PCUtils.BackgroundKnowledge import BackgroundKnowledge
    except ImportError:
        return None
    node = {n: GraphNode(n) for n in names}
    bk = BackgroundKnowledge()
    for u, v in knowledge.required:
        if u in node and v in node:
            bk.add_required_by_node(node[u], node[v])
    for u, v in knowledge.forbidden:
        if u in node and v in node:
            bk.add_forbidden_by_node(node[u], node[v])
    for i, tier in enumerate(knowledge.tiers):
        for later_tier in knowledge.tiers[i + 1:]:
            for late in later_tier:
                for early in tier:
                    if late in node and early in node:
                        bk.add_forbidden_by_node(node[late], node[early])
    return bk


def _numeric_matrix(df: pd.DataFrame) -> Tuple[np.ndarray, List[str]]:
    num = df.select_dtypes("number")
    dropped = sorted(set(df.columns) - set(num.columns))
    if dropped:
        raise ValueError(
            f"Discovery needs numeric columns; encode or drop: {dropped}"
        )
    return num.to_numpy(dtype=float), list(num.columns)


# ── adapters ─────────────────────────────────────────────────────────────────

@DISCOVERY.register("pc")
def discover_pc(df: pd.DataFrame, knowledge: KnowledgeSection, seed: int, alpha: float = 0.05, indep_test: str = "fisherz", **kw):
    _require_causallearn()
    from causallearn.search.ConstraintBased.PC import pc

    X, names = _numeric_matrix(df)
    cg = pc(
        X,
        alpha=alpha,
        indep_test=indep_test,
        show_progress=False,
        node_names=names,
        background_knowledge=_background_knowledge(knowledge, names),
        **kw,
    )
    model = _from_general_graph(np.asarray(cg.G.graph), names, "Discovered")
    return model, {"library": "causal-learn", "alpha": alpha, "indep_test": indep_test}


@DISCOVERY.register("ges")
def discover_ges(df: pd.DataFrame, knowledge: KnowledgeSection, seed: int, score_func: str = "local_score_BIC", **kw):
    _require_causallearn()
    from causallearn.search.ScoreBased.GES import ges

    X, names = _numeric_matrix(df)
    record = ges(X, score_func=score_func, node_names=names, **kw)
    model = _from_general_graph(np.asarray(record["G"].graph), names, "Discovered")
    return model, {"library": "causal-learn", "score_func": score_func, "score": float(record.get("score", float("nan")))}


@DISCOVERY.register("lingam")
def discover_lingam(df: pd.DataFrame, knowledge: KnowledgeSection, seed: int, threshold: float = 1e-3, **kw):
    _require_causallearn()
    from causallearn.search.FCMBased import lingam

    X, names = _numeric_matrix(df)
    m = lingam.DirectLiNGAM(random_state=seed, **kw)
    m.fit(X)
    B = np.asarray(m.adjacency_matrix_)  # B[i, j] != 0  =>  j -> i, coefficient B[i, j]
    model = CausalModel(name="Discovered")
    for n in names:
        model.ensure_variable(n)
    for i in range(len(names)):
        for j in range(len(names)):
            if abs(B[i, j]) > threshold:
                model.add_edge(names[j], names[i], float(B[i, j]))
    return model, {"library": "causal-learn", "method": "DirectLiNGAM", "threshold": threshold,
                   "note": "edge weights are LiNGAM coefficients; the fit stage refits them under the configured hypothesis"}
