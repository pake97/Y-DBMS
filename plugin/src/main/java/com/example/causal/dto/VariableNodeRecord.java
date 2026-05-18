package com.example.causal.dto;

import org.neo4j.graphdb.Node;

/**
 * Cypher output row carrying a {@code :Variable} node.
 *
 * <p>Used as the output row type for structural query procedures such as
 * {@code causal.parents}, {@code causal.mediators}, etc.
 *
 * <p>Usage from Cypher:
 * <pre>
 *   MATCH (v:Variable {id: 'X'})
 *   CALL causal.ancestors([v]) YIELD variable RETURN variable
 * </pre>
 *
 * <p>Public fields are required by Neo4j's procedure reflection mechanism.
 */
public class VariableNodeRecord {
    public final Node variable;

    public VariableNodeRecord(Node variable) {
        this.variable = variable;
    }
}
