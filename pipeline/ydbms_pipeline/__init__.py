"""Y-DBMS ingestion pipeline.

Everything statistical/ML that the database cannot do happens here, upstream:
causal structure discovery, hypothesis-driven SEM fitting, refutation. The
output contract is a grammar-valid ``.causal`` DSL file (plus a provenance
sidecar); loading into Neo4j remains the job of the Java DSL executor.
"""

from ydbms_pipeline.model import CausalModel, VariableSpec, EdgeSpec
from ydbms_pipeline.pipeline import Pipeline, RunResult

__version__ = "0.1.0"

__all__ = ["CausalModel", "VariableSpec", "EdgeSpec", "Pipeline", "RunResult", "__version__"]
