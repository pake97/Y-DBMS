package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.Map;

/**
 * Plugin-side <strong>linear-Gaussian counterfactual inference</strong> over the
 * persisted, compiled causal graph.
 *
 * <h2>The math (Pearl's three steps, linear-Gaussian)</h2>
 * Given factual evidence and a hypothetical {@code do(X = x*)}:
 * <ol>
 *   <li><b>Abduction</b> — recover the exogenous noise from the factual world.</li>
 *   <li><b>Action</b> — graph surgery {@code do(X = x*)}.</li>
 *   <li><b>Prediction</b> — re-propagate holding the abducted noise fixed.</li>
 * </ol>
 * For a linear-Gaussian SEM this collapses to:
 * <pre>  Y_cf = Y_factual + totalEffect(X→Y) · (x* − X_factual)</pre>
 *
 * <h2>Reference oracle</h2>
 * {@code dsl/src/main/java/com/example/causal/dsl/exec/CounterfactualEvaluator.java}
 *
 * @see LinearGaussianInference the SEM engine this builds on
 */
public final class CounterfactualInference {

    private final CompiledCausalGraph graph;

    public CounterfactualInference(CompiledCausalGraph graph) {
        this.graph = graph;
    }

    /**
     * Counterfactual value of {@code outcome} under {@code do(interventionVar = counterfactualValue)},
     * given the factual values of the relevant variables.
     *
     * <p>Uses the linear-Gaussian identity:
     * {@code Y_cf = Y_factual + totalEffect(X→Y) · (x* − X_factual)}.
     *
     * @param interventionVar     the variable being hypothetically intervened on
     * @param counterfactualValue the hypothetical value x*
     * @param outcome             the variable whose counterfactual value is queried
     * @param factualValues       observed (factual) values keyed by variable id,
     *                            must include both {@code interventionVar} and {@code outcome}
     * @return the counterfactual value Y_cf
     * @throws IllegalArgumentException if factual values are missing for
     *                                  {@code interventionVar} or {@code outcome}
     */
    public double counterfactual(String interventionVar,
                                 double counterfactualValue,
                                 String outcome,
                                 Map<String, Double> factualValues) {
        if (!factualValues.containsKey(interventionVar)) {
            throw new IllegalArgumentException(
                    "Factual value required for intervention variable: " + interventionVar);
        }
        if (!factualValues.containsKey(outcome)) {
            throw new IllegalArgumentException(
                    "Factual value required for outcome variable: " + outcome);
        }

        double xFactual = factualValues.get(interventionVar);
        double yFactual = factualValues.get(outcome);

        double effect = new LinearGaussianInference(graph).totalEffect(interventionVar, outcome);

        return yFactual + effect * (counterfactualValue - xFactual);
    }
}
