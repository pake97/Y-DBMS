package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.*;

/**
 * Provides ancestor, descendant, and reachability queries over a compiled graph.
 *
 * <h2>Current implementation</h2>
 * Uses iterative DFS for correctness. Per-query cost is O(V + E).
 *
 * <h2>TODO(student, tier 2): Bitset acceleration</h2>
 * This is the same bitset work listed in
 * {@link CompiledCausalGraph}'s Tier 2 plan — that class owns the storage,
 * this class reads it. Once the dense layout lands:
 * <ol>
 *   <li>Assign each variable a dense index 0..n-1 (via the {@code nameToIndex}
 *       map planned in {@code CompiledCausalGraph}).</li>
 *   <li>Represent ancestor sets as {@code long[ceil(n/64)]} bitsets.</li>
 *   <li>Pre-compute {@code ancestorBits[i]} for every variable in
 *       reverse-topological order (store on {@code CompiledCausalGraph},
 *       not here):
 *       <pre>
 *         for each node i in reverse-topo order:
 *           ancestorBits[i] = union of (ancestorBits[p] | setBit(p)) for each parent p
 *       </pre>
 *   </li>
 *   <li>Replace the DFS in {@link #ancestors} with an O(n/64) bitset lookup.</li>
 *   <li>For d-separation (Bayes Ball), represent conditioning sets and
 *       reachable sets as bitsets to enable O(n/64) intersection tests.</li>
 * </ol>
 *
 * <p><b>Not required for Tier 1.</b> The path queries in
 * {@link PathAnalyzer} and the d-separation / backdoor work in
 * {@code DefaultCausalGraphRuntime} can both be written against today's
 * DFS-based {@link #ancestors} / {@link #descendants} — they stay correct
 * when the storage switches to bitsets.
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
        int index = graph.topoIndex(id);
        if (index < 0) return Collections.emptySet();
        long[] bits = graph.getAncestorBits(index);

        Set<String> result = new LinkedHashSet<>();
        for (int w = 0; w < bits.length; w++) {
            long word = bits[w];
            while (word != 0) {
                int bit = Long.numberOfTrailingZeros(word);
                result.add(graph.idForIndex(w * 64 + bit));
                word &= ~(1L << bit);
            }
        }
        return result;
    }

    /**
     * DFS baseline implementation of ancestors, preserved for parity testing.
     */
    public Set<String> ancestorsDfs(String id) {
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
        int index = graph.topoIndex(id);
        if (index < 0) return Collections.emptySet();
        long[] bits = graph.getDescendantBits(index);

        Set<String> result = new LinkedHashSet<>();
        for (int w = 0; w < bits.length; w++) {
            long word = bits[w];
            while (word != 0) {
                int bit = Long.numberOfTrailingZeros(word);
                result.add(graph.idForIndex(w * 64 + bit));
                word &= ~(1L << bit);
            }
        }
        return result;
    }

    /**
     * DFS baseline implementation of descendants, preserved for parity testing.
     */
    public Set<String> descendantsDfs(String id) {
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
     * Executes in O(1) time by directly querying the compiled reachability bitset.
     */
    public boolean hasDirectedPath(String from, String to) {
        int u = graph.topoIndex(from);
        int v = graph.topoIndex(to);
        if (u == -1 || v == -1) return false;
        return graph.reaches(u, v);
    }
}
