package com.example.causal.api;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable snapshot of the compiled causal graph.
 *
 * <p>Returned by {@link CausalGraphRuntime#compile} and
 * {@link CausalGraphRuntime#currentSnapshot}. The snapshot is produced once
 * by reading Neo4j and remains valid until {@link CausalGraphRuntime#invalidate}
 * is called.
 *
 * <p>The runtime implementation ({@code CompiledCausalGraph}) implements this
 * interface. The DSL layer should depend only on this interface, never on the
 * concrete runtime class.
 */
public interface GraphSnapshot {

    /** All variables present in the compiled graph. */
    Set<VariableRef> variables();

    /** Number of variables. */
    int size();

    /** True if this snapshot contains the given variable. */
    boolean contains(VariableRef ref);

    /** Timestamp at which this snapshot was compiled from Neo4j. */
    Instant compiledAt();

    /** Convenience: true when the snapshot contains no variables. */
    default boolean isEmpty() {
        return size() == 0;
    }
}
