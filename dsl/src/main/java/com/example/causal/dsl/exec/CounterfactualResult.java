package com.example.causal.dsl.exec;

/**
 * Result of a {@code COUNTERFACTUAL … WHAT WOULD <Y> HAVE BEEN IF <X> = x*}.
 *
 * <p>For a linear-Gaussian model the unit-level counterfactual is closed-form:
 * holding every exogenous noise term fixed at its factual value (abduction), then
 * setting {@code X = x*} (action) and re-propagating (prediction) collapses to
 *
 * <pre>  Y_cf = Y_factual + totalEffect(X→Y) · (x* − X_factual)</pre>
 *
 * because the system is linear in {@code X} given the noise. The confounders and
 * everything off the {@code X → Y} paths are baked into {@code Y_factual}.
 *
 * <p>Numeric fields are null when the model is not parameterized or the {@code GIVEN}
 * evidence is insufficient; {@link #note} then explains why.
 *
 * @param outcome           the queried outcome Y
 * @param interventionVar   the counterfactual variable X
 * @param interventionValue x* (the hypothetical value, as written)
 * @param factual           Y's factual value (from GIVEN)
 * @param counterfactual    Y_cf
 * @param difference        Y_cf − Y_factual (the unit-level effect)
 * @param note              explanation when no numeric answer is produced
 */
public record CounterfactualResult(
    String outcome,
    String interventionVar,
    String interventionValue,
    Double factual,
    Double counterfactual,
    Double difference,
    String note
) implements QueryResult {

    /** True when this carries a numeric counterfactual (parameterized model + sufficient evidence). */
    public boolean isNumeric() {
        return counterfactual != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("counterfactual: what would ").append(outcome)
          .append(" have been if ").append(interventionVar).append(" = ").append(interventionValue).append("?\n");
        if (isNumeric()) {
            sb.append("  factual ").append(outcome).append(" = ").append(factual).append('\n');
            sb.append("  counterfactual ").append(outcome).append(" = ").append(counterfactual).append('\n');
            sb.append("  difference = ").append(difference);
        } else {
            sb.append("  ").append(note);
        }
        return sb.toString();
    }
}
