package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.*;

/**
 * Ancestor, descendant, and reachability queries over a compiled graph.
 *
 * <p>The default methods read the ancestor/descendant closures that
 * {@link CompiledCausalGraph} precomputes at compile time: a lookup costs
 * O(n/64) plus the size of the answer, and results come back in ascending
 * dense-index order. The {@code *Dfs} methods keep the original traversal
 * as the reference implementation.
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
        int i = graph.indexOf(id);
        return i < 0 ? new LinkedHashSet<>() : graph.toIds(graph.ancestorBits(i));
    }

    /** {@link #ancestors(String)} through the index, or {@link #ancestorsDfs} when {@code useIndex} is false. */
    public Set<String> ancestors(String id, boolean useIndex) {
        return useIndex ? ancestors(id) : ancestorsDfs(id);
    }

    /**
     * All descendants of {@code id} (transitive children), excluding {@code id} itself.
     * Returns an empty set if {@code id} is unknown or has no children.
     */
    public Set<String> descendants(String id) {
        int i = graph.indexOf(id);
        return i < 0 ? new LinkedHashSet<>() : graph.toIds(graph.descendantBits(i));
    }

    /** True if there is a directed path {@code from → … → to}. */
    public boolean hasDirectedPath(String from, String to) {
        int f = graph.indexOf(from), t = graph.indexOf(to);
        return f >= 0 && t >= 0 && graph.reaches(f, t);
    }

    /**
     * Ancestors by iterative DFS over the parent maps, O(V + E) per query.
     * Reference implementation; used by the parity test and by the DFS
     * baseline of the evaluation (CODE-PLAN.md WP5). Do not delete.
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
     * Descendants by iterative DFS over the child maps, O(V + E) per query.
     * Reference implementation; used by the parity test and by the DFS
     * baseline of the evaluation (CODE-PLAN.md WP5). Do not delete.
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
}
