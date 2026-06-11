package com.example.causal.dto;

import java.util.Map;

/**
 * Cypher output row for {@code causal.linearGaussianInference()}.
 *
 * <p>Usage from Cypher:
 * <pre>
 *   CALL causal.linearGaussianInference(treatment, outcome, {X: 3.0})
 *   YIELD means, variances, totalEffect
 * </pre>
 */
public class LinearGaussianRecord {
    public final Map<String, Double> means;
    public final Map<String, Double> variances;
    public final Double totalEffect;

    public LinearGaussianRecord(Map<String, Double> means,
                                Map<String, Double> variances,
                                Double totalEffect) {
        this.means = means;
        this.variances = variances;
        this.totalEffect = totalEffect;
    }
}
