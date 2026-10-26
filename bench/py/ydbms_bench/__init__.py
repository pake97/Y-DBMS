"""Datasets, workloads and reference answers for the Y-DBMS evaluation."""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
BENCH = REPO / "bench"
DATASETS = BENCH / "datasets"
WORKLOADS = BENCH / "workloads"
REFERENCE = BENCH / "reference"
