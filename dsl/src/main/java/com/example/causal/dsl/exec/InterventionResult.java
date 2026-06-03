package com.example.causal.dsl.exec;

import java.util.Set;

/**
 * Result of an {@code INTERVENE ON x = v RETURN …} statement.
 *
 * <p>v1 is purely structural — the model carries no parameters or data, so this
 * reports the <em>shape</em> of the interventional distribution rather than
 * numbers: whether {@code do(x)} reaches the target, the causal pathway, and the
 * name of the derived world that is now the active graph. {@link #note} records
 * that numeric values await a future parameter layer.
 *
 * @param intervenedVar   the variable set by the do-operator (x)
 * @param intervenedValue the value it was set to (v)
 * @param targetVar       the RETURN target (y)
 * @param returnType      DISTRIBUTION | EFFECT | EXPECTATION
 * @param affectsTarget   whether {@code do(x)} causally reaches y (y is a descendant of x)
 * @param pathway         mediators on the directed path(s) x → y (empty if unaffected)
 * @param derivedModel    name of the derived world now active (e.g. {@code Model_do_x_1})
 * @param note            structural caveat (null once numerics are present)
 * @param compareNote     non-null when COMPARE TO was given; describes the structural contrast
 * @param expectation     E[y | do(x=v)] — non-null only for a parameterized (linear-Gaussian) model
 * @param variance        Var(y | do(x=v)) — non-null only for a parameterized model
 * @param totalEffect     total causal effect coefficient of x on y — non-null only for a parameterized model
 * @param compareDelta    E[y|do(x=v)] − E[y|do(x=v₂)] — non-null only with COMPARE TO on a parameterized model
 */
public record InterventionResult(
    String intervenedVar,
    String intervenedValue,
    String targetVar,
    String returnType,
    boolean affectsTarget,
    Set<String> pathway,
    String derivedModel,
    String note,
    String compareNote,
    Double expectation,
    Double variance,
    Double totalEffect,
    Double compareDelta
) implements QueryResult {

    /** True when this carries numeric answers from a parameterized linear-Gaussian model. */
    public boolean isNumeric() {
        return expectation != null || totalEffect != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(returnType).append(" OF ").append(targetVar)
          .append(" under do(").append(intervenedVar).append('=').append(intervenedValue).append(")\n");
        sb.append("  active graph: ").append(derivedModel).append('\n');
        if (affectsTarget) {
            sb.append("  ").append(intervenedVar).append(" causally affects ").append(targetVar);
            if (!pathway.isEmpty()) sb.append(" via ").append(pathway);
            sb.append('\n');
        } else {
            sb.append("  no causal effect: P(").append(targetVar).append(" | do(")
              .append(intervenedVar).append(")) = P(").append(targetVar).append(")\n");
        }
        if (expectation != null) sb.append("  E[").append(targetVar).append(" | do] = ").append(expectation).append('\n');
        if (variance != null)    sb.append("  Var[").append(targetVar).append(" | do] = ").append(variance).append('\n');
        if (totalEffect != null) sb.append("  total effect ").append(intervenedVar).append("→").append(targetVar)
                                   .append(" = ").append(totalEffect).append('\n');
        if (compareDelta != null) sb.append("  contrast Δ = ").append(compareDelta).append('\n');
        if (compareNote != null) sb.append("  ").append(compareNote).append('\n');
        if (note != null) sb.append("  ").append(note);
        return sb.toString().stripTrailing();
    }
}
