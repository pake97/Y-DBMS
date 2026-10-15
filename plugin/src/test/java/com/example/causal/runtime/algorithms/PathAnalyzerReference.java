package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reference implementation of {@link PathAnalyzer} on id sets and per-query DFS
 * ({@link ReachabilityIndex#ancestorsDfs} / {@link ReachabilityIndex#descendantsDfs}): the
 * pre-WP2 bodies, kept for the parity tests and as the DFS baseline of the evaluation.
 * {@link #backdoorVariables} uses the same barrier as {@link PathAnalyzer} (the generalized
 * criterion's {@code forb(t,y) \ {y}}), so both compute the same operator.
 */
public final class PathAnalyzerReference {

    private final CompiledCausalGraph graph;
    private final ReachabilityIndex reachability;

    public PathAnalyzerReference(CompiledCausalGraph graph) {
        this.graph = graph;
        this.reachability = new ReachabilityIndex(graph);
    }

    public Set<String> mediators(String from, String to) {
        Set<String> result = new LinkedHashSet<>(reachability.descendantsDfs(from));
        result.retainAll(reachability.ancestorsDfs(to));
        return result;
    }

    public Set<String> colliders(String x, String y) {
        Set<String> reachableFromX = new LinkedHashSet<>(reachability.descendantsDfs(x));
        reachableFromX.add(x);
        Set<String> reachableFromY = new LinkedHashSet<>(reachability.descendantsDfs(y));
        reachableFromY.add(y);
        Set<String> ancestorsOfX = reachability.ancestorsDfs(x);
        Set<String> ancestorsOfY = reachability.ancestorsDfs(y);

        Set<String> result = new LinkedHashSet<>();
        for (String z : graph.topoOrder()) {
            if (z.equals(x) || z.equals(y)) continue;
            if (ancestorsOfX.contains(z) || ancestorsOfY.contains(z)) continue;
            Set<String> parentsOfZ = graph.parentsOf(z);
            boolean hasXSideParent = parentsOfZ.stream().anyMatch(reachableFromX::contains);
            boolean hasYSideParent = parentsOfZ.stream().anyMatch(reachableFromY::contains);
            if (hasXSideParent && hasYSideParent) result.add(z);
        }
        return result;
    }

    public Set<String> confounders(String x, String y) {
        Set<String> commonAncestors = new LinkedHashSet<>(reachability.ancestorsDfs(x));
        commonAncestors.retainAll(reachability.ancestorsDfs(y));
        commonAncestors.remove(x);
        commonAncestors.remove(y);
        return commonAncestors;
    }

    /** Barrier {@code forb(t,y) \ {y}}: {t}, the causal nodes and their descendants. */
    public Set<String> backdoorVariables(String treatment, String outcome) {
        Set<String> forbidden = forbidden(treatment, outcome);
        forbidden.remove(outcome);

        Set<String> fromParents = undirectedReachable(graph.parentsOf(treatment), forbidden);
        Set<String> fromOutcome = undirectedReachable(Set.of(outcome), forbidden);

        fromParents.retainAll(fromOutcome);
        fromParents.remove(outcome);
        return fromParents;
    }

    /** {@code {t} ∪ cn ∪ de(cn)} with {@code cn = (de(t) ∩ an(y)) ∪ {y}} if t reaches y, else {t}. */
    Set<String> forbidden(String t, String y) {
        Set<String> forb = new LinkedHashSet<>();
        forb.add(t);
        Set<String> deT = reachability.descendantsDfs(t);
        if (!t.equals(y) && deT.contains(y)) {
            Set<String> cn = new LinkedHashSet<>(deT);
            cn.retainAll(reachability.ancestorsDfs(y));
            cn.add(y);
            for (String c : cn) {
                forb.add(c);
                forb.addAll(reachability.descendantsDfs(c));
            }
        }
        return forb;
    }

    private Set<String> undirectedReachable(Set<String> starts, Set<String> forbidden) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (forbidden.contains(current) || !visited.add(current)) continue;
            queue.addAll(graph.parentsOf(current));
            queue.addAll(graph.childrenOf(current));
        }
        return visited;
    }
}
