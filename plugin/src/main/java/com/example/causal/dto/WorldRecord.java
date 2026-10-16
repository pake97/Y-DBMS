package com.example.causal.dto;

import java.util.List;

/**
 * Output row of {@code causal.intervene}: the derived world just defined and what computing
 * its snapshot cost. {@code coneSize} is the closure rows recomputed ({@code totalRows -
 * sharedRows}); {@code sharedRows} are the rows that are the base's own arrays.
 */
public class WorldRecord {
    public final String       name;
    public final String       base;
    public final List<String> intervened;
    public final long         removedEdges;
    public final long         coneSize;
    public final long         sharedRows;
    public final long         totalRows;
    public final long         millis;

    public WorldRecord(String name, String base, List<String> intervened, long removedEdges,
                       long coneSize, long sharedRows, long totalRows, long millis) {
        this.name         = name;
        this.base         = base;
        this.intervened   = intervened;
        this.removedEdges = removedEdges;
        this.coneSize     = coneSize;
        this.sharedRows   = sharedRows;
        this.totalRows    = totalRows;
        this.millis       = millis;
    }
}
