package com.example.causal.dto;

/**
 * Cypher output row carrying a single variable id.
 *
 * <p>Used as the output row type for:
 * {@code causal.parents}, {@code causal.children},
 * {@code causal.ancestors}, {@code causal.descendants}.
 *
 * <p>Usage from Cypher:
 * <pre>
 *   CALL causal.ancestors(['X']) YIELD variableId RETURN variableId
 * </pre>
 *
 * <p>Public fields are required by Neo4j's procedure reflection mechanism.
 */
public class VariableNameRecord {
    public final String variableId;

    public VariableNameRecord(String variableId) {
        this.variableId = variableId;
    }
}
