package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.*;

/**
 * Ancestor, descendant, and reachability queries over a compiled graph.
 *
 * <p>Three branches, chosen once in the constructor:
 * <ul>
 *   <li><b>closures</b> (default): read the ancestor/descendant bitsets that
 *       {@link CompiledCausalGraph} precomputes; a lookup costs O(n/64) plus the
 *       size of the answer.</li>
 *   <li><b>layer-pruned</b>: the snapshot skipped its closures
 *       ({@code -Dcausal.closures.budget}); DFS over the CSR arrays.
 *       {@code reaches(u, v)} abandons any node whose layer is not below
 *       {@code layer(v)}. For ancestors/descendants the layer test never prunes
 *       (parents always have a smaller layer), so those are plain int-array DFS.</li>
 *   <li><b>DFS baseline</b> ({@code -Dcausal.index=off}): the {@code *Dfs}
 *       reference methods over the id maps, for the evaluation's un-indexed
 *       baseline.</li>
 * </ul>
 * The closure and layer-pruned branches return ids in ascending dense-index order.
 */
public final class ReachabilityIndex {

    private final CompiledCausalGraph graph;
    private final boolean dfsBaseline;
    private long visited; // diagnostic: nodes expanded by the DFS branches

    public ReachabilityIndex(CompiledCausalGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
        this.dfsBaseline = "off".equals(System.getProperty("causal.index"));
    }

    /** Nodes expanded by DFS-based answers since construction (not thread-safe; diagnostics only). */
    public long visitedNodes() {
        return visited;
    }

    /**
     * All ancestors of {@code id} (transitive parents), excluding {@code id} itself.
     * Returns an empty set if {@code id} is unknown or has no parents.
     */
    public Set<String> ancestors(String id) {
        if (dfsBaseline) return ancestorsDfs(id);
        int i = graph.indexOf(id);
        if (i < 0) return new LinkedHashSet<>();
        return graph.hasClosures() ? graph.toIds(graph.ancestorBits(i)) : walk(i, false);
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
        if (dfsBaseline) return descendantsDfs(id);
        int i = graph.indexOf(id);
        if (i < 0) return new LinkedHashSet<>();
        return graph.hasClosures() ? graph.toIds(graph.descendantBits(i)) : walk(i, true);
    }

    /** True if there is a directed path {@code from → … → to}. */
    public boolean hasDirectedPath(String from, String to) {
        if (dfsBaseline) return descendantsDfs(from).contains(to);
        int f = graph.indexOf(from), t = graph.indexOf(to);
        if (f < 0 || t < 0) return false;
        return graph.hasClosures() ? graph.reaches(f, t) : reachesPruned(f, t);
    }

    /** Layer-pruned reachability: only nodes with layer < layer(to) can lie strictly inside a path to {@code to}. */
    public boolean reachesPruned(int from, int to) {
        int target = graph.layer(to);
        if (graph.layer(from) >= target) return false;
        boolean[] seen = new boolean[graph.size()];
        int[] stack = new int[graph.size()];
        int top = 0;
        stack[top++] = from;
        seen[from] = true;
        while (top > 0) {
            int u = stack[--top];
            visited++;
            for (int k = 0, m = graph.childCount(u); k < m; k++) {
                int c = graph.child(u, k);
                if (c == to) return true;
                if (!seen[c] && graph.layer(c) < target) {
                    seen[c] = true;
                    stack[top++] = c;
                }
            }
        }
        return false;
    }

    /** DFS over the CSR arrays from {@code start}, excluding it; ids in ascending dense-index order. */
    private Set<String> walk(int start, boolean down) {
        boolean[] seen = new boolean[graph.size()];
        int[] stack = new int[graph.size()];
        int top = 0, found = 0;
        int[] out = new int[graph.size()];
        stack[top++] = start;
        while (top > 0) {
            int u = stack[--top];
            visited++;
            for (int k = 0, m = down ? graph.childCount(u) : graph.parentCount(u); k < m; k++) {
                int w = down ? graph.child(u, k) : graph.parent(u, k);
                if (!seen[w]) {
                    seen[w] = true;
                    out[found++] = w;
                    stack[top++] = w;
                }
            }
        }
        Arrays.sort(out, 0, found);
        Set<String> ids = new LinkedHashSet<>();
        for (int k = 0; k < found; k++) ids.add(graph.idOf(out[k]));
        return ids;
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
                this.visited++;
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
                this.visited++;
                stack.addAll(graph.childrenOf(current));
            }
        }
        return visited;
    }
}
