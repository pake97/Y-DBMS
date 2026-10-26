"""Stage: refute & diagnose (optional gate before emission).

Always available (numpy only):
* ``ci`` — tests conditional independencies implied by the DAG (each node vs a
  non-descendant non-parent, given its parents) via partial correlation with a
  Fisher-z test. A DAG whose implied independencies the data rejects is suspect.
* ``gof`` — for the linear-Gaussian hypothesis: per-equation R^2 plus residual
  skew/kurtosis as a cheap normality signal.

Optional (require dowhy): ``placebo``, ``random_common_cause``, ``subset`` —
dowhy refuters run on a (treatment, outcome) pair when roles identify one.

Config decides whether failures block emission or just warn.
"""

from __future__ import annotations

import math
from typing import Any, Dict, List, Optional, Tuple

import networkx as nx
import numpy as np
import pandas as pd

from ydbms_pipeline.model import CausalModel
from ydbms_pipeline.registry import REFUTERS


def run_refutation(
    model: CausalModel,
    df: pd.DataFrame,
    tests: List[str],
    on_failure: str = "warn",
    seed: int = 0,
) -> Dict[str, Any]:
    report: Dict[str, Any] = {"tests": {}, "passed": True}
    for t in tests:
        outcome = REFUTERS.get(t)(model, df, seed=seed)
        report["tests"][t] = outcome
        if not outcome.get("passed", True):
            report["passed"] = False
    if not report["passed"] and on_failure == "block":
        failed = [t for t, o in report["tests"].items() if not o.get("passed", True)]
        raise ValueError(f"Refutation failed ({', '.join(failed)}) and refute.on_failure=block")
    return report


# ── helpers ──────────────────────────────────────────────────────────────────

def _partial_corr(df: pd.DataFrame, x: str, y: str, z: List[str]) -> float:
    """Partial correlation of x,y given z via residualization."""
    xv = df[x].to_numpy(dtype=float)
    yv = df[y].to_numpy(dtype=float)
    if z:
        Z = np.column_stack([np.ones(len(df))] + [df[c].to_numpy(dtype=float) for c in z])
        xv = xv - Z @ np.linalg.lstsq(Z, xv, rcond=None)[0]
        yv = yv - Z @ np.linalg.lstsq(Z, yv, rcond=None)[0]
    sx, sy = xv.std(), yv.std()
    if sx == 0 or sy == 0:
        return 0.0
    return float(np.corrcoef(xv, yv)[0, 1])


def _fisher_z_pvalue(r: float, n: int, k: int) -> float:
    """Two-sided p-value for H0: partial correlation = 0."""
    r = max(min(r, 0.999999), -0.999999)
    dof = n - k - 3
    if dof <= 0:
        return 1.0
    z = 0.5 * math.log((1 + r) / (1 - r)) * math.sqrt(dof)
    # 2 * (1 - Phi(|z|)) via erfc
    return math.erfc(abs(z) / math.sqrt(2))


def _observed(model: CausalModel, df: pd.DataFrame, name: str) -> bool:
    return not model.variables[name].latent and name in df.columns


@REFUTERS.register("ci")
def refute_ci(model: CausalModel, df: pd.DataFrame, seed: int = 0, alpha: float = 0.01, max_tests: int = 200) -> Dict[str, Any]:
    """Test implied independencies: X _||_ Y | parents(X) for non-adjacent,
    non-descendant pairs (local Markov property)."""
    g = model.graph()
    rng = np.random.default_rng(seed)
    candidates: List[Tuple[str, str, List[str]]] = []
    for x in g.nodes:
        if not _observed(model, df, x):
            continue
        parents = [p for p in g.predecessors(x) if _observed(model, df, p)]
        if len(parents) != len(list(g.predecessors(x))):
            continue  # latent parent -> local Markov need not hold among observed
        nondesc = set(g.nodes) - nx.descendants(g, x) - {x} - set(g.predecessors(x))
        for y in sorted(nondesc):
            if _observed(model, df, y):
                candidates.append((x, y, parents))
    if len(candidates) > max_tests:
        idx = rng.choice(len(candidates), size=max_tests, replace=False)
        candidates = [candidates[i] for i in sorted(idx)]

    n = len(df)
    results = []
    for x, y, z in candidates:
        r = _partial_corr(df, x, y, z)
        p = _fisher_z_pvalue(r, n, len(z))
        results.append({"x": x, "y": y, "given": z, "partial_r": round(r, 4), "p": p})

    # Holm step-down correction: the pass/fail verdict controls the family-wise
    # error rate across all implied independencies tested, so a single unlucky
    # draw does not fail an otherwise correct DAG.
    m = len(results)
    failures = []
    for rank, res in enumerate(sorted(results, key=lambda d: d["p"])):
        if res["p"] < alpha / (m - rank):
            failures.append(res)
        else:
            break
    return {
        "passed": len(failures) == 0,
        "tested": m,
        "alpha": alpha,
        "correction": "holm",
        "violations": failures[:20],
        "n_violations": len(failures),
    }


