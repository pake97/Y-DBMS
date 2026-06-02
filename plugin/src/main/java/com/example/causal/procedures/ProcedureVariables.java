package com.example.causal.procedures;

import com.example.causal.api.CausalGraphRuntime;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.compile.GraphLoader;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Maps between Neo4j {@link Node} entities and {@link VariableRef} at the
 * procedure boundary. The compiled runtime stays keyed by variable {@code id}.
 */
public final class ProcedureVariables {

    private ProcedureVariables() {}

    public static Set<VariableRef> resolveAll(CausalGraphRuntime runtime, List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return Set.of();
        Set<VariableRef> result = new LinkedHashSet<>();
        for (Node node : nodes) {
            resolve(runtime, node).ifPresent(result::add);
        }
        return result;
    }

    public static Optional<VariableRef> resolve(CausalGraphRuntime runtime, Node node) {
        return runtime.resolveVariable(requireVariableId(node));
    }

    public static VariableRef requireResolved(CausalGraphRuntime runtime, Node node, String role) {
        String id = requireVariableId(node);
        return runtime.resolveVariable(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown variable " + role + ": " + id));
    }

    public static String requireVariableId(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("Variable node must not be null");
        }
        if (!node.hasLabel(GraphLoader.VARIABLE_LABEL)) {
            throw new IllegalArgumentException(
                    "Expected :Variable node, got: " + node.getLabels());
        }
        if (!node.hasProperty("id")) {
            throw new IllegalArgumentException(":Variable node missing required 'id' property");
        }
        Object id = node.getProperty("id");
        if (!(id instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(":Variable 'id' must be a non-blank string");
        }
        return s;
    }

    /** Re-bind a snapshot variable id to a {@code :Variable} node in the active transaction. */
    public static Node lookup(Transaction tx, String id) {
        try (ResourceIterator<Node> it = tx.findNodes(GraphLoader.VARIABLE_LABEL, "id", id)) {
            if (it.hasNext()) return it.next();
        }
        throw new IllegalArgumentException("No :Variable node with id: " + id);
    }
}
