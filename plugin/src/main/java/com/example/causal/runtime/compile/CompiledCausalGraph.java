package com.example.causal.runtime.compile;

import com.example.causal.api.GraphSnapshot;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    // ── Linear-Gaussian SEM parameters ───────────────────────────────────────
    /** Edge coefficients β, keyed child → (parent → β). Immutable deep copy. */
    private final Map<String, Map<String, Double>> edgeWeights;
    /** True when at least one SEM parameter differs from the default. */
    private final boolean parameterized;

    private final Map<String, Integer> nameToIndex;
    private final Variable[] indexToVar;
    private final int[][] denseForwardAdj;
    private final int[][] denseReverseAdj;
    
    private final int[] topoLayer;
    private final long[][] ancestorBits;
    private final long[][] descendantBits;

    /**
     * Full constructor including linear-Gaussian SEM edge weights.
     */
    CompiledCausalGraph(
            Map<String, Variable> variableById,
            Map<String, Set<String>> forwardAdj,
            Map<String, Set<String>> reverseAdj,
            List<String> topoOrder,
            Map<String, Map<String, Double>> edgeWeights,
            Instant compiledAt
    ) {
        this.variableById = Collections.unmodifiableMap(new LinkedHashMap<>(variableById));
        this.edgeWeights  = Collections.unmodifiableMap(deepCopyWeights(edgeWeights));
        this.compiledAt   = compiledAt;
        this.parameterized = computeParameterized();

        int n = this.variableById.size();
        this.nameToIndex = new HashMap<>();
        this.indexToVar = new Variable[n];
        
        for (int i = 0; i < n; i++) {
            String id = topoOrder.get(i);
            this.nameToIndex.put(id, i);
            this.indexToVar[i] = this.variableById.get(id);
        }
        
        // jagged columns, efficient storage of only needed indices
        this.denseForwardAdj = new int[n][];
        this.denseReverseAdj = new int[n][];
        
        for (int i = 0; i < n; i++) {
            String id = topoOrder.get(i);
            Set<String> children = forwardAdj.getOrDefault(id, Set.of());
            this.denseForwardAdj[i] = children.stream().mapToInt(this.nameToIndex::get).toArray();
            
            Set<String> parents = reverseAdj.getOrDefault(id, Set.of());
            this.denseReverseAdj[i] = parents.stream().mapToInt(this.nameToIndex::get).toArray();
        }
        
        // setup topo layer ordering for all nodes
        // curr layer = max(parents' layers) + 1
        this.topoLayer = new int[n];
        for (int i = 0; i < n; i++) {
            int maxParentLayer = -1;
            for (int p : this.denseReverseAdj[i]) {
                if (this.topoLayer[p] > maxParentLayer) {
                    maxParentLayer = this.topoLayer[p];
                }
            }
            this.topoLayer[i] = maxParentLayer + 1;
        }
        
        // index <-> bit mapping: not jagged, for efficient logical ops
        int longsPerNode = (n + 63) / 64;
        this.ancestorBits = new long[n][longsPerNode];
        this.descendantBits = new long[n][longsPerNode];
        
        for (int i = 0; i < n; i++) {
            for (int p : this.denseReverseAdj[i]) {
                // apply the parent's ancestors indices to current node
                for (int w = 0; w < longsPerNode; w++) {
                    this.ancestorBits[i][w] |= this.ancestorBits[p][w];
                }
                // set current node's parent
                // 1. Which block (array index) does bit p live in?
                // find the specific long block of parent ( p / 64 ~ p / 2^64 ~ shift op)
                // 2. Which specific bit inside that block is it?
                // p % 64; find location of node's index in block
                this.ancestorBits[i][p >>> 6] |= (1L << (p & 63));
            }
        }
        
        for (int i = n - 1; i >= 0; i--) {
            for (int c : this.denseForwardAdj[i]) {
                for (int w = 0; w < longsPerNode; w++) {
                    this.descendantBits[i][w] |= this.descendantBits[c][w];
                }
                this.descendantBits[i][c >>> 6] |= (1L << (c & 63));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Incremental Maintenance
    // -------------------------------------------------------------------------

    public CompiledCausalGraph patch(ChangeBatch batch) {
        if (!batch.removedVariables().isEmpty() || !batch.removedEdges().isEmpty()) {
            return null; // non-local changes, fallback to recompile
        }
        if (batch.isEmpty() && batch.updatedVariables().isEmpty()) {
            return this;
        }
        for (ChangeBatch.Edge e : batch.addedEdges()) {
            int u = topoIndex(e.from());
            int v = topoIndex(e.to());
            // If from is new, topoIndex is -1. If to is new, topoIndex is -1.
            // But we assign new variables to the END of the topo order.
            // if u != -1 and v != -1 and u >= v -> backward edge, fallback.
            // if u == -1 and v != -1, then u (new) >= v (old) -> fallback.
            // if u != -1 and v == -1, then u (old) < v (new) -> OK.
            if ((u != -1 && v != -1 && u >= v) || (u == -1 && v != -1)) {
                return null; // Topo order violated, fallback
            }
        }
        return new CompiledCausalGraph(this, batch);
    }

    private CompiledCausalGraph(CompiledCausalGraph old, ChangeBatch batch) {
        int oldN = old.variableById.size();
        int newN = oldN + batch.addedVariables().size();

        Map<String, Variable> newVarById = new LinkedHashMap<>(old.variableById);
        for (Variable v : batch.updatedVariables()) newVarById.put(v.id(), v);
        for (Variable v : batch.addedVariables()) newVarById.put(v.id(), v);
        this.variableById = Collections.unmodifiableMap(newVarById);

        this.edgeWeights = old.edgeWeights; // unchanged
        this.compiledAt = Instant.now();
        
        this.nameToIndex = new HashMap<>(old.nameToIndex);
        this.indexToVar = Arrays.copyOf(old.indexToVar, newN);
        
        // Deep copy the jagged arrays so we can mutate them
        this.denseForwardAdj = new int[newN][];
        this.denseReverseAdj = new int[newN][];
        for (int i = 0; i < oldN; i++) {
            this.denseForwardAdj[i] = old.denseForwardAdj[i].clone();
            this.denseReverseAdj[i] = old.denseReverseAdj[i].clone();
        }

        this.topoLayer = Arrays.copyOf(old.topoLayer, newN);

        int longsPerNode = (newN + 63) / 64;
        int oldLongsPerNode = (oldN + 63) / 64;
        this.ancestorBits = new long[newN][longsPerNode];
        this.descendantBits = new long[newN][longsPerNode];

        for (int i = 0; i < oldN; i++) {
            System.arraycopy(old.ancestorBits[i], 0, this.ancestorBits[i], 0, oldLongsPerNode);
            System.arraycopy(old.descendantBits[i], 0, this.descendantBits[i], 0, oldLongsPerNode);
        }
        for (Variable v : batch.updatedVariables()) {
            Integer idx = this.nameToIndex.get(v.id());
            if (idx != null) {
                this.indexToVar[idx] = v;
            }
        }

        for (int i = oldN; i < newN; i++) {
            Variable v = batch.addedVariables().get(i - oldN);
            this.nameToIndex.put(v.id(), i);
            this.indexToVar[i] = v;
            this.denseForwardAdj[i] = new int[0];
            this.denseReverseAdj[i] = new int[0];
            this.topoLayer[i] = 0;
        }

        int minV = newN;
        int maxU = -1;

        for (ChangeBatch.Edge e : batch.addedEdges()) {
            int u = this.nameToIndex.get(e.from());
            int v = this.nameToIndex.get(e.to());
            
            int[] oldF = this.denseForwardAdj[u];
            int[] newF = Arrays.copyOf(oldF, oldF.length + 1);
            newF[oldF.length] = v;
            this.denseForwardAdj[u] = newF;

            int[] oldR = this.denseReverseAdj[v];
            int[] newR = Arrays.copyOf(oldR, oldR.length + 1);
            newR[oldR.length] = u;
            this.denseReverseAdj[v] = newR;

            if (v < minV) minV = v;
            if (u > maxU) maxU = u;
        }

        for (int i = minV; i < newN; i++) {
            int maxParentLayer = -1;
            Arrays.fill(this.ancestorBits[i], 0L);
            for (int p : this.denseReverseAdj[i]) {
                if (this.topoLayer[p] > maxParentLayer) {
                    maxParentLayer = this.topoLayer[p];
                }
                for (int w = 0; w < longsPerNode; w++) {
                    this.ancestorBits[i][w] |= this.ancestorBits[p][w];
                }
                this.ancestorBits[i][p >>> 6] |= (1L << (p & 63));
            }
            this.topoLayer[i] = maxParentLayer + 1;
        }

        for (int i = maxU; i >= 0; i--) {
            Arrays.fill(this.descendantBits[i], 0L);
            for (int c : this.denseForwardAdj[i]) {
                for (int w = 0; w < longsPerNode; w++) {
                    this.descendantBits[i][w] |= this.descendantBits[c][w];
                }
                this.descendantBits[i][c >>> 6] |= (1L << (c & 63));
            }
        }

        this.parameterized = computeParameterized();
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
        int idx = topoIndex(id);
        if (idx == -1) return Set.of();
        Set<String> parents = new LinkedHashSet<>();
        for (int p : denseReverseAdj[idx]) parents.add(indexToVar[p].id());
        return Collections.unmodifiableSet(parents);
    }

    /** Direct children (effects) of the variable with the given id. */
    public Set<String> childrenOf(String id) {
        int idx = topoIndex(id);
        if (idx == -1) return Set.of();
        Set<String> children = new LinkedHashSet<>();
        for (int c : denseForwardAdj[idx]) children.add(indexToVar[c].id());
        return Collections.unmodifiableSet(children);
    }

    /** Topological order of variable ids, sources first. */
    public List<String> topoOrder() {
        return Arrays.stream(indexToVar).map(Variable::id).toList();
    }

    /**
     * Topological position of the variable (0 = source), or -1 if unknown.
     */
    public int topoIndex(String id) {
        return nameToIndex.getOrDefault(id, -1);
    }

    /** Ancestor bitset of the variable at {@code denseIndex} (defensive copy — the snapshot stays immutable). */
    public long[] getAncestorBits(int denseIndex) {
        return ancestorBits[denseIndex].clone();
    }

    /** Descendant bitset of the variable at {@code denseIndex} (defensive copy — the snapshot stays immutable). */
    public long[] getDescendantBits(int denseIndex) {
        return descendantBits[denseIndex].clone();
    }

    public String idForIndex(int denseIndex) {
        return indexToVar[denseIndex].id();
    }

    // -------------------------------------------------------------------------
    // Linear-Gaussian SEM accessors
    // -------------------------------------------------------------------------

    /**
     * Edge coefficient β for edge {@code from → to}.
     * Returns {@link Variable#DEFAULT_WEIGHT 1.0} when the weight was not
     * declared in Neo4j (the identity coefficient).
     */
    public double weight(String from, String to) {
        return edgeWeights.getOrDefault(to, Map.of()).getOrDefault(from, Variable.DEFAULT_WEIGHT);
    }

    /**
     * Intercept μ₀ (structural-equation constant) for the variable with the given id.
     * Returns {@link Variable#DEFAULT_INTERCEPT 0.0} when not declared.
     */
    public double intercept(String id) {
        Variable v = variableById.get(id);
        return v != null ? v.intercept() : Variable.DEFAULT_INTERCEPT;
    }

    /**
     * Noise variance σ² for the variable with the given id.
     * Returns {@link Variable#DEFAULT_NOISE_VARIANCE 1.0} when not declared.
     */
    public double noiseVariance(String id) {
        Variable v = variableById.get(id);
        return v != null ? v.noiseVariance() : Variable.DEFAULT_NOISE_VARIANCE;
    }

    /**
     * True if any SEM parameter (weight, intercept, or noise) was explicitly set
     * to a non-default value. When false, the graph is structural-only.
     */
    public boolean isParameterized() {
        return parameterized;
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
        int edgeCount = 0;
        for (int[] children : denseForwardAdj) edgeCount += children.length;
        sb.append("\n  edges     : ").append(edgeCount);
        sb.append("\n  compiled  : ").append(compiledAt);

        List<String> topoList = topoOrder();

        sb.append("\n\n── Topological order (index : id) ──────────────────────────");
        for (int i = 0; i < topoList.size(); i++) {
            String id = topoList.get(i);
            Variable v = variableById.get(id);
            sb.append(String.format("%n  [%2d]  %-30s  observed=%-5b  latent=%b",
                    i, id, v.observed(), v.latent()));
        }

        sb.append("\n\n── Forward adjacency (id → children) ───────────────────────");
        for (String id : topoList) {
            Set<String> children = childrenOf(id);
            sb.append(String.format("%n  %-30s →  %s", id, children.isEmpty() ? "(none)" : children));
        }

        sb.append("\n\n── Reverse adjacency (id → parents) ────────────────────────");
        for (String id : topoList) {
            Set<String> parents = parentsOf(id);
            sb.append(String.format("%n  %-30s ←  %s", id, parents.isEmpty() ? "(none)" : parents));
        }

        if (variableById.size() <= 64) {
            sb.append("\n\n── Dense Index & Reachability Bitsets (n<=64) ─────────────");
            for (int i = 0; i < variableById.size(); i++) {
                String ancStr = String.format("%" + variableById.size() + "s", Long.toBinaryString(ancestorBits[i][0])).replace(' ', '0');
                String descStr = String.format("%" + variableById.size() + "s", Long.toBinaryString(descendantBits[i][0])).replace(' ', '0');
                sb.append(String.format("%n  [%2d] %-20s layer=%d  anc=%s  desc=%s",
                        i, indexToVar[i].id(), topoLayer[i], ancStr, descStr));
            }
        }

        // ── Linear-Gaussian SEM parameters (when present) ────────────
        if (parameterized) {
            sb.append("\n\n── SEM parameters (linear-Gaussian) ────────────────────────");
            sb.append("\n  Per-variable (intercept μ₀, noise σ²):");
            for (String id : topoList) {
                Variable v = variableById.get(id);
                sb.append(String.format("%n    %-30s  intercept=%.4f  noise=%.4f",
                        id, v.intercept(), v.noiseVariance()));
            }
            sb.append("\n  Edge weights (from → to : β):");
            for (String to : topoList) {
                Map<String, Double> byParent = edgeWeights.getOrDefault(to, Map.of());
                for (String from : parentsOf(to)) {
                    double w = byParent.getOrDefault(from, 1.0);
                    sb.append(String.format("%n    %-15s → %-15s  β=%.4f", from, to, w));
                }
            }
        }

        sb.append("\n────────────────────────────────────────────────────────────\n");
        LOG.info(sb.toString());
    }

    // -------------------------------------------------------------------------

    /** Deep-copy edge weight maps into an immutable structure. */
    private static Map<String, Map<String, Double>> deepCopyWeights(Map<String, Map<String, Double>> src) {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : src.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        return copy;
    }

    /**
     * Check whether any SEM parameter deviates from its default, indicating
     * the graph was loaded with explicit linear-Gaussian parameters.
     */
    private boolean computeParameterized() {
        for (Map<String, Double> byParent : edgeWeights.values()) {
            for (double w : byParent.values()) {
                if (w != Variable.DEFAULT_WEIGHT) return true;
            }
        }
        for (Variable v : variableById.values()) {
            if (v.intercept() != Variable.DEFAULT_INTERCEPT) return true;
            if (v.noiseVariance() != Variable.DEFAULT_NOISE_VARIANCE) return true;
        }
        return false;
    }
}
