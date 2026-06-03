package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.Map;

/**
 * Plugin-side <strong>linear-Gaussian counterfactual inference</strong> over the
 * persisted, compiled causal graph.
 *
 * <h2>Status: Tier 2 student exercise — NOT yet implemented</h2>
 * The method below throws {@link UnsupportedOperationException}. This class has no
 * callers yet; it marks where the plugin's counterfactual engine goes, so the
 * DSL's {@code Neo4jQueryExecutor} can answer {@code COUNTERFACTUAL} against the
 * live graph instead of only the in-memory path.
 *
 * <h2>Reference oracle</h2>
 * The DSL ships a complete, tested implementation:
 * {@code dsl/src/main/java/com/example/causal/dsl/exec/CounterfactualEvaluator.java}
 * (with cases in {@code CausalDSLTest}). Match its numbers.
 *
 * <h2>The math (Pearl's three steps, linear-Gaussian)</h2>
 * Given factual evidence and a hypothetical {@code do(X = x*)}:
 * <ol>
 *   <li><b>Abduction</b> — recover the exogenous noise from the factual world.</li>
 *   <li><b>Action</b> — graph surgery {@code do(X = x*)} (reuse the structural surgery).</li>
 *   <li><b>Prediction</b> — re-propagate holding the abducted noise fixed.</li>
 * </ol>
 * For a linear-Gaussian SEM this collapses to a one-liner — holding the noise
 * fixed, {@code Y} is affine in {@code X}:
 * <pre>  Y_cf = Y_factual + totalEffect(X→Y) · (x* − X_factual)</pre>
 * so it reuses {@link LinearGaussianInference#totalEffect} once that lands. Depends
 * on the same parameter schema/loader work described in {@link LinearGaussianInference}.
 *
 * @see LinearGaussianInference the SEM engine this builds on
 * @see PathAnalyzer the completed Tier 1 worked example to model this on
 */
public final class CounterfactualInference {

    private final CompiledCausalGraph graph;

    public CounterfactualInference(CompiledCausalGraph graph) {
        this.graph = graph;
    }

    /**
     * Counterfactual value of {@code outcome} under {@code do(interventionVar = counterfactualValue)},
     * given the factual values of the relevant variables.
     * TODO(student, tier 2): implement — see the linear-Gaussian one-liner above
     * and the {@code CounterfactualEvaluator} oracle in the DSL module.
     *
     * @param factualValues observed (factual) values keyed by variable id, incl. the outcome and X
     */
    public double counterfactual(String interventionVar,
                                 double counterfactualValue,
                                 String outcome,
                                 Map<String, Double> factualValues) {
        throw new UnsupportedOperationException(
            "TODO(student, tier 2): linear-Gaussian counterfactual — see dsl CounterfactualEvaluator oracle");
    }

    @SuppressWarnings("unused") // used once implemented
    private CompiledCausalGraph graph() {
        return graph;
    }
}
