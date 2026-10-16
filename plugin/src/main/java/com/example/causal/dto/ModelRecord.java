package com.example.causal.dto;

import java.util.List;

/**
 * Output row of {@code causal.models}, {@code causal.use} and {@code causal.drop}: one world.
 * {@code base} is null for the world {@code base}; {@code intervened} is the resolved set;
 * {@code nodes}/{@code edges} are 0 while the world's snapshot is not materialized.
 */
public class ModelRecord {
    public final String       name;
    public final String       base;
    public final List<String> intervened;
    public final boolean      active;
    public final boolean      materialized;
    public final long         nodes;
    public final long         edges;

    public ModelRecord(String name, String base, List<String> intervened, boolean active,
                       boolean materialized, long nodes, long edges) {
        this.name         = name;
        this.base         = base;
        this.intervened   = intervened;
        this.active       = active;
        this.materialized = materialized;
        this.nodes        = nodes;
        this.edges        = edges;
    }
}
