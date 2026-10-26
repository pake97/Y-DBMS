"""Runnable demo: generate synthetic linear-Gaussian data, discover (if
causal-learn is installed) or use the known DAG, fit, refute, emit.

    python examples/synthetic_end_to_end.py
"""

import numpy as np
import pandas as pd

from ydbms_pipeline import Pipeline
from ydbms_pipeline.model import CausalModel


def generate(n=20_000, seed=0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    burden = rng.normal(2.0, 1.0, n)
    interventions = 0.9 * burden + rng.normal(0, 0.6, n)
    exposure = 0.7 * burden - 0.5 * interventions + rng.normal(0, 0.5, n)
    cases = 1.2 * exposure + 0.2 * interventions + rng.normal(0, 0.4, n)
    return pd.DataFrame({
        "covid19_burden": burden,
        "interventions": interventions,
        "exposure_sarscov2": exposure,
        "reported_cases": cases,
    })


def known_dag() -> CausalModel:
    m = CausalModel(name="CovidToy")
    m.add_edge("covid19_burden", "interventions")
    m.add_edge("covid19_burden", "exposure_sarscov2")
    m.add_edge("interventions", "exposure_sarscov2")
    m.add_edge("interventions", "reported_cases")
    m.add_edge("exposure_sarscov2", "reported_cases")
    return m


def main():
    df = generate()
    data_path = "out/synthetic.csv"
    import os

    os.makedirs("out", exist_ok=True)
    df.to_csv(data_path, index=False)

    p = Pipeline(seed=42, name="synthetic-demo").with_data(
        data_path,
        roles={"interventions": "treatment", "reported_cases": "outcome", "covid19_burden": "confounder"},
    )

    try:
        import causallearn  # noqa: F401

        print("causal-learn found -> Mode A (discovery with tier knowledge)")
        p = p.with_knowledge(
            tiers=[["covid19_burden"], ["interventions"], ["exposure_sarscov2"], ["reported_cases"]]
        ).discover("pc", alpha=0.01).resolve("prior")
    except ImportError:
        print("causal-learn not installed -> Mode B (known DAG)")
        p = p.with_model(known_dag())

    result = p.fit("linear-gaussian").refute("ci", "gof").emit(
        "out/synthetic.causal", model_name="SyntheticCovid", unit="Region"
    )

    print(f"\nwrote {result.dsl_path}\nwrote {result.provenance_path}\n")
    print(result.dsl_text)
    print("refutation passed:", result.report["refutation"]["passed"])


if __name__ == "__main__":
    main()
