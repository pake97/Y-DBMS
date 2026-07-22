"""End-to-end: Mode B (DAG + data -> fitted .causal + provenance) and the
config-driven path; Mode A when causal-learn is installed."""

import json

import numpy as np
import pandas as pd
import pytest
import yaml

from causal4j_pipeline.io.readers import read_causal
from causal4j_pipeline.pipeline import Pipeline, run


@pytest.fixture()
def dataset(tmp_path):
    rng = np.random.default_rng(11)
    n = 30_000
    z = rng.normal(0, 1, n)
    t = 0.8 * z + rng.normal(0, 0.5, n)
    y = 1.5 * t + 0.3 * z + rng.normal(0, 0.5, n)
    df = pd.DataFrame({"Z": z, "T": t, "Y": y})
    p = tmp_path / "data.csv"
    df.to_csv(p, index=False)
    return p


@pytest.fixture()
def dag_file(tmp_path):
    p = tmp_path / "dag.csv"
    p.write_text("source,target\nZ,T\nZ,Y\nT,Y\n")
    return p


def test_mode_b_builder(dataset, dag_file, tmp_path):
    out = tmp_path / "out" / "model.causal"
    result = (
        Pipeline(seed=42, name="e2e")
        .with_data(str(dataset), roles={"T": "treatment", "Y": "outcome"})
        .with_dag(str(dag_file))
        .fit("linear-gaussian")
        .refute("ci", "gof")
        .emit(str(out), model_name="ToyModel", unit="User")
    )
    assert result.dsl_path and result.dsl_path.exists()
    assert result.provenance_path and result.provenance_path.exists()

    back = read_causal(result.dsl_path)
    assert back.name == "ToyModel"
    assert back.edge_weight("T", "Y") == pytest.approx(1.5, abs=0.05)
    assert back.variables["T"].role == "treatment"
    assert back.variables["Z"].var_type == "continuous"
    assert back.variables["T"].unit == "User"

    prov = json.loads(result.provenance_path.read_text())
    assert prov["data"]["sha256"]
    assert prov["fit"]["hypothesis"] == "linear-gaussian"
    assert prov["refutation"]["passed"]


def test_mode_b_from_yaml_config(dataset, dag_file, tmp_path):
    out = tmp_path / "cfg-out" / "model.causal"
    cfg = {
        "run": {"name": "cfg-e2e", "seed": 1},
        "data": {"path": str(dataset), "roles": {"T": "treatment", "Y": "outcome"}},
        "dag": {"path": str(dag_file)},
        "model": {"hypothesis": "linear-gaussian"},
        "refute": {"tests": ["ci"], "on_failure": "block"},
        "emit": {"model_name": "CfgModel", "out": str(out)},
    }
    cfg_path = tmp_path / "run.yaml"
    cfg_path.write_text(yaml.safe_dump(cfg))

    result = run(cfg_path)
    assert result.dsl_path.exists()
    assert read_causal(result.dsl_path).name == "CfgModel"


def test_dry_run_writes_nothing(dataset, dag_file, tmp_path):
    out = tmp_path / "never" / "model.causal"
    result = (
        Pipeline()
        .with_data(str(dataset))
        .with_dag(str(dag_file))
        .fit()
        .emit(str(out), dry_run=True)
    )
    assert not out.exists()
    assert "DEFINE MODEL" in result.dsl_text


def test_column_sanitization(tmp_path):
    rng = np.random.default_rng(5)
    df = pd.DataFrame({"reported cases": rng.normal(size=1000), "2fast": rng.normal(size=1000)})
    data = tmp_path / "odd.csv"
    df.to_csv(data, index=False)
    dag = tmp_path / "dag.csv"
    dag.write_text("source,target\nreported_cases,_2fast\n")

    result = (
        Pipeline()
        .with_data(str(data))
        .with_dag(str(dag))
        .fit()
        .emit(str(tmp_path / "m.causal"))
    )
    v = result.model.variables["reported_cases"]
    assert v.observed_as == "reported cases"  # original name preserved
    assert "reported cases" not in result.dsl_text.replace('OBSERVED AS "reported cases"', "")


def test_mode_a_discovery(dataset, tmp_path):
    pytest.importorskip("causallearn")
    result = (
        Pipeline(seed=0)
        .with_data(str(dataset))
        .with_knowledge(tiers=[["Z"], ["T"], ["Y"]])
        .discover("pc", alpha=0.05)
        .resolve("prior")
        .fit("linear-gaussian")
        .emit(str(tmp_path / "disc.causal"), model_name="Discovered")
    )
    edges = {(e.source, e.target) for e in result.model.edges}
    assert ("T", "Y") in edges or ("Z", "Y") in edges  # recovered structure, tier-consistent
    assert result.report["discovery"]["algorithm"] == "pc"
