package com.example.causal.runtime.model;

/**
 * Internal representation of a causal variable loaded from a Neo4j {@code :Variable} node.
 *
 * <p>This is a private runtime type. The DSL layer sees only {@link com.example.causal.api.VariableRef}.
 *
 * <h3>Neo4j property mapping</h3>
 * <pre>
 *   Node label  : Variable
 *   id          → id       (required, unique)
 *   name        → name     (defaults to id if absent)
 *   observed    → observed (default true)
 *   latent      → latent   (default false)
 * </pre>
 */
public record Variable(
        String id,
        String name,
        boolean observed,
        boolean latent
) {
    public Variable {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Variable id must not be blank");
        }
    }

    public static Variable from(String id, String name, boolean observed, boolean latent) {
        return new Variable(id, name != null ? name : id, observed, latent);
    }
}
