"""The runner: chains stages from a config (CLI path) or via the builder API.

Notebook usage::

    from causal4j_pipeline import Pipeline
    result = (Pipeline(seed=42)
              .with_data("data/covid.csv", roles={"interventions": "treatment"})
              .with_dag("dags/covid.gml")            # Mode B ... or:
              # .discover("pc", alpha=0.05)          # Mode A
              .fit("linear-gaussian")
              .refute("ci", "gof")
              .emit("out/covid.causal", model_name="CovidModel"))
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import pandas as pd

from causal4j_pipeline import registry
from causal4j_pipeline.config import DataSection, KnowledgeSection, PipelineConfig, load_config
from causal4j_pipeline.emit.dsl import emit_dsl, write_dsl
from causal4j_pipeline.emit.provenance import build_provenance, write_provenance
from causal4j_pipeline.io.readers import read_dag
from causal4j_pipeline.io.neo4j_source import read_dag_from_neo4j
from causal4j_pipeline.model import CausalModel
from causal4j_pipeline.stages.discover import discover
from causal4j_pipeline.stages.fit import fit_sem
from causal4j_pipeline.stages.load import apply_metadata, load_data
from causal4j_pipeline.stages.refute import run_refutation
from causal4j_pipeline.stages.resolve import resolve_model


@dataclass
class RunResult:
    model: CausalModel
    dsl_text: str
    dsl_path: Optional[Path] = None
    provenance_path: Optional[Path] = None
    report: Dict[str, Any] = field(default_factory=dict)


class Pipeline:
    """Builder-style pipeline. Every stage is optional; ``emit`` closes the run."""

    def __init__(self, seed: int = 0, name: str = "run"):
        registry.load_plugins()
        self.seed = seed
        self.name = name
        self._data_cfg = DataSection()
        self._df: Optional[pd.DataFrame] = None
        self._name_map: Dict[str, str] = {}
        self._model: Optional[CausalModel] = None
        self._knowledge = KnowledgeSection()
        self._resolve_policy = "fail"
        self._hypothesis: Optional[str] = None
        self._hypothesis_params: Dict[str, Any] = {}
        self._refute_tests: List[str] = []
        self._refute_on_failure = "warn"
        self._report: Dict[str, Any] = {}
        self._dag_source: Optional[str] = None
        self._config_raw: Dict[str, Any] = {}

    # ── inputs ───────────────────────────────────────────────────────────────

    def with_data(self, path: str, roles: Optional[Dict[str, str]] = None,
                  types: Optional[Dict[str, str]] = None, latents: Optional[List[str]] = None,
                  units: Optional[Dict[str, str]] = None, missing: str = "drop") -> "Pipeline":
        self._data_cfg = DataSection(path=path, roles=roles or {}, types=types or {},
                                     units=units or {}, missing=missing, latents=latents or [])
        self._df, self._name_map = load_data(self._data_cfg)
        return self

    def with_dag(self, path: str, fmt: Optional[str] = None) -> "Pipeline":
        self._model = read_dag(path, fmt)
        self._dag_source = str(path)
        return self

    def with_dag_from_neo4j(self, uri: str, user: str = "neo4j", password: Optional[str] = None,
                            database: Optional[str] = None) -> "Pipeline":
        self._model = read_dag_from_neo4j(uri, user, password, database)
        self._dag_source = uri
        return self

    def with_model(self, model: CausalModel) -> "Pipeline":
        self._model = model
        self._dag_source = "in-memory"
        return self

    def with_knowledge(self, required: Optional[List] = None, forbidden: Optional[List] = None,
                       tiers: Optional[List[List[str]]] = None) -> "Pipeline":
        self._knowledge = KnowledgeSection(
            required=[tuple(e) for e in required or []],
            forbidden=[tuple(e) for e in forbidden or []],
            tiers=tiers or [],
        )
        return self

    # ── stages ───────────────────────────────────────────────────────────────

    def discover(self, algorithm: str, **params) -> "Pipeline":
        if self._df is None:
            raise ValueError("discover() needs data — call with_data() first")
        if self._model is not None:
            raise ValueError("A DAG is already set; use either with_dag (Mode B) or discover (Mode A)")
        self._model, report = discover(self._df, algorithm, self._knowledge, self.seed, **params)
        self._report["discovery"] = report
        self._dag_source = f"discovered:{algorithm}"
        return self

    def resolve(self, policy: str = "fail") -> "Pipeline":
        self._resolve_policy = policy
        return self

    def fit(self, hypothesis: str = "linear-gaussian", **params) -> "Pipeline":
        self._hypothesis = hypothesis
        self._hypothesis_params = params
        return self

    def refute(self, *tests: str, on_failure: str = "warn") -> "Pipeline":
        self._refute_tests = list(tests)
        self._refute_on_failure = on_failure
        return self

    # ── terminal ─────────────────────────────────────────────────────────────

    def emit(self, out: Optional[str] = None, model_name: Optional[str] = None,
             unit: Optional[str] = None, provenance: bool = True, dry_run: bool = False) -> RunResult:
        if self._model is None:
            raise ValueError("Nothing to emit: set a DAG (with_dag) or run discover() first")
        model = self._model

        if model_name:
            model.name = model_name

        # Resolve (no-op when the model is already a DAG with no unoriented edges).
        column_order = list(self._df.columns) if self._df is not None else []
        self._report["resolution"] = resolve_model(model, self._resolve_policy, self._knowledge, column_order)

        # Metadata from data config (roles, types, units, latents, inference).
        apply_metadata(model, self._data_cfg, self._df, self._name_map)

        # Fit SEM under the hypothesis.
        if self._hypothesis:
            if self._df is None:
                raise ValueError(f"fit('{self._hypothesis}') needs data — call with_data() first")
            self._report["fit"] = fit_sem(model, self._df, self._hypothesis, **self._hypothesis_params)

        # Refute / diagnose.
        if self._refute_tests:
            if self._df is None:
                raise ValueError("refute() needs data — call with_data() first")
            self._report["refutation"] = run_refutation(
                model, self._df, self._refute_tests, self._refute_on_failure, self.seed
            )

        model.validate(self._knowledge.required, self._knowledge.forbidden)

        header = f"Generated by causal4j-pipeline (run: {self.name}, seed: {self.seed}). Do not edit by hand."
        text = emit_dsl(model, default_unit=unit, header=header)

        result = RunResult(model=model, dsl_text=text, report=self._report)
        if out and not dry_run:
            result.dsl_path = write_dsl(model, out, default_unit=unit, header=header)
            if provenance:
                prov = build_provenance(
                    config_raw=self._config_raw or {"builder": True, "run": {"name": self.name, "seed": self.seed}},
                    data_path=self._data_cfg.path,
                    dag_source=self._dag_source,
                    discovery=self._report.get("discovery"),
                    resolution=self._report.get("resolution"),
                    fit=self._report.get("fit"),
                    refutation=self._report.get("refutation"),
                )
                result.provenance_path = write_provenance(prov, result.dsl_path)
        return result

    # ── config-driven entry point ────────────────────────────────────────────

    @classmethod
    def from_config(cls, cfg: PipelineConfig | str | Path) -> "Pipeline":
        if not isinstance(cfg, PipelineConfig):
            cfg = load_config(cfg)
        p = cls(seed=cfg.run.seed, name=cfg.run.name)
        p._config_raw = cfg.raw
        p._knowledge = cfg.discovery.knowledge
        p._resolve_policy = cfg.resolve.undirected_policy
        if cfg.data.path:
            p._data_cfg = cfg.data
            p._df, p._name_map = load_data(cfg.data)
        if cfg.dag.path:
            p.with_dag(cfg.dag.path, cfg.dag.format)
        elif cfg.dag.neo4j_uri:
            p.with_dag_from_neo4j(cfg.dag.neo4j_uri, cfg.dag.neo4j_user,
                                  cfg.dag.neo4j_password, cfg.dag.neo4j_database)
        elif cfg.discovery.algorithm:
            p.discover(cfg.discovery.algorithm, **cfg.discovery.params)
        if cfg.model.hypothesis and cfg.data.path:
            p.fit(cfg.model.hypothesis, **cfg.model.params)
        if cfg.refute.tests:
            p.refute(*cfg.refute.tests, on_failure=cfg.refute.on_failure)
        return p

    def run_config(self, cfg: PipelineConfig, dry_run: bool = False) -> RunResult:
        return self.emit(
            out=cfg.emit.out,
            model_name=cfg.emit.model_name,
            unit=cfg.emit.unit,
            provenance=cfg.emit.provenance,
            dry_run=dry_run,
        )


def run(config_path: str | Path, dry_run: bool = False) -> RunResult:
    """One-call entry point: full chain from a YAML config."""
    cfg = load_config(config_path)
    return Pipeline.from_config(cfg).run_config(cfg, dry_run=dry_run)
