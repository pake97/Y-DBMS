package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.*;

/**
 * Provides ancestor, descendant, and reachability queries over a compiled graph.
 *
 * <h2>Current implementation</h2>
 * Uses iterative DFS for correctness. Per-query cost is O(V + E).
 *
 * <h2>TODO(student): Bitset acceleration plan</h2>
 * <p>Once {@link CompiledCausalGraph} uses a dense integer layout:
 * <ol>
 *   <li>Assign each variable a dense index 0..n-1 (already prepared in the
 *       {@code nameToIndex} map planned in {@code CompiledCausalGraph}).</li>
 *   <li>Represent ancestor sets as {@code long[ceil(n/64)]} bitsets.</li>
 *   <li>Pre-compute {@code ancestorBits[i]} for every variable in
 *       reverse-topological order:
 *       <pre>
 *         for each node i in reverse-topo order:
 *           ancestorBits[i] = union of (ancestorBits[p] | setBit(p)) for each parent p
 *       </pre>
 *   </li>
 *   <li>Replace DFS in {@link #ancestors} with a single bitset lookup.</li>
 *   <li>For d-separation (Bayes Ball), represent conditioning sets and
 *       reachable sets as bitsets to enable O(n/64) intersection tests.</li>
 * </ol>
 */
public final class ReachabilityIndex {

    private final CompiledCausalGraph graph;

    public ReachabilityIndex(CompiledCausalGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
    }

    /**
     * All ancestors of {@code id} (transitive parents), excluding {@code id} itself.
     * Returns an empty set if {@code id} is unknown or has no parents.
     */
    public Set<String> ancestors(String id) {
        // TODO(student): replace with O(1) bitset lookup after pre-computation
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(graph.parentsOf(id));
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (visited.add(current)) {
                stack.addAll(graph.parentsOf(current));
            }
        }
        return visited;
    }

    /**
     * All descendants of {@code id} (transitive children), excluding {@code id} itself.
     * Returns an empty set if {@code id} is unknown or has no children.
     */
    public Set<String> descendants(String id) {
        // TODO(student): replace with O(1) bitset lookup after pre-computation
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(graph.childrenOf(id));
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (visited.add(current)) {
                stack.addAll(graph.childrenOf(current));
            }
        }
        return visited;
    }

    /**
     * True if there is a directed path {@code from → … → to}.
     */
    public boolean hasDirectedPath(String from, String to) {
        return descendants(from).contains(to);
    }
}
