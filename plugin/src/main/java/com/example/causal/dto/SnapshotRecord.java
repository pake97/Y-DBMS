package com.example.causal.dto;

import com.example.causal.runtime.compile.PatchStats;

import java.util.List;

/**
 * Cypher output row for {@code causal.compile()} and {@code causal.inspectSnapshot()}.
 * The patch columns are {@code SnapshotPatcher}'s process-wide counters (WP3), read when
 * the row is built.
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
    public final long         patchesApplied;
    public final long         insertionsRelabelled;
    public final long         nodesMoved;
    public final long         deletionsRecomputed;
    public final long         lastConeSize;
    public final long         fallbacksThreshold;
    public final long         fallbacksInvariant;
    public final long         fallbacksNoClosures;

    public SnapshotRecord(long variableCount, List<String> variables, String compiledAt, PatchStats p) {
        this.variableCount        = variableCount;
        this.variables            = variables;
        this.compiledAt           = compiledAt;
        this.patchesApplied       = p.patchesApplied();
        this.insertionsRelabelled = p.insertionsRelabelled();
        this.nodesMoved           = p.nodesMoved();
        this.deletionsRecomputed  = p.deletionsRecomputed();
        this.lastConeSize         = p.lastConeSize();
        this.fallbacksThreshold   = p.fallbacksThreshold();
        this.fallbacksInvariant   = p.fallbacksInvariant();
        this.fallbacksNoClosures  = p.fallbacksNoClosures();
    }
}
