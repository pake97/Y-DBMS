package com.example.causal.api;

import java.util.List;
import java.util.Set;

/**
 * Identification of the total effect of {@code treatment} on {@code outcome} by covariate
 * adjustment under the generalized adjustment criterion.
 *
 * @param identifiable whether some adjustment set exists (the canonical set is one)
 * @param canonical    the canonical set (observed ancestors of {t, y} outside the forbidden set)
 * @param forbidden    the forbidden set: treatment, causal nodes and their descendants
 * @param sets         per {@code mode}: {@code canonical} → [canonical] if identifiable;
 *                     {@code minimal} → one inclusion-minimal set; {@code all-minimal} → up to
 *                     {@code limit} inclusion-minimal sets; empty if not identifiable
 * @param mode         {@code canonical}, {@code minimal} or {@code all-minimal}
 * @param explanation  human-readable summary
 */
public record AdjustmentSetsResult(boolean identifiable, Set<VariableRef> canonical, Set<VariableRef> forbidden,
                                   List<Set<VariableRef>> sets, String mode, String explanation) {}
