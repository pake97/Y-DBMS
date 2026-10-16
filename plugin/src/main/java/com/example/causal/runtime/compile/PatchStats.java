package com.example.causal.runtime.compile;

/**
 * {@link SnapshotPatcher} counters, process-wide and monotone since JVM start (so a caller
 * measures a delta). {@link CompiledCausalGraph#patchStats()} holds them as of that
 * snapshot's creation; {@link SnapshotPatcher#stats()} reads them live.
 *
 * @param patchesApplied       batches applied by a patch (not counting empty batches)
 * @param insertionsRelabelled inserted edges against the topological order
 * @param nodesMoved           nodes moved by those relabels
 * @param deletionsRecomputed  removed edges whose cones were recomputed
 * @param lastConeSize         rows recomputed by the last patch: |anc rows| + |des rows| flagged
 * @param fallbacksThreshold   batches declined because the deletion cones exceeded the threshold
 * @param fallbacksInvariant   batches that broke a beforeCommit invariant (cycle, unknown endpoint)
 * @param fallbacksNoClosures  batches against a layer-pruned snapshot (causal.closures.budget)
 */
public record PatchStats(long patchesApplied, long insertionsRelabelled, long nodesMoved,
                         long deletionsRecomputed, long lastConeSize,
                         long fallbacksThreshold, long fallbacksInvariant, long fallbacksNoClosures) {
}
