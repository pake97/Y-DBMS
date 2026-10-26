"""Stage: resolve an equivalence class (CPDAG remnants) into a single DAG.

Discovery algorithms usually return some edges unoriented. Policies:

* ``fail`` (default) — refuse to guess; report the unoriented pairs.
* ``prior`` — orient using background knowledge (required edges, tiers);
  anything knowledge can't settle is an error.
* ``heuristic`` — after priors, orient remaining pairs deterministically
  (data column order; earlier -> later) and RECORD every guess in the
  resolution report. Convenient, and honest about being a guess.

Every orientation decision is returned for the provenance sidecar — an
orientation is model input, never a silent side effect.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

import networkx as nx

from ydbms_pipeline.config import KnowledgeSection
from ydbms_pipeline.model import CausalModel
from ydbms_pipeline.registry import RESOLVERS


def resolve_model(
    model: CausalModel,
    policy: str = "fail",
    knowledge: Optional[KnowledgeSection] = None,
    column_order: Optional[List[str]] = None,
) -> Dict[str, Any]:
    report = RESOLVERS.get(policy)(model, knowledge or KnowledgeSection(), column_order or [])
    # Whatever the policy, the result must be a DAG.
    g = model.graph()
    if not nx.is_directed_acyclic_graph(g):
        cycle = nx.find_cycle(g)
        raise ValueError(f"Resolution produced a cyclic graph: {cycle}")
    return report


def _tier_of(name: str, knowledge: KnowledgeSection) -> Optional[int]:
    for i, tier in enumerate(knowledge.tiers):
        if name in tier:
            return i
    return None


def _orient_with_knowledge(model: CausalModel, knowledge: KnowledgeSection) -> List[Dict[str, str]]:
    """Orient undirected pairs that priors settle. Returns decisions made."""
    decisions = []
    required = set(map(tuple, knowledge.required))
    still = set()
    for pair in model.undirected:
        a, b = sorted(pair)
        if (a, b) in required:
            model.add_edge(a, b)
            decisions.append({"edge": f"{a}->{b}", "reason": "required by knowledge"})
        elif (b, a) in required:
            model.add_edge(b, a)
            decisions.append({"edge": f"{b}->{a}", "reason": "required by knowledge"})
        else:
            ta, tb = _tier_of(a, knowledge), _tier_of(b, knowledge)
            if ta is not None and tb is not None and ta != tb:
                u, v = (a, b) if ta < tb else (b, a)
                model.add_edge(u, v)
                decisions.append({"edge": f"{u}->{v}", "reason": f"temporal tiers ({ta} vs {tb})"})
            else:
                still.add(pair)
    model.undirected = still
    return decisions


@RESOLVERS.register("fail")
def resolve_fail(model: CausalModel, knowledge: KnowledgeSection, column_order: List[str]) -> Dict[str, Any]:
    decisions = _orient_with_knowledge(model, knowledge)
    if model.undirected:
        pairs = ["{" + ",".join(sorted(p)) + "}" for p in sorted(model.undirected, key=sorted)]
        raise ValueError(
            f"{len(pairs)} edge(s) remain unoriented after applying knowledge: {', '.join(pairs)}. "
            "Add discovery.knowledge constraints, or set resolve.undirected_policy to 'heuristic' "
            "to accept recorded guesses."
        )
    return {"policy": "fail", "decisions": decisions}


@RESOLVERS.register("prior")
def resolve_prior(model: CausalModel, knowledge: KnowledgeSection, column_order: List[str]) -> Dict[str, Any]:
    return resolve_fail(model, knowledge, column_order) | {"policy": "prior"}


@RESOLVERS.register("heuristic")
def resolve_heuristic(model: CausalModel, knowledge: KnowledgeSection, column_order: List[str]) -> Dict[str, Any]:
    decisions = _orient_with_knowledge(model, knowledge)
    order = {name: i for i, name in enumerate(column_order)}
    guesses = []
    for pair in sorted(model.undirected, key=sorted):
        a, b = sorted(pair, key=lambda x: (order.get(x, 1 << 30), x))
        # Guess a -> b; if that closes a cycle, flip it.
        model.add_edge(a, b)
        if not nx.is_directed_acyclic_graph(model.graph()):
            model.edges = [e for e in model.edges if not (e.source == a and e.target == b)]
            model.add_edge(b, a)
            a, b = b, a
        guesses.append({"edge": f"{a}->{b}", "reason": "heuristic (column order) — GUESS, verify"})
    model.undirected = set()
    return {"policy": "heuristic", "decisions": decisions, "guesses": guesses}
