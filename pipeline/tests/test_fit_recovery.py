"""Recovery test: fit on data generated from a known linear-Gaussian SEM must
recover beta / intercept / noise within tolerance."""

import numpy as np
import pandas as pd
import pytest

from causal4j_pipeline.model import CausalModel
from causal4j_pipeline.stages.fit import fit_sem

N = 50_000
SEED = 7

# Ground truth:  Z ~ N(1.0, 1.0);  T = 0.5 + 0.8*Z + eps(0.25);  Y = -1.0 + 1.5*T + 0.3*Z + eps(0.5)
TRUE = {
    "Z": {"mean": 1.0, "noise": 1.0},
    "T": {"intercept": 0.5, "beta": {"Z": 0.8}, "noise": 0.25},
    "Y": {"intercept": -1.0, "beta": {"T": 1.5, "Z": 0.3}, "noise": 0.5},
}


def generate() -> pd.DataFrame:
    rng = np.random.default_rng(SEED)
    z = TRUE["Z"]["mean"] + rng.normal(0, np.sqrt(TRUE["Z"]["noise"]), N)
    t = TRUE["T"]["intercept"] + TRUE["T"]["beta"]["Z"] * z + rng.normal(0, np.sqrt(TRUE["T"]["noise"]), N)
    y = (TRUE["Y"]["intercept"] + TRUE["Y"]["beta"]["T"] * t + TRUE["Y"]["beta"]["Z"] * z
         + rng.normal(0, np.sqrt(TRUE["Y"]["noise"]), N))
    return pd.DataFrame({"Z": z, "T": t, "Y": y})


def build_dag() -> CausalModel:
    m = CausalModel(name="Toy")
    m.add_edge("Z", "T")
    m.add_edge("Z", "Y")
    m.add_edge("T", "Y")
    return m


def test_linear_gaussian_recovery():
    df = generate()
    m = build_dag()
    report = fit_sem(m, df, "linear-gaussian")

    # Root: sample mean / variance.
    assert m.variables["Z"].mean == pytest.approx(TRUE["Z"]["mean"], abs=0.02)
    assert m.variables["Z"].noise == pytest.approx(TRUE["Z"]["noise"], rel=0.05)

    # T equation.
    assert m.variables["T"].mean == pytest.approx(TRUE["T"]["intercept"], abs=0.02)
    assert m.edge_weight("Z", "T") == pytest.approx(TRUE["T"]["beta"]["Z"], abs=0.02)
    assert m.variables["T"].noise == pytest.approx(TRUE["T"]["noise"], rel=0.05)

    # Y equation.
    assert m.variables["Y"].mean == pytest.approx(TRUE["Y"]["intercept"], abs=0.03)
    assert m.edge_weight("T", "Y") == pytest.approx(TRUE["Y"]["beta"]["T"], abs=0.02)
    assert m.edge_weight("Z", "Y") == pytest.approx(TRUE["Y"]["beta"]["Z"], abs=0.03)
    assert m.variables["Y"].noise == pytest.approx(TRUE["Y"]["noise"], rel=0.05)

    assert report["per_variable"]["Y"]["r2"] > 0.7


def test_latent_parent_is_skipped():
    df = generate()
    m = build_dag()
    m.ensure_variable("U").var_type = "latent"
    m.add_edge("U", "Y")
    report = fit_sem(m, df, "linear-gaussian")
    assert report["per_variable"]["U"] == {"skipped": "latent"}
    # Y is still fitted on its observed parents only.
    assert "U" not in report["per_variable"]["Y"]["parents"]
    assert m.edge_weight("U", "Y") is None
