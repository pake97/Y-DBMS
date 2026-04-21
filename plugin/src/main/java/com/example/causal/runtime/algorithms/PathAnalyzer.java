package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.Set;

/**
 * Path-based structural queries over a {@link CompiledCausalGraph}:
 * mediators, colliders, confounders, and backdoor-path variables.
 *
 * <h2>Student A implementation surface</h2>
 * Each method below is stubbed with a TODO and the expected semantics.
 * The DSL depends on these via {@link com.example.causal.api.CausalGraphRuntime};
 * implementing them unblocks {@code MEDIATORS / COLLIDERS / CONFOUNDERS /
 * BACKDOOR PATHS} in the DSL.
 *
 * <h2>Implementation notes</h2>
 * <ul>
 *   <li>All methods work on variable ids (strings) — callers wrap results in
 *       {@code VariableRef} at the API boundary.</li>
 *   <li>Prefer reusing the existing {@link ReachabilityIndex} where possible
 *       (ancestors / descendants / hasDirectedPath).</li>
 *   <li>For a reference implementation (pure Java, same algorithms, no bitsets),
 *       see {@code dsl/model/CausalModel.java} in the DSL module — treat it as a
 *       correctness oracle, not a performance target.</li>
 *   <li>Write unit tests under {@code plugin/src/test/java/.../runtime/} in the
 *       same style as {@code TopologicalSorterTest} — pure Java, no Neo4j.</li>
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
        // TODO(student): implement as described above
        throw new UnsupportedOperationException("TODO(student): PathAnalyzer.mediators");
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
        // TODO(student): implement as described above
        throw new UnsupportedOperationException("TODO(student): PathAnalyzer.colliders");
    }

    /**
     * Common ancestors of {@code x} and {@code y} (intersection of
     * {@code An(x)} and {@code An(y)}), excluding {@code x} and {@code y}.
     *
     * <p>Suggested approach: one line using {@link ReachabilityIndex#ancestors}.
     */
    public Set<String> confounders(String x, String y) {
        // TODO(student): implement using ReachabilityIndex.ancestors intersection
        throw new UnsupportedOperationException("TODO(student): PathAnalyzer.confounders");
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
        // TODO(student): implement as described above
        throw new UnsupportedOperationException("TODO(student): PathAnalyzer.backdoorVariables");
    }
}
