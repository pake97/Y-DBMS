package com.example.causal.api;

import org.neo4j.graphdb.Transaction;

import java.util.Objects;

/**
 * Options passed to {@link CausalGraphRuntime#compile}.
 *
 * <p>Wraps the active Neo4j {@link Transaction} so that the runtime can read
 * the graph without holding a long-lived database reference. The transaction
 * is owned by the calling procedure and must not be closed here.
 *
 * <p>Extend this class with compile-time flags as the project grows
 * (e.g. filtering by subgraph, excluding latent variables, etc.).
 */
public final class GraphCompileOptions {

    private final Transaction transaction;

    public GraphCompileOptions(Transaction transaction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    /** The active Neo4j transaction to use for reading graph data. */
    public Transaction transaction() {
        return transaction;
    }
}
