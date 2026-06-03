package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Path-based structural queries over a {@link CompiledCausalGraph}:
 * mediators, colliders, confounders, and backdoor-path variables.
 *
 * <h2>Status: implemented (Tier 1 reference example)</h2>
 * All four methods below are implemented and covered by {@code PathAnalyzerTest}.
 * They are the worked example for the two Tier 1 algorithms still left as an
 * exercise — {@code isDSeparated} and {@code candidateAdjustmentSet} in
 * {@link com.example.causal.runtime.DefaultCausalGraphRuntime}. Both follow the
 * same shape used here: take variable ids, lean on {@link ReachabilityIndex} for
 * ancestor/descendant sets, return a {@code Set<String>} (or a result record).
 *
 * <p>The DSL reaches these via {@link com.example.causal.api.CausalGraphRuntime},
 * which is what powers {@code MEDIATORS / COLLIDERS / CONFOUNDERS / BACKDOOR
 * PATHS}. No bitset work is involved — see
 * {@link com.example.causal.runtime.compile.CompiledCausalGraph} for the full
 * tiered plan and where the dense layout would change these.
 *
 * <h2>Implementation notes</h2>
 * <ul>
 *   <li>All methods work on variable ids (strings) — callers wrap results in
 *       {@code VariableRef} at the API boundary.</li>
 *   <li>They reuse the existing {@link ReachabilityIndex} for ancestors /
 *       descendants / hasDirectedPath rather than re-walking the graph.</li>
 *   <li>For an independent reference (pure Java, same algorithms, no bitsets),
 *       see {@code dsl/model/CausalModel.java} in the DSL module — a correctness
 *       oracle, not a performance target.</li>
 *   <li>Tests live in {@code plugin/src/test/java/.../runtime/algorithms/
 *       PathAnalyzerTest} — pure Java, no Neo4j, the style to copy for the
 *       remaining Tier 1 work.</li>
 * </ul>
 */
public final class PathAnalyzer {

    private final CompiledCausalGraph graph;
    private final ReachabilityIndex reachability;

    public PathAnalyzer(CompiledCausalGraph graph) {
        this.graph = graph;
        this.reachability = new ReachabilityIndex(graph);
    }

    /**
     * Variables on at least one directed path {@code from → … → to}, excluding
     * the endpoints. Returns an empty set if there is no directed path.
     *
     * <p>Suggested approach: DFS from {@code from} with backtracking; on each
     * call that reaches {@code to}, add every node on the current stack to the
     * result. Prune branches where {@code reachability.descendants(current)}
     * does not contain {@code to}.
     */
    public Set<String> mediators(String from, String to) {
        Set<String> result = new LinkedHashSet<>(reachability.descendants(from));
        result.retainAll(reachability.ancestors(to));
        return result;
    }

    /**
     * Variables {@code Z} where {@code Z} is neither an ancestor of {@code x}
     * nor of {@code y}, but has at least one parent reachable from {@code x}
     * (including {@code x} itself) and at least one parent reachable from
     * {@code y}.
     *
     * <p>Suggested approach: compute {@code De(x) ∪ {x}} and {@code De(y) ∪ {y}}
     * using {@link ReachabilityIndex#descendants}. Then for each variable
     * {@code z}, inspect {@code graph.parentsOf(z)}.
     */
    public Set<String> colliders(String x, String y) {
        Set<String> reachableFromX = new LinkedHashSet<>(reachability.descendants(x));
        reachableFromX.add(x);

        Set<String> reachableFromY = new LinkedHashSet<>(reachability.descendants(y));
        reachableFromY.add(y);

        Set<String> ancestorsOfX = reachability.ancestors(x);
        Set<String> ancestorsOfY = reachability.ancestors(y);

        Set<String> result = new LinkedHashSet<>();
        for (String z : graph.topoOrder()) {
            if (z.equals(x) || z.equals(y)) continue;
            if (ancestorsOfX.contains(z) || ancestorsOfY.contains(z)) continue;

            Set<String> parentsOfZ = graph.parentsOf(z);
            boolean hasXSideParent = parentsOfZ.stream().anyMatch(reachableFromX::contains);
            boolean hasYSideParent = parentsOfZ.stream().anyMatch(reachableFromY::contains);

            if (hasXSideParent && hasYSideParent) {
                result.add(z);
            }
        }

        return result;
    }

    /**
     * Common ancestors of {@code x} and {@code y} (intersection of
     * {@code An(x)} and {@code An(y)}), excluding {@code x} and {@code y}.
     *
     * <p>Suggested approach: one line using {@link ReachabilityIndex#ancestors}.
     */
    public Set<String> confounders(String x, String y) {
        Set<String> commonAncestors = new LinkedHashSet<>(reachability.ancestors(x));
        commonAncestors.retainAll(reachability.ancestors(y));
        commonAncestors.remove(x);
        commonAncestors.remove(y);
        return commonAncestors;
    }

    /**
     * Variables on at least one backdoor path from {@code treatment} to
     * {@code outcome}. A backdoor path begins with an edge into {@code treatment}
     * (so starts at a parent of {@code treatment}) and reaches {@code outcome}
     * through the undirected graph without crossing any descendant of
     * {@code treatment}.
     *
     * <p>Suggested approach: BFS on the undirected skeleton starting from
     * {@code graph.parentsOf(treatment)}; do not enqueue {@code treatment}
     * itself or any node in {@code reachability.descendants(treatment)}.
     * Optionally, filter the result to only variables that can reach
     * {@code outcome} via the same BFS.
     */
    public Set<String> backdoorVariables(String treatment, String outcome) {
        Set<String> forbidden = reachability.descendants(treatment);
        forbidden.add(treatment);
        forbidden.remove(outcome);

        Set<String> fromParents = undirectedReachable(graph.parentsOf(treatment), forbidden);
        Set<String> fromOutcome = undirectedReachable(Set.of(outcome), forbidden);

        fromParents.retainAll(fromOutcome);
        fromParents.remove(outcome);
        return fromParents;
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
