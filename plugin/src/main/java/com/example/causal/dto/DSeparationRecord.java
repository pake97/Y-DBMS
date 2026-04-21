package com.example.causal.dto;

/**
 * Cypher output row for {@code causal.dsep()}.
 *
 * <p>Usage from Cypher:
 * <pre>
 *   CALL causal.dsep(['X'], ['Y'], ['Z']) YIELD separated, explanation
 * </pre>
 *
 * <p>Public fields are required by Neo4j's procedure reflection mechanism.
 */
public class DSeparationRecord {
    public final boolean separated;
    public final String  explanation;

    public DSeparationRecord(boolean separated, String explanation) {
        this.separated   = separated;
        this.explanation = explanation;
    }
}
