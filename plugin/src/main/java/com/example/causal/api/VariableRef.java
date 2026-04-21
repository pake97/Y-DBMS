package com.example.causal.api;

/**
 * Immutable reference to a causal variable, identified by its string key.
 *
 * <p>The {@code id} corresponds to the {@code id} property on a {@code :Variable}
 * node in Neo4j. All cross-layer communication uses {@code VariableRef} rather than
 * raw strings so that the compiler enforces type safety at API boundaries.
 *
 * <p><strong>DSL contract:</strong> the DSL/planner layer creates and consumes
 * {@code VariableRef} instances only through {@link CausalGraphRuntime#resolveVariable}.
 */
public record VariableRef(String id) {

    public VariableRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("VariableRef id must not be blank");
        }
    }

    @Override
    public String toString() {
        return "VariableRef(" + id + ")";
    }
}
