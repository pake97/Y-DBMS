"""Provenance sidecar: what makes a run auditable and reproducible.

The ``.causal`` file is what the DB loads; the sidecar records where it came
from — data hash, algorithm + hyperparameters + seed, library versions, fit
diagnostics, refutation outcomes, timestamp.
"""

from __future__ import annotations

import hashlib
import json
import platform
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional


def _sha256(path: str | Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _versions() -> Dict[str, str]:
    from importlib.metadata import PackageNotFoundError, version

    out: Dict[str, str] = {"python": platform.python_version()}
    for pkg in ("causal4j-pipeline", "numpy", "pandas", "networkx", "causal-learn", "dowhy", "neo4j"):
        try:
            out[pkg] = version(pkg)
        except PackageNotFoundError:
            pass
    return out


def build_provenance(
    config_raw: Dict[str, Any],
    data_path: Optional[str] = None,
    dag_source: Optional[str] = None,
    discovery: Optional[Dict[str, Any]] = None,
    resolution: Optional[Dict[str, Any]] = None,
    fit: Optional[Dict[str, Any]] = None,
    refutation: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    prov: Dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "tool": "causal4j-pipeline",
        "versions": _versions(),
        "config": config_raw,
    }
    if data_path:
        prov["data"] = {"path": str(data_path), "sha256": _sha256(data_path)}
    if dag_source:
        prov["dag_source"] = dag_source
    if discovery:
        prov["discovery"] = discovery
    if resolution:
        prov["resolution"] = resolution
    if fit:
        prov["fit"] = fit
    if refutation:
        prov["refutation"] = refutation
    return prov


def write_provenance(prov: Dict[str, Any], causal_path: str | Path) -> Path:
    out = Path(causal_path).with_suffix(".provenance.json")
    out.write_text(json.dumps(prov, indent=2, sort_keys=True, default=str))
    return out
