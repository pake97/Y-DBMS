"""YAML run configuration: schema, defaults, validation.

One YAML file fully describes a run (see PIPELINE-DESIGN.md section 7), which is
what makes reruns identical and sweeps easy.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import yaml

from causal4j_pipeline.model import VARIABLE_ROLES, VARIABLE_TYPES


class ConfigError(ValueError):
    pass


def _expect_mapping(node: Any, where: str) -> Dict[str, Any]:
    if node is None:
        return {}
    if not isinstance(node, dict):
        raise ConfigError(f"'{where}' must be a mapping, got {type(node).__name__}")
    return node


def _edge_list(node: Any, where: str) -> List[Tuple[str, str]]:
    out: List[Tuple[str, str]] = []
    for item in node or []:
        if not isinstance(item, (list, tuple)) or len(item) != 2:
            raise ConfigError(f"'{where}' entries must be [source, target] pairs, got {item!r}")
        out.append((str(item[0]), str(item[1])))
    return out


@dataclass
class RunSection:
    name: str = "run"
    seed: int = 0


@dataclass
class DataSection:
    path: Optional[str] = None
    roles: Dict[str, str] = field(default_factory=dict)
    types: Dict[str, str] = field(default_factory=dict)
    units: Dict[str, str] = field(default_factory=dict)
    missing: str = "drop"  # drop | mean | fail
    latents: List[str] = field(default_factory=list)


@dataclass
class DagSection:
    path: Optional[str] = None
    format: Optional[str] = None  # inferred from extension when None
    neo4j_uri: Optional[str] = None  # read-only source adapter
    neo4j_user: str = "neo4j"
    neo4j_password: Optional[str] = None
    neo4j_database: Optional[str] = None


@dataclass
class KnowledgeSection:
    required: List[Tuple[str, str]] = field(default_factory=list)
    forbidden: List[Tuple[str, str]] = field(default_factory=list)
    tiers: List[List[str]] = field(default_factory=list)  # earlier tier can't be caused by later


@dataclass
class DiscoverySection:
    algorithm: Optional[str] = None
    params: Dict[str, Any] = field(default_factory=dict)
    knowledge: KnowledgeSection = field(default_factory=KnowledgeSection)


@dataclass
class ResolveSection:
    undirected_policy: str = "fail"  # fail | prior | heuristic


@dataclass
class ModelSection:
    hypothesis: str = "linear-gaussian"
    params: Dict[str, Any] = field(default_factory=dict)


@dataclass
class RefuteSection:
    tests: List[str] = field(default_factory=list)
    on_failure: str = "warn"  # warn | block


@dataclass
class EmitSection:
    model_name: Optional[str] = None
    out: Optional[str] = None
    provenance: bool = True
    unit: Optional[str] = None  # FOR UNIT on every variable, if set


@dataclass
class PipelineConfig:
    run: RunSection = field(default_factory=RunSection)
    data: DataSection = field(default_factory=DataSection)
    dag: DagSection = field(default_factory=DagSection)
    discovery: DiscoverySection = field(default_factory=DiscoverySection)
    resolve: ResolveSection = field(default_factory=ResolveSection)
    model: ModelSection = field(default_factory=ModelSection)
    refute: RefuteSection = field(default_factory=RefuteSection)
    emit: EmitSection = field(default_factory=EmitSection)
    raw: Dict[str, Any] = field(default_factory=dict)

    # ── mode detection ───────────────────────────────────────────────────────

    @property
    def has_dag_input(self) -> bool:
        return bool(self.dag.path or self.dag.neo4j_uri)

    @property
    def has_discovery(self) -> bool:
        return self.discovery.algorithm is not None

    def validate(self) -> None:
        if not self.has_dag_input and not self.has_discovery:
            raise ConfigError(
                "Neither 'dag' (Mode B) nor 'discovery.algorithm' (Mode A) is set — nothing to do."
            )
        if self.has_dag_input and self.has_discovery:
            raise ConfigError("Set either 'dag' (Mode B) or 'discovery' (Mode A), not both.")
        if self.has_discovery and not self.data.path:
            raise ConfigError("Mode A (discovery) requires 'data.path'.")
        if self.resolve.undirected_policy not in {"fail", "prior", "heuristic"}:
            raise ConfigError(
                f"resolve.undirected_policy must be fail|prior|heuristic, got '{self.resolve.undirected_policy}'"
            )
        if self.refute.on_failure not in {"warn", "block"}:
            raise ConfigError(f"refute.on_failure must be warn|block, got '{self.refute.on_failure}'")
        if self.data.missing not in {"drop", "mean", "fail"}:
            raise ConfigError(f"data.missing must be drop|mean|fail, got '{self.data.missing}'")
        for col, role in self.data.roles.items():
            if role not in VARIABLE_ROLES:
                raise ConfigError(f"data.roles[{col}]='{role}' is not a DSL role {sorted(VARIABLE_ROLES)}")
        for col, t in self.data.types.items():
            if t not in VARIABLE_TYPES:
                raise ConfigError(f"data.types[{col}]='{t}' is not a DSL type {sorted(VARIABLE_TYPES)}")


def load_config(path: str | Path) -> PipelineConfig:
    raw = yaml.safe_load(Path(path).read_text()) or {}
    return parse_config(raw)


def parse_config(raw: Dict[str, Any]) -> PipelineConfig:
    if not isinstance(raw, dict):
        raise ConfigError("Top-level config must be a mapping")
    known = {"run", "data", "dag", "discovery", "resolve", "model", "refute", "emit"}
    unknown = set(raw) - known
    if unknown:
        raise ConfigError(f"Unknown top-level config section(s): {sorted(unknown)}")

    run_n = _expect_mapping(raw.get("run"), "run")
    data_n = _expect_mapping(raw.get("data"), "data")
    dag_n = _expect_mapping(raw.get("dag"), "dag")
    disc_n = _expect_mapping(raw.get("discovery"), "discovery")
    kn_n = _expect_mapping(disc_n.get("knowledge"), "discovery.knowledge")
    res_n = _expect_mapping(raw.get("resolve"), "resolve")
    model_n = _expect_mapping(raw.get("model"), "model")
    refute_n = _expect_mapping(raw.get("refute"), "refute")
    emit_n = _expect_mapping(raw.get("emit"), "emit")

    cfg = PipelineConfig(
        run=RunSection(
            name=str(run_n.get("name", "run")),
            seed=int(run_n.get("seed", 0)),
        ),
        data=DataSection(
            path=data_n.get("path"),
            roles={str(k): str(v) for k, v in _expect_mapping(data_n.get("roles"), "data.roles").items()},
            types={str(k): str(v) for k, v in _expect_mapping(data_n.get("types"), "data.types").items()},
            units={str(k): str(v) for k, v in _expect_mapping(data_n.get("units"), "data.units").items()},
            missing=str(data_n.get("missing", "drop")),
            latents=[str(x) for x in data_n.get("latents", [])],
        ),
        dag=DagSection(
            path=dag_n.get("path"),
            format=dag_n.get("format"),
            neo4j_uri=dag_n.get("neo4j_uri"),
            neo4j_user=str(dag_n.get("neo4j_user", "neo4j")),
            neo4j_password=dag_n.get("neo4j_password"),
            neo4j_database=dag_n.get("neo4j_database"),
        ),
        discovery=DiscoverySection(
            algorithm=disc_n.get("algorithm"),
            params=_expect_mapping(disc_n.get("params"), "discovery.params"),
            knowledge=KnowledgeSection(
                required=_edge_list(kn_n.get("required"), "discovery.knowledge.required"),
                forbidden=_edge_list(kn_n.get("forbidden"), "discovery.knowledge.forbidden"),
                tiers=[[str(v) for v in tier] for tier in kn_n.get("tiers", [])],
            ),
        ),
        resolve=ResolveSection(
            undirected_policy=str(res_n.get("undirected_policy", "fail")),
        ),
        model=ModelSection(
            hypothesis=str(model_n.get("hypothesis", "linear-gaussian")),
            params=_expect_mapping(model_n.get("params"), "model.params"),
        ),
        refute=RefuteSection(
            tests=[str(t) for t in refute_n.get("tests", [])],
            on_failure=str(refute_n.get("on_failure", "warn")),
        ),
        emit=EmitSection(
            model_name=emit_n.get("model_name"),
            out=emit_n.get("out"),
            provenance=bool(emit_n.get("provenance", True)),
            unit=emit_n.get("unit"),
        ),
        raw=raw,
    )
    cfg.validate()
    return cfg
