package com.example.causal.api;

import java.util.List;
import java.util.Set;

/**
 * Result of a candidate adjustment set query for identifying the causal effect
 * of a treatment variable on an outcome variable.
 *
 * <p>Each element of {@code candidateSets} is a valid adjustment set — a set of
 * variables that, when conditioned on, blocks all backdoor paths from treatment
 * to outcome (backdoor criterion) or satisfies an equivalent graphical criterion.
 *
 * <p>If {@code adjustmentSetExists} is false, no valid adjustment set exists under
 * the current graphical criterion (e.g. the effect is not identifiable via the
 * backdoor criterion alone).
 */
public record AdjustmentCandidateResult(
        boolean adjustmentSetExists,
        List<Set<VariableRef>> candidateSets,
        String explanation
) {
    /** Placeholder returned while the algorithm is not yet implemented. */
    public static AdjustmentCandidateResult unimplemented() {
        return new AdjustmentCandidateResult(
                false,
                List.of(),
                "TODO: adjustment set algorithm not yet implemented"
        );
    }
}
