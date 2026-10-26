"""Resolution policies + always-available refuters."""

import numpy as np
import pandas as pd
import pytest

from ydbms_pipeline.config import KnowledgeSection
from ydbms_pipeline.model import CausalModel
from ydbms_pipeline.stages.refute import run_refutation
from ydbms_pipeline.stages.resolve import resolve_model


def cpdag_model() -> CausalModel:
    m = CausalModel(name="M")
    m.add_edge("A", "B")
    m.ensure_variable("C")
    m.undirected.add(frozenset(("B", "C")))
    return m


def test_fail_policy_raises():
    with pytest.raises(ValueError, match="unoriented"):
        resolve_model(cpdag_model(), "fail")


def test_prior_policy_orients_from_required():
    m = cpdag_model()
    k = KnowledgeSection(required=[("B", "C")])
    report = resolve_model(m, "prior", k)
    assert not m.undirected
    assert ("B", "C") in {(e.source, e.target) for e in m.edges}
    assert any("required" in d["reason"] for d in report["decisions"])


def test_tiers_orient():
    m = cpdag_model()
    k = KnowledgeSection(tiers=[["C"], ["B"]])  # C before B => C -> B
    resolve_model(m, "prior", k)
    assert ("C", "B") in {(e.source, e.target) for e in m.edges}


def test_heuristic_records_guesses():
    m = cpdag_model()
    report = resolve_model(m, "heuristic", column_order=["A", "B", "C"])
    assert not m.undirected
    assert len(report["guesses"]) == 1
    assert "GUESS" in report["guesses"][0]["reason"]


def test_heuristic_avoids_cycle():
    m = CausalModel(name="M")
    m.add_edge("A", "B")
    m.add_edge("B", "C")
    m.undirected.add(frozenset(("A", "C")))
    # Column order suggests C -> A, which would close a cycle; must flip.
    resolve_model(m, "heuristic", column_order=["C", "B", "A"])
    assert ("A", "C") in {(e.source, e.target) for e in m.edges}


# ── refuters ─────────────────────────────────────────────────────────────────

def chain_data(n=20_000, seed=3) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    a = rng.normal(0, 1, n)
    b = 0.9 * a + rng.normal(0, 0.5, n)
    c = 0.7 * b + rng.normal(0, 0.5, n)
    return pd.DataFrame({"A": a, "B": b, "C": c})


def chain_model() -> CausalModel:
    m = CausalModel(name="Chain")
    m.add_edge("A", "B")
    m.add_edge("B", "C")
    return m


def test_ci_passes_on_true_dag():
    report = run_refutation(chain_model(), chain_data(), ["ci"], seed=3)
    assert report["passed"], report


def test_ci_fails_on_wrong_dag():
    # Claim A and C are both roots (A _||_ C marginally) — false for a chain.
    m = CausalModel(name="Wrong")
    m.add_edge("A", "B")
    m.add_edge("C", "B")
    report = run_refutation(m, chain_data(), ["ci"], on_failure="warn", seed=3)
    assert not report["passed"]


def test_block_raises():
    m = CausalModel(name="Wrong")
    m.add_edge("A", "B")
    m.add_edge("C", "B")
    with pytest.raises(ValueError, match="on_failure=block"):
        run_refutation(m, chain_data(), ["ci"], on_failure="block", seed=3)


def test_gof():
    report = run_refutation(chain_model(), chain_data(), ["gof"], seed=3)
    assert report["tests"]["gof"]["per_equation"]["B"]["r2"] > 0.5
