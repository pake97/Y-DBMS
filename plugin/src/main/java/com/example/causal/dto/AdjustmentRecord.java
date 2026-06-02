package com.example.causal.dto;

import org.neo4j.graphdb.Node;

import java.util.List;

/**
 * Cypher output row for {@code causal.adjustmentCandidate()}.
 *
 * <p>Usage from Cypher:
 * <pre>
 *   MATCH (t:Variable {id: 'treatment'}), (o:Variable {id: 'outcome'})
 *   CALL causal.adjustmentCandidate(t, o)
 *     YIELD adjustmentSetExists, candidateSets, explanation
 * </pre>
 *
 * <p>{@code candidateSets} is a list of lists: each inner list is one valid
 * adjustment set as {@code :Variable} nodes.
 *
 * <p>Public fields are required by Neo4j's procedure reflection mechanism.
 */
public class AdjustmentRecord {
    public final boolean           adjustmentSetExists;
    public final List<List<Node>>  candidateSets;
    public final String            explanation;

    public AdjustmentRecord(
            boolean adjustmentSetExists,
            List<List<Node>> candidateSets,
            String explanation
    ) {
        this.adjustmentSetExists = adjustmentSetExists;
        this.candidateSets       = candidateSets;
        this.explanation         = explanation;
    }
}
