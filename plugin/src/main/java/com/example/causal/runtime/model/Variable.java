package com.example.causal.runtime.model;

/**
 * Internal representation of a causal variable loaded from a Neo4j {@code :Variable} node.
 *
 * <p>This is a private runtime type. The DSL layer sees only {@link com.example.causal.api.VariableRef}.
 *
 * <h3>Neo4j property mapping</h3>
 * <pre>
 *   Node label  : Variable
 *   id          → id            (required, unique)
 *   name        → name          (defaults to id if absent)
 *   observed    → observed      (default true)
 *   latent      → latent        (default false)
 *   intercept   → intercept     (default 0.0) — structural-equation constant μ₀
 *   noise       → noiseVariance (default 1.0) — exogenous noise variance σ²
 * </pre>
 */
public record Variable(
        String id,
        String name,
        boolean observed,
        boolean latent,
        double intercept,
        double noiseVariance
) {
    /** Default observed flag when not declared in Neo4j. */
    public static final boolean DEFAULT_OBSERVED = true;
    /** Default latent flag when not declared in Neo4j. */
    public static final boolean DEFAULT_LATENT = false;
    /** Default edge weight (β coefficient) when not declared in Neo4j. */
    public static final double DEFAULT_WEIGHT = 1.0;
    /** Default intercept when not declared in Neo4j. */
    public static final double DEFAULT_INTERCEPT = 0.0;
    /** Default noise variance when not declared in Neo4j. */
    public static final double DEFAULT_NOISE_VARIANCE = 1.0;

    public Variable {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Variable id must not be blank");
        }
    }

    /** Full factory including linear-Gaussian SEM parameters. */
    public static Variable from(String id, String name, boolean observed, boolean latent,
                                double intercept, double noiseVariance) {
        return new Variable(id, name != null ? name : id, observed, latent,
                intercept, noiseVariance);
    }
}
