package com.example.causal.dsl.exec;

import java.util.List;

/**
 * Result of {@code ESTIMATE EFFECT OF <T> ON <O>}.
 *
 * <p>Per the DB-not-ML boundary, we do not fit estimators from data. When the
 * model is a parameterized linear-Gaussian SEM, {@link #effect} is the
 * **SEM-implied** total effect (arithmetic over the *given* coefficients). For a
 * structural-only model, {@link #effect} is null and {@link #note} explains that a
 * numeric estimate must be supplied externally.
 *
 * @param treatment     T
 * @param outcome       O
 * @param adjustmentSet the (validated) ADJUST FOR set, as written
 * @param estimator     the USING estimator id (defaulted)
 * @param effect        SEM-implied total effect of T on O, or null if unparameterized
 * @param note          explanation of what the effect is / why it is absent
 */
public record EstimateResult(
    String treatment,
    String outcome,
    List<String> adjustmentSet,
    String estimator,
    Double effect,
    String note
) implements QueryResult {

    public boolean isNumeric() {
        return effect != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ESTIMATE EFFECT OF ")
            .append(treatment).append(" ON ").append(outcome).append('\n');
        sb.append("  adjust for: ")
          .append(adjustmentSet.isEmpty() ? "(none)" : String.join(", ", adjustmentSet)).append('\n');
        sb.append("  estimator:  ").append(estimator).append('\n');
        if (effect != null) sb.append("  effect:     ").append(effect).append('\n');
        sb.append("  ").append(note);
        return sb.toString();
    }
}
