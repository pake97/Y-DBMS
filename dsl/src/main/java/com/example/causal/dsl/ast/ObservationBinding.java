package com.example.causal.dsl.ast;

/**
 * The OBSERVED AS clause: a raw Cypher query string that maps graph instances
 * to (unit, value, time?) tuples for this variable.
 *
 * The query is stored verbatim and executed against Neo4j at estimation time.
 */
public record ObservationBinding(String cypherQuery, String unit) {}
