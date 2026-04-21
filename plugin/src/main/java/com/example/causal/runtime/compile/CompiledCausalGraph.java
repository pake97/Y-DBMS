package com.example.causal.runtime.compile;

import com.example.causal.api.GraphSnapshot;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Immutable compiled representation of the causal DAG.
 *
 * <p>This is the central data structure that Student A is responsible for
 * optimising. The current implementation uses plain Java collections.
 *
 * <h2>Student A implementation plan</h2>
 *
 * The plan is tiered — only Tier 1 is needed for the procedures to be
 * functionally complete. Tier 2 and 3 are performance and ergonomics.
 *
 * <h3>Tier 1 — required for the plugin to answer every DSL query</h3>
 * <ol>
 *   <li>Implement the path-based queries in
 *       {@link com.example.causal.runtime.algorithms.PathAnalyzer}
 *       (mediators, colliders, confounders, backdoorVariables).
 *       Unblocks {@code MEDIATORS / COLLIDERS / CONFOUNDERS / BACKDOOR}
 *       in the DSL. Nothing here needs to change yet.</li>
 *   <li>Implement d-separation and the backdoor criterion in
 *       {@link com.example.causal.runtime.DefaultCausalGraphRuntime}
 *       (see TODOs there). Unblocks {@code causal.dsep} and
 *       {@code causal.adjustmentCandidate}, which currently return
 *       {@code .unimplemented()} placeholders.</li>
 * </ol>
 *
 * <h3>Tier 2 — performance refactor (snapshot layout + bitsets)</h3>
 * <ol>
 *   <li>Replace the Map-based adjacency in this class with a dense
 *       {@code int}-indexed layout:
 *       <pre>
 *         Map&lt;String, Integer&gt; nameToIndex;   // variable id → dense int index
 *         Variable[]           indexToVar;     // dense index → Variable
 *         int[][]              forwardAdj;     // index → int[] of child indices
 *         int[][]              reverseAdj;     // index → int[] of parent indices
 *         int[]                topoOrder;      // topological order as int indices
 *         int[]                topoLayer;      // layer per node (0 = source); prunes traversals
 *       </pre>
 *   </li>
 *   <li>Pre-compute ancestor/descendant bitsets per variable (drives the
 *       speedup for every structural query):
 *       <pre>
 *         long[][] ancestorBits;    // ancestorBits[i] = ancestors of i as a bitset
 *         long[][] descendantBits;  // descendantBits[i] = descendants of i as a bitset
 *       </pre>
 *       Fill in reverse topological order:
 *       {@code ancestorBits[i] = ⋃ (ancestorBits[p] | setBit(p))} over parents p.
 *       Store these here and let {@link com.example.causal.runtime.algorithms.ReachabilityIndex}
 *       read them.</li>
 *   <li>Expose {@code topoIndex(String id)} as an O(1) map lookup (replaces
 *       the current {@link #topoIndex} {@code indexOf} scan).</li>
 * </ol>
 *
 * <h3>Tier 3 — optional ergonomics / lazy caches</h3>
 * <ul>
 *   <li>One-liner derived sets on top of the bitsets, computed lazily:
 *       {@code markovBlanket(v) = parents ∪ children ∪ parents_of_children},
 *       {@code forbiddenAdjustmentSet(t, y)} = descendants of {@code t} that
 *       lie on a directed path to {@code y} (used by the DSL's
 *       {@code ESTIMATE} validator).</li>
 *   <li>Lazy pair-index cache: {@code Map<(treatment, outcome), PairFacts>}
 *       with {@code hasDirectedPath / hasBackdoorPath / mediatorsBitset /
 *       minimalAdjustmentSetsRef}. Add only if profiling shows the same
 *       pairs are queried repeatedly.</li>
 * </ul>
 *
 * <h3>Explicitly out of scope for v1</h3>
 * <ul>
 *   <li>Materialising derived relationships as Neo4j edges
 *       ({@code :ANCESTOR_OF}, {@code :IN_TOPO_LAYER}, etc.) — the snapshot
 *       is the single source of truth; duplicating it in the graph doubles
 *       the invalidation burden. Expose derived facts through
 *       {@code causal.*} procedures instead.</li>
 *   <li>Districts / c-components (ADMG / latent projection), path-pattern
 *       signature caches, spouse sets — only needed if d-separation is
 *       implemented via the moral graph or if the DSL grows an ADMG layer.</li>
 * </ul>
 *
 * <h2>Verifying your structures</h2>
 * Call {@link #logStructure()} after any structural change — it prints the full
 * adjacency lists and topological order to the console so you can eyeball correctness.
 * {@link com.example.causal.runtime.DefaultCausalGraphRuntime#compile} calls it
 * automatically every time the graph is compiled.
 */
public final class CompiledCausalGraph implements GraphSnapshot {

    private static final Logger LOG = Logger.getLogger(CompiledCausalGraph.class.getName());

    private final Instant compiledAt;
    private final Map<String, Variable> variableById;      // TODO(student, tier 2): → dense int[] layout
    private final Map<String, Set<String>> forwardAdj;     // TODO(student, tier 2): → int[][] forwardAdj
    private final Map<String, Set<String>> reverseAdj;     // TODO(student, tier 2): → int[][] reverseAdj
    private final List<String> topoOrder;                  // TODO(student, tier 2): → int[] topoOrder

    // TODO(student, tier 2): add alongside the dense layout
    // private final int[]    topoLayer;       // layer per node (0 = source)
    // private final long[][] ancestorBits;    // ancestorBits[i] = bitset of ancestors of i
    // private final long[][] descendantBits;  // descendantBits[i] = bitset of descendants of i

    CompiledCausalGraph(
            Map<String, Variable> variableById,
            Map<String, Set<String>> forwardAdj,
            Map<String, Set<String>> reverseAdj,
            List<String> topoOrder,
            Instant compiledAt
    ) {
        this.variableById = Collections.unmodifiableMap(new LinkedHashMap<>(variableById));
        this.forwardAdj   = Collections.unmodifiableMap(deepCopy(forwardAdj));
        this.reverseAdj   = Collections.unmodifiableMap(deepCopy(reverseAdj));
        this.topoOrder    = Collections.unmodifiableList(new ArrayList<>(topoOrder));
        this.compiledAt   = compiledAt;
    }

    // -------------------------------------------------------------------------
    // GraphSnapshot
    // -------------------------------------------------------------------------

    @Override
    public Set<VariableRef> variables() {
        Set<VariableRef> out = new LinkedHashSet<>();
        for (String id : variableById.keySet()) out.add(new VariableRef(id));
        return Collections.unmodifiableSet(out);
    }

    @Override public int size()                      { return variableById.size(); }
    @Override public boolean contains(VariableRef r) { return variableById.containsKey(r.id()); }
    @Override public Instant compiledAt()            { return compiledAt; }

    // -------------------------------------------------------------------------
    // Internal accessors — used by DefaultCausalGraphRuntime and algorithms
    // -------------------------------------------------------------------------

    /** Look up the internal Variable record by id. */
    public Optional<Variable> findVariable(String id) {
        return Optional.ofNullable(variableById.get(id));
    }

    /** Direct parents (causes) of the variable with the given id. */
    public Set<String> parentsOf(String id) {
        return reverseAdj.getOrDefault(id, Set.of());
    }

    /** Direct children (effects) of the variable with the given id. */
    public Set<String> childrenOf(String id) {
        return forwardAdj.getOrDefault(id, Set.of());
    }

    /** Topological order of variable ids, sources first. */
    public List<String> topoOrder() {
        return topoOrder;
    }

    /**
     * Topological position of the variable (0 = source), or -1 if unknown.
     * TODO(student, tier 2): replace with O(1) map lookup once dense layout is in place.
     */
    public int topoIndex(String id) {
        return topoOrder.indexOf(id);
    }

    // -------------------------------------------------------------------------
    // Structural logging — for Student A to verify data structures
    // -------------------------------------------------------------------------

    /**
     * Logs the full compiled graph structure at INFO level.
     *
     * <p>Called automatically by
     * {@link com.example.causal.runtime.DefaultCausalGraphRuntime#compile} after
     * every compile. Read the output to verify that your data structures are correct.
     *
     * <p>When you add new internal structures (bitsets, index maps, etc.), add a
     * section here so you can inspect them in the same place.
     *
     * <p>Output goes to the standard JUL logger. In Neo4j server mode the log
     * appears in {@code logs/neo4j.log}. In the embedded test harness it prints
     * to stdout.
     */
    public void logStructure() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════════╗");
        sb.append("\n║           CompiledCausalGraph — structure dump           ║");
        sb.append("\n╚══════════════════════════════════════════════════════════╝");
        sb.append("\n  variables : ").append(variableById.size());
        sb.append("\n  edges     : ").append(forwardAdj.values().stream().mapToInt(Set::size).sum());
        sb.append("\n  compiled  : ").append(compiledAt);

        sb.append("\n\n── Topological order (index : id) ──────────────────────────");
        for (int i = 0; i < topoOrder.size(); i++) {
            String id = topoOrder.get(i);
            Variable v = variableById.get(id);
            sb.append(String.format("%n  [%2d]  %-30s  observed=%-5b  latent=%b",
                    i, id, v.observed(), v.latent()));
        }

        sb.append("\n\n── Forward adjacency (id → children) ───────────────────────");
        for (String id : topoOrder) {
            Set<String> children = forwardAdj.getOrDefault(id, Set.of());
            sb.append(String.format("%n  %-30s →  %s", id, children.isEmpty() ? "(none)" : children));
        }

        sb.append("\n\n── Reverse adjacency (id → parents) ────────────────────────");
        for (String id : topoOrder) {
            Set<String> parents = reverseAdj.getOrDefault(id, Set.of());
            sb.append(String.format("%n  %-30s ←  %s", id, parents.isEmpty() ? "(none)" : parents));
        }

        // TODO(student, tier 2): add sections here once you implement the dense
        // layout + bitsets. Logging them in one place is how you verify correctness
        // before switching callers over. Suggested:
        //
        //   ── Topo layer per node ─────────────────────────────────────
        //     for (int i = 0; i < size(); i++) sb.append(... topoLayer[i] ...);
        //
        //   ── Ancestor bitsets ────────────────────────────────────────
        //     for (int i = 0; i < size(); i++)
        //         sb.append(String.format("%n  [%2d] %s", i, Long.toBinaryString(ancestorBits[i][0])));

        sb.append("\n────────────────────────────────────────────────────────────\n");
        LOG.info(sb.toString());
    }

    // -------------------------------------------------------------------------

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> src) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : src.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        return copy;
    }
}
