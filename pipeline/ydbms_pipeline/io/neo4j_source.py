"""Read-only Neo4j source adapter.

Pulls the current causal DAG out of a running Neo4j instance so it can serve
as Mode B input or as prior knowledge. Mirrors the plugin's loading rules
(GraphLoader): ``(:Variable {id})-[:CAUSES]->(:Variable)`` with only
``coalesce(r.active, true)`` edges, plus SEM properties when present.

This adapter never writes. Loading .causal output into the DB stays with the
Java DSL executor.
"""

from __future__ import annotations

from typing import Optional

from ydbms_pipeline.model import CausalModel, VariableSpec

_NODES_QUERY = """
MATCH (v:Variable)
RETURN v.id AS id, v.name AS name, v.observed AS observed, v.latent AS latent,
       v.intercept AS intercept, v.noise AS noise
"""

_EDGES_QUERY = """
MATCH (a:Variable)-[r:CAUSES]->(b:Variable)
WHERE coalesce(r.active, true)
RETURN a.id AS source, b.id AS target, r.weight AS weight
"""


def read_dag_from_neo4j(
    uri: str,
    user: str = "neo4j",
    password: Optional[str] = None,
    database: Optional[str] = None,
    model_name: str = "Neo4jModel",
) -> CausalModel:
    try:
        from neo4j import GraphDatabase
    except ImportError as e:  # pragma: no cover
        raise ImportError(
            "The Neo4j source adapter requires the 'neo4j' driver: pip install ydbms-pipeline[neo4j]"
        ) from e

    model = CausalModel(name=model_name)
    with GraphDatabase.driver(uri, auth=(user, password) if password else None) as driver:
        with driver.session(database=database) as session:
            for rec in session.run(_NODES_QUERY):
                var_id = str(rec["id"])
                latent = bool(rec["latent"]) if rec["latent"] is not None else False
                spec = VariableSpec(
                    name=var_id,
                    observed_as=rec["name"] if rec["name"] and rec["name"] != var_id else None,
                    var_type="latent" if latent else None,
                    mean=float(rec["intercept"]) if rec["intercept"] is not None else None,
                    noise=float(rec["noise"]) if rec["noise"] is not None else None,
                )
                model.add_variable(spec)
            for rec in session.run(_EDGES_QUERY):
                w = float(rec["weight"]) if rec["weight"] is not None else None
                model.add_edge(str(rec["source"]), str(rec["target"]), w)
    return model
