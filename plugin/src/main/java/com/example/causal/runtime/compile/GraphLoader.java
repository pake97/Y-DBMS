package com.example.causal.runtime.compile;

import com.example.causal.api.GraphCompileOptions;
import com.example.causal.runtime.algorithms.TopologicalSorter;
import com.example.causal.runtime.model.Variable;
import org.neo4j.graphdb.*;

import java.time.Instant;
import java.util.*;

/**
 * Reads a causal DAG from Neo4j and produces an immutable {@link CompiledCausalGraph}.
 *
 * <h2>Expected Neo4j schema</h2>
 * <pre>
 *   (:Variable {id: String, name: String, observed: Boolean, latent: Boolean})
 *   -[:CAUSES {active: Boolean}]->
 *   (:Variable {...})
 * </pre>
 *
 * <p>Only {@code :CAUSES} relationships where {@code active = true} are included.
 * The property defaults are: {@code name} → {@code id}, {@code observed} → true,
 * {@code latent} → false, {@code active} → true.
 *
 * <p>This class is stateless; all state lives in the returned {@link CompiledCausalGraph}.
 */
public final class GraphLoader {

    /** Neo4j node label for causal variables. */
    public static final Label VARIABLE_LABEL = Label.label("Variable");

    private GraphLoader() {}

    /**
     * Load and compile the causal graph from Neo4j within the transaction
     * supplied by {@code options}.
     *
     * @param options compile options — must contain an active read transaction
     * @return an immutable compiled graph, never null
     * @throws IllegalStateException if the graph contains a directed cycle
     */
    public static CompiledCausalGraph load(GraphCompileOptions options) {
        Transaction tx = options.transaction();

        Map<String, Variable> variableById = new LinkedHashMap<>();
        Map<String, Set<String>> forwardAdj = new LinkedHashMap<>();
        Map<String, Set<String>> reverseAdj = new LinkedHashMap<>();

        // --- Pass 1: load all Variable nodes ---
        try (ResourceIterator<Node> nodes = tx.findNodes(VARIABLE_LABEL)) {
            while (nodes.hasNext()) {
                Node node = nodes.next();
                String id = (String) node.getProperty("id");
                String name = (String) node.getProperty("name", id);
                boolean observed = (boolean) node.getProperty("observed", true);
                boolean latent   = (boolean) node.getProperty("latent", false);
                double intercept = ((Number) node.getProperty("intercept",
                        Variable.DEFAULT_INTERCEPT)).doubleValue();
                double noise     = ((Number) node.getProperty("noise",
                        Variable.DEFAULT_NOISE_VARIANCE)).doubleValue();

                variableById.put(id, Variable.from(id, name, observed, latent, intercept, noise));
                forwardAdj.putIfAbsent(id, new LinkedHashSet<>());
                reverseAdj.putIfAbsent(id, new LinkedHashSet<>());
            }
        }

        // --- Pass 2: load active CAUSES relationships via Cypher ---
        // Using Cypher here is intentional: it avoids the need to iterate all
        // relationships and lets Neo4j filter on the 'active' property efficiently.
        // Also reads the optional 'weight' property (β coefficient) for the SEM.
        Map<String, Map<String, Double>> edgeWeights = new LinkedHashMap<>();
        Result relResult = tx.execute(
                "MATCH (a:Variable)-[r:CAUSES]->(b:Variable) " +
                "WHERE coalesce(r.active, true) = true " +
                "RETURN a.id AS fromId, b.id AS toId, coalesce(r.weight, " + Variable.DEFAULT_WEIGHT + ") AS weight"
        );
        while (relResult.hasNext()) {
            Map<String, Object> row = relResult.next();
            String fromId = (String) row.get("fromId");
            String toId   = (String) row.get("toId");

            if (variableById.containsKey(fromId) && variableById.containsKey(toId)) {
                double weight = ((Number) row.get("weight")).doubleValue();
                forwardAdj.computeIfAbsent(fromId, k -> new LinkedHashSet<>()).add(toId);
                reverseAdj.computeIfAbsent(toId,   k -> new LinkedHashSet<>()).add(fromId);
                edgeWeights.computeIfAbsent(toId,  k -> new LinkedHashMap<>()).put(fromId, weight);
            }
        }

        List<String> topoOrder = TopologicalSorter.sort(variableById.keySet(), forwardAdj);

        return new CompiledCausalGraph(
                variableById, forwardAdj, reverseAdj, topoOrder, edgeWeights, Instant.now()
        );
    }
}
