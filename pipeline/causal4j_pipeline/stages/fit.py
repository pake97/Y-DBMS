"""Stage: fit structural equations under a model hypothesis.

``linear-gaussian`` (v1): per-node OLS on the node's parents. Produces exactly
what the plugin's quantitative layer consumes: edge coefficient beta (DSL
``WEIGHT``), intercept mu_0 (``MEAN``), residual noise variance sigma^2
(``NOISE``). Latent variables are declared but never fitted. Other hypotheses
plug into the HYPOTHESES registry.
"""

from __future__ import annotations

from typing import Any, Dict

import numpy as np
import pandas as pd

from causal4j_pipeline.model import CausalModel
from causal4j_pipeline.registry import HYPOTHESES


def fit_sem(model: CausalModel, df: pd.DataFrame, hypothesis: str = "linear-gaussian", **params) -> Dict[str, Any]:
    """Fit the configured hypothesis in place; return a diagnostics report."""
    return HYPOTHESES.get(hypothesis)(model, df, **params)


@HYPOTHESES.register("linear-gaussian")
def fit_linear_gaussian(model: CausalModel, df: pd.DataFrame, ddof_adjust: bool = True) -> Dict[str, Any]:
    """Per-node OLS in topological order.

    Roots get mean/variance of their column; internal nodes get OLS
    coefficients on their parents (skipping latent parents, which have no
    data), intercept, and residual variance (ddof = number of fitted params
    when ``ddof_adjust``).
    """
    diagnostics: Dict[str, Any] = {"hypothesis": "linear-gaussian", "per_variable": {}}
    n = len(df)

    for name in model.topo_order():
        spec = model.variables[name]
        if spec.latent:
            diagnostics["per_variable"][name] = {"skipped": "latent"}
            continue
        if name not in df.columns:
            diagnostics["per_variable"][name] = {"skipped": "no data column"}
            continue

        y = df[name].to_numpy(dtype=float)
        parents = [p for p in model.parents(name) if not model.variables[p].latent and p in df.columns]

        if not parents:
            spec.mean = float(np.mean(y))
            spec.noise = float(np.var(y, ddof=1 if n > 1 else 0))
            diagnostics["per_variable"][name] = {"kind": "root", "mean": spec.mean, "noise": spec.noise}
            continue

        X = np.column_stack([np.ones(n)] + [df[p].to_numpy(dtype=float) for p in parents])
        coef, _, _, _ = np.linalg.lstsq(X, y, rcond=None)
        residuals = y - X @ coef
        dof = max(n - (len(parents) + 1), 1) if ddof_adjust else n
        sigma2 = float(residuals @ residuals / dof)
        ss_tot = float(((y - y.mean()) ** 2).sum())
        r2 = 1.0 - float(residuals @ residuals) / ss_tot if ss_tot > 0 else float("nan")

        spec.mean = float(coef[0])
        spec.noise = sigma2
        betas = {}
        for i, p in enumerate(parents):
            beta = float(coef[i + 1])
            betas[p] = beta
            for e in model.edges:
                if e.source == p and e.target == name:
                    e.weight = beta
        diagnostics["per_variable"][name] = {
            "kind": "ols",
            "parents": parents,
            "beta": betas,
            "intercept": spec.mean,
            "noise": sigma2,
            "r2": r2,
        }

    diagnostics["n_rows"] = n
    return diagnostics
