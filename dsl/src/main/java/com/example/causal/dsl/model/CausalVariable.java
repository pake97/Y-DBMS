package com.example.causal.dsl.model;

import com.example.causal.dsl.ast.ObservationBinding;

/**
 * A semantic variable in the causal DAG.
 * Binding is null for latent variables or when no OBSERVED AS clause was given.
 */
public record CausalVariable(
    String name,
    String unit,
    ObservationBinding binding,
    VariableType type,
    VariableRole role
) {
    public boolean isLatent() {
        return type == VariableType.LATENT || binding == null;
    }
}
