package com.example.causal.runtime.compile;

import com.example.causal.runtime.algorithms.TopologicalSorter;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.*;

/**
 * The one construction path for a {@link CompiledCausalGraph}: maps, topological
 * sort, constructor (dense layout and closures). {@link GraphLoader} calls it after
 * reading Neo4j; the benchmark harness and tests call it on edge lists.
 */
public final class GraphBuilder {

    private GraphBuilder() {}

    /**
     * @param variables      variables in the order that defines dense indices
     * @param edges          {@code {from, to}} id pairs; endpoints must be in {@code variables}
     * @param weightsByChild optional β coefficients, child id → (parent id → β); may be empty
     * @throws IllegalArgumentException on an unknown endpoint
     * @throws IllegalStateException    if the edges contain a directed cycle
     */
    public static CompiledCausalGraph build(Collection<Variable> variables,
                                            Collection<String[]> edges,
                                            Map<String, Map<String, Double>> weightsByChild) {
        return build(variables, edges, weightsByChild, Map.of());
    }

    /** As above, plus each variable's Neo4j element id (GraphLoader), so procedures can return nodes directly. */
    public static CompiledCausalGraph build(Collection<Variable> variables,
                                            Collection<String[]> edges,
                                            Map<String, Map<String, Double>> weightsByChild,
                                            Map<String, String> elementIdById) {
        Map<String, Variable> variableById = new LinkedHashMap<>();
        Map<String, Set<String>> forwardAdj = new LinkedHashMap<>();
        Map<String, Set<String>> reverseAdj = new LinkedHashMap<>();
        for (Variable v : variables) {
            variableById.put(v.id(), v);
            forwardAdj.putIfAbsent(v.id(), new LinkedHashSet<>());
            reverseAdj.putIfAbsent(v.id(), new LinkedHashSet<>());
        }
        for (String[] e : edges) {
            if (!variableById.containsKey(e[0]) || !variableById.containsKey(e[1])) {
                throw new IllegalArgumentException("edge " + e[0] + " -> " + e[1] + " has an unknown endpoint");
            }
            forwardAdj.get(e[0]).add(e[1]);
            reverseAdj.get(e[1]).add(e[0]);
        }
        List<String> topoOrder = TopologicalSorter.sort(variableById.keySet(), forwardAdj);
        return new CompiledCausalGraph(variableById, forwardAdj, reverseAdj, topoOrder,
                weightsByChild, elementIdById, Instant.now());
    }
}
