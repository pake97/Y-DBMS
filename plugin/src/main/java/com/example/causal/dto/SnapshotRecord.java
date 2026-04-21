package com.example.causal.dto;

import java.util.List;

/**
 * Cypher output row for {@code causal.compile()} and {@code causal.inspectSnapshot()}.
 *
 * <p>Usage from Cypher:
 * <pre>
 *   CALL causal.compile() YIELD variableCount, variables, compiledAt
 * </pre>
 *
 * <p>Public fields are required by Neo4j's procedure reflection mechanism.
 */
public class SnapshotRecord {
    public final long         variableCount;
    public final List<String> variables;
    public final String       compiledAt;

    public SnapshotRecord(long variableCount, List<String> variables, String compiledAt) {
        this.variableCount = variableCount;
        this.variables     = variables;
        this.compiledAt    = compiledAt;
    }
}
