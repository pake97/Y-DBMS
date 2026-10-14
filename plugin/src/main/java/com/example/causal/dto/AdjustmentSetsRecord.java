package com.example.causal.dto;

import org.neo4j.graphdb.Node;

import java.util.List;

/** Output row of {@code causal.adjustmentSets}. Public final fields for Neo4j's row mapping. */
public class AdjustmentSetsRecord {
    public final boolean          identifiable;
    public final List<Node>       canonical;
    public final List<Node>       forbidden;
    public final List<List<Node>> sets;
    public final String           mode;
    public final String           explanation;

    public AdjustmentSetsRecord(boolean identifiable, List<Node> canonical, List<Node> forbidden,
                                List<List<Node>> sets, String mode, String explanation) {
        this.identifiable = identifiable;
        this.canonical    = canonical;
        this.forbidden    = forbidden;
        this.sets         = sets;
        this.mode         = mode;
        this.explanation  = explanation;
    }
}