@REFUTERS.register("gof")
def refute_gof(model: CausalModel, df: pd.DataFrame, seed: int = 0, min_r2: float = 0.0) -> Dict[str, Any]:
    """Per-equation goodness of fit for the linear-Gaussian hypothesis."""
    per: Dict[str, Any] = {}
    worst_r2 = 1.0
    for name in model.topo_order():
        if not _observed(model, df, name):
            continue
        parents = [p for p in model.parents(name) if _observed(model, df, p)]
        if not parents:
            continue
        y = df[name].to_numpy(dtype=float)
        X = np.column_stack([np.ones(len(df))] + [df[p].to_numpy(dtype=float) for p in parents])
        coef, _, _, _ = np.linalg.lstsq(X, y, rcond=None)
        res = y - X @ coef
        ss_tot = float(((y - y.mean()) ** 2).sum())
        r2 = 1 - float(res @ res) / ss_tot if ss_tot > 0 else float("nan")
        std = res.std() or 1.0
        zres = (res - res.mean()) / std
        per[name] = {
            "r2": round(r2, 4),
            "residual_skew": round(float((zres**3).mean()), 3),
            "residual_excess_kurtosis": round(float((zres**4).mean() - 3), 3),
        }
        if not math.isnan(r2):
            worst_r2 = min(worst_r2, r2)
    return {"passed": worst_r2 >= min_r2, "worst_r2": worst_r2, "per_equation": per}


def _dowhy_refuter(method_name: str):
    def run(model: CausalModel, df: pd.DataFrame, seed: int = 0) -> Dict[str, Any]:
        try:
            from dowhy import CausalModel as DowhyModel
        except ImportError:
            return {"passed": True, "skipped": "dowhy not installed (pip install ydbms-pipeline[refute])"}

        treatment = next((n for n, v in model.variables.items() if v.role == "treatment"), None)
        outcome = next((n for n, v in model.variables.items() if v.role == "outcome"), None)
        if not treatment or not outcome:
            return {"passed": True, "skipped": "no treatment/outcome roles configured"}

        g = model.graph()
        gml = "graph [ directed 1 " + " ".join(f'node [ id "{n}" label "{n}" ]' for n in g.nodes) + " " + " ".join(
            f'edge [ source "{u}" target "{v}" ]' for u, v in g.edges
        ) + " ]"
        cm = DowhyModel(data=df, treatment=treatment, outcome=outcome, graph=gml)
        estimand = cm.identify_effect(proceed_when_unidentifiable=True)
        estimate = cm.estimate_effect(estimand, method_name="backdoor.linear_regression")
        ref = cm.refute_estimate(estimand, estimate, method_name=method_name, random_seed=seed)
        new_effect = getattr(ref, "new_effect", None)
        old_effect = getattr(ref, "estimated_effect", None)
        passed = True
        if method_name == "placebo_treatment_refuter" and new_effect is not None:
            passed = abs(new_effect) < max(0.1 * abs(old_effect or 1.0), 0.05)
        return {"passed": passed, "estimated_effect": old_effect, "new_effect": new_effect, "detail": str(ref).strip()[:500]}

    return run


REFUTERS.register("placebo")(_dowhy_refuter("placebo_treatment_refuter"))
REFUTERS.register("random_common_cause")(_dowhy_refuter("random_common_cause"))
REFUTERS.register("subset")(_dowhy_refuter("data_subset_refuter"))
