package com.example.causal.dto;

/**
 * Record returned by the {@code causal.counterfactual} procedure.
 */
public class CounterfactualRecord {

    public final Double factual;
    public final Double counterfactual;
    public final Double difference;

    public CounterfactualRecord(Double factual, Double counterfactual, Double difference) {
        this.factual = factual;
        this.counterfactual = counterfactual;
        this.difference = difference;
    }
}
