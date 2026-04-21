package com.example.causal.dto;

import java.util.List;

/**
 * Cypher output row for {@code causal.adjustmentCandidate()}.
 *
 * <p>Usage from Cypher:
 * <pre>
 *   CALL causal.adjustmentCandidate('treatment', 'outcome')
 *     YIELD adjustmentSetExists, candidateSets, explanation
 * </pre>
 *
 * <p>{@code candidateSets} is a list of lists: each inner list is one valid
 * adjustment set expressed as variable ids.
 *
 * <p>Public fields are required by Neo4j's procedure reflection mechanism.
 */
public class AdjustmentRecord {
    public final boolean            adjustmentSetExists;
    public final List<List<String>> candidateSets;
    public final String             explanation;

    public AdjustmentRecord(
            boolean adjustmentSetExists,
            List<List<String>> candidateSets,
            String explanation
    ) {
        this.adjustmentSetExists = adjustmentSetExists;
        this.candidateSets       = candidateSets;
        this.explanation         = explanation;
    }
}
