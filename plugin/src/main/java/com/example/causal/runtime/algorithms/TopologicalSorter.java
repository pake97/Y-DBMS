package com.example.causal.runtime.algorithms;

import java.util.*;

/**
 * Topological sort of a directed acyclic graph using Kahn's algorithm.
 *
 * <p>Input and output use string variable ids. Once Student A introduces the
 * dense integer layout in {@link com.example.causal.runtime.compile.CompiledCausalGraph},
 * this class should be updated to operate on {@code int[]} arrays directly,
 * eliminating hashing overhead.
 */
public final class TopologicalSorter {

    private TopologicalSorter() {}

    /**
     * Topologically sort the given variables.
     *
     * @param nodes      all variable ids in the graph
     * @param forwardAdj adjacency map: variable id → set of direct child ids
     * @return list of variable ids in topological order (sources first)
     * @throws IllegalStateException if the graph is not a DAG (contains a cycle)
     */
    public static List<String> sort(Set<String> nodes, Map<String, Set<String>> forwardAdj) {
        // Compute in-degree for each node
        Map<String, Integer> inDegree = new HashMap<>(nodes.size() * 2);
        for (String n : nodes) inDegree.put(n, 0);
        for (Map.Entry<String, Set<String>> e : forwardAdj.entrySet()) {
            for (String child : e.getValue()) {
                inDegree.merge(child, 1, Integer::sum);
            }
        }

        // Enqueue all sources (in-degree 0)
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        List<String> order = new ArrayList<>(nodes.size());
        while (!queue.isEmpty()) {
            String node = queue.poll();
            order.add(node);
            for (String child : forwardAdj.getOrDefault(node, Set.of())) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) {
                    queue.add(child);
                }
            }
        }

        if (order.size() != nodes.size()) {
            throw new IllegalStateException(
                    "Cycle detected in causal graph — the graph must be a DAG. " +
                    "Processed " + order.size() + " of " + nodes.size() + " variables.");
        }

        return order;
    }
}
