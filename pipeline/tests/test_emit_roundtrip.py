"""Round-trip gate: emitted .causal must re-read to the same model, and the
emitter must be deterministic and grammar-safe."""

import pytest

from causal4j_pipeline.emit.dsl import emit_dsl, format_number
from causal4j_pipeline.io.readers import read_causal
from causal4j_pipeline.model import CausalModel, VariableSpec, sanitize_id


def build_model() -> CausalModel:
    m = CausalModel(name="UserRetentionModel")
    m.add_variable(VariableSpec("Treatment", unit="User", var_type="binary", role="treatment"))
    m.add_variable(VariableSpec("Retention30", unit="User", var_type="binary", role="outcome"))
    m.add_variable(VariableSpec("Age", var_type="continuous", role="covariate", mean=42.5, noise=3.25))
    m.add_variable(VariableSpec("Engagement", var_type="continuous", role="mediator"))
    m.add_variable(VariableSpec("Hidden", var_type="latent"))
    m.add_edge("Age", "Treatment", 0.83)
    m.add_edge("Age", "Retention30", -0.12)
    m.add_edge("Treatment", "Engagement", 1.5)
    m.add_edge("Engagement", "Retention30", 0.000125)
    m.add_edge("Hidden", "Engagement")
    return m


def test_roundtrip(tmp_path):
    m = build_model()
    text = emit_dsl(m)
    p = tmp_path / "model.causal"
    p.write_text(text)
    back = read_causal(p)

    assert back.name == "UserRetentionModel"
    assert set(back.variables) == set(m.variables)
    assert {(e.source, e.target) for e in back.edges} == {(e.source, e.target) for e in m.edges}
    for e in m.edges:
        if e.weight is not None:
            assert back.edge_weight(e.source, e.target) == pytest.approx(e.weight, abs=1e-6)
    assert back.variables["Age"].mean == pytest.approx(42.5)
    assert back.variables["Age"].noise == pytest.approx(3.25)
    assert back.variables["Hidden"].var_type == "latent"
    assert back.variables["Treatment"].role == "treatment"
    assert back.variables["Treatment"].unit == "User"


def test_deterministic():
    assert emit_dsl(build_model()) == emit_dsl(build_model())


def test_numbers_grammar_safe():
    # DSL NUMBER has no scientific notation: '-'? [0-9]+ ('.' [0-9]+)?
    assert format_number(0.000125) == "0.000125"
    assert format_number(1.5) == "1.5"
    assert format_number(-0.12) == "-0.12"
    assert format_number(3.0) == "3"
    assert format_number(0.0) == "0"
    assert "e" not in format_number(1e-06).lower()
    with pytest.raises(ValueError):
        format_number(float("nan"))


def test_no_scientific_notation_in_output():
    m = build_model()
    m.add_edge("Age", "Engagement", 1.2e-06)
    assert "e-" not in emit_dsl(m) and "E-" not in emit_dsl(m)


def test_emit_refuses_undirected():
    m = build_model()
    m.undirected.add(frozenset(("Age", "Engagement")))
    with pytest.raises(ValueError, match="unoriented"):
        emit_dsl(m)


def test_emit_refuses_cycle():
    m = build_model()
    m.add_edge("Retention30", "Age")
    with pytest.raises(ValueError, match="cycle"):
        emit_dsl(m)


def test_sanitize_id():
    taken: set = set()
    assert sanitize_id("reported cases", taken) == "reported_cases"
    assert sanitize_id("2fast", taken) == "_2fast"
    assert sanitize_id("latent", taken) == "latent_"
    assert sanitize_id("reported cases", taken) == "reported_cases_2"  # uniqueness
