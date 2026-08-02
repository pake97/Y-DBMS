package com.example.causal.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.example.causal.runtime.model.Variable;

/**
 * A description of the causal-schema changes observed inside a single Neo4j
 * transaction. Produced in
 * {@link CausalGraphChangeListener#beforeCommit} and consumed in
 * {@link CausalGraphChangeListener#afterCommit} — this is the context the
 * student uses to patch the compiled snapshot incrementally.
 *
 * <p>The fields below are intentionally minimal. Extend as the incremental
 * maintenance strategy gets richer (property flips on {@code active},
 * {@code latent}, etc.).
 *
 * @param addedVariables   ids of newly created {@code :Variable} nodes
 * @param removedVariables ids of deleted {@code :Variable} nodes
 * @param addedEdges       directed edges {@code (from, to)} created with
 *                         relationship type {@code :CAUSES}
 * @param removedEdges     directed edges removed
 */
public record ChangeBatch(
        List<Variable> addedVariables,
        List<String> removedVariables,
        List<Edge>   addedEdges,
        List<Edge>   removedEdges,
        List<Variable> updatedVariables
) {

    /** A canonical empty batch — use for transactions that don't touch the causal schema. */
    public static final ChangeBatch EMPTY = new ChangeBatch(List.of(), List.of(), List.of(), List.of(), List.of());

    /** A directed edge identified by endpoint {@code id} properties. */
    public record Edge(String from, String to) {}

    /** True if there is nothing for {@link CausalGraphChangeListener#afterCommit} to apply. */
    public boolean isEmpty() {
        return addedVariables.isEmpty()
            && removedVariables.isEmpty()
            && addedEdges.isEmpty()
            && removedEdges.isEmpty()
            && updatedVariables.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Variable> addedVariables   = new ArrayList<>();
        private final List<String> removedVariables = new ArrayList<>();
        private final List<Edge>   addedEdges       = new ArrayList<>();
        private final List<Edge>   removedEdges     = new ArrayList<>();
        private final List<Variable> updatedVariables = new ArrayList<>();

        public Builder addedVariable(Variable v)             { addedVariables.add(v); return this; }
        public Builder removedVariable(String id)            { removedVariables.add(id); return this; }
        public Builder addedEdge(String from, String to)     { addedEdges.add(new Edge(from, to)); return this; }
        public Builder removedEdge(String from, String to)   { removedEdges.add(new Edge(from, to)); return this; }
        public Builder updatedVariable(Variable v)           { updatedVariables.add(v); return this; }

        public ChangeBatch build() {
            return new ChangeBatch(
                Collections.unmodifiableList(addedVariables),
                Collections.unmodifiableList(removedVariables),
                Collections.unmodifiableList(addedEdges),
                Collections.unmodifiableList(removedEdges),
                Collections.unmodifiableList(updatedVariables)
            );
        }
    }
}
