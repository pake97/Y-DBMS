"""Stage: load & preprocess data; map columns to variable metadata.

Missingness policy is explicit (drop / mean / fail) — never silent.
"""

from __future__ import annotations

from pathlib import Path
from typing import Dict, Optional, Tuple

import pandas as pd

from ydbms_pipeline.config import DataSection
from ydbms_pipeline.model import CausalModel, sanitize_id


def load_data(cfg: DataSection) -> Tuple[pd.DataFrame, Dict[str, str]]:
    """Load the dataset; return (dataframe, original->sanitized name map).

    Column names are sanitized into DSL-safe IDs and the frame is renamed
    accordingly, so every downstream stage works in DSL name space.
    """
    if not cfg.path:
        raise ValueError("data.path is not set")
    p = Path(cfg.path)
    if p.suffix.lower() in (".parquet", ".pq"):
        df = pd.read_parquet(p)
    elif p.suffix.lower() in (".csv", ".tsv"):
        df = pd.read_csv(p, sep="\t" if p.suffix.lower() == ".tsv" else ",")
    else:
        raise ValueError(f"Unsupported data format '{p.suffix}' (csv, tsv, parquet)")

    taken: set = set()
    name_map = {col: sanitize_id(col, taken) for col in df.columns}
    df = df.rename(columns=name_map)

    n_missing = int(df.isna().sum().sum())
    if n_missing:
        if cfg.missing == "fail":
            raise ValueError(f"{cfg.path}: {n_missing} missing values and data.missing=fail")
        if cfg.missing == "drop":
            df = df.dropna()
        elif cfg.missing == "mean":
            num = df.select_dtypes("number").columns
            df[num] = df[num].fillna(df[num].mean())
            df = df.dropna()  # non-numeric NaNs cannot be mean-imputed

    if df.empty:
        raise ValueError(f"{cfg.path}: no rows left after missing-value handling")
    return df, name_map


def infer_type(series: pd.Series) -> str:
    """Infer the DSL TYPE for a data column."""
    s = series.dropna()
    if s.dtype == bool:
        return "binary"
    if pd.api.types.is_numeric_dtype(s):
        uniq = s.unique()
        if len(uniq) <= 2 and set(pd.Series(uniq).astype(float).tolist()) <= {0.0, 1.0}:
            return "binary"
        return "continuous"
    return "categorical"


def apply_metadata(
    model: CausalModel,
    cfg: DataSection,
    df: Optional[pd.DataFrame] = None,
    name_map: Optional[Dict[str, str]] = None,
) -> CausalModel:
    """Attach TYPE / ROLE / UNIT / latent metadata to the model's variables.

    Config keys may use either original or sanitized names. Types are inferred
    from data for variables the config does not pin down.
    """
    nmap = name_map or {}

    def resolve(key: str) -> str:
        return nmap.get(key, key)

    for col, role in cfg.roles.items():
        model.ensure_variable(resolve(col)).role = role
    for col, t in cfg.types.items():
        model.ensure_variable(resolve(col)).var_type = t
    for col, unit in cfg.units.items():
        model.ensure_variable(resolve(col)).unit = unit
    for col in cfg.latents:
        model.ensure_variable(resolve(col)).var_type = "latent"

    if df is not None:
        for name in model.variables:
            spec = model.variables[name]
            if spec.var_type is None and name in df.columns:
                spec.var_type = infer_type(df[name])

    # Record the original column name when sanitization changed it.
    for original, sanitized in nmap.items():
        if original != sanitized and sanitized in model.variables:
            if model.variables[sanitized].observed_as is None:
                model.variables[sanitized].observed_as = original
    return model
