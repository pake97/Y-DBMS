package com.example.causal.runtime.compile;

import com.example.causal.api.GraphSnapshot;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Immutable compiled representation of the causal DAG.
 *
 * <p>Alongside the id-keyed maps (kept for the string-level API), the
 * constructor builds a dense, integer-indexed layout:
 * <ul>
 *   <li>{@code nameToIndex} / {@code indexToVar}: dense index {@code 0..n-1}
 *       in loader insertion order;</li>
 *   <li>CSR adjacency: children of {@code i} are
 *       {@code fwdTarget[fwdOffset[i] .. fwdOffset[i+1])}, parents likewise
 *       in {@code revOffset}/{@code revTarget};</li>
 *   <li>{@code topoOrderIdx}, its inverse {@code topoPos}, and
 *       {@code topoLayer} (0 for sources, else 1 + max layer of parents);</li>
 *   <li>ancestor/descendant closures as one {@code long[words]} bitset per
 *       node ({@link BitSet64}); a node's own bit is never set.</li>
 * </ul>
 * The closures cost {@code 2 * n * ceil(n/64)} longs (about {@code n^2/4}
 * bytes: 250 KB at n = 1000, 25 MB at n = 10 000). Every array is filled in
 * the constructor and never written again. See {@code CODE-PLAN.md} (WP1).
 *
 * <p>{@link SnapshotPatcher} derives a new snapshot from an old one plus a
 * {@code ChangeBatch}, sharing unchanged closure rows by reference.
 *
 * <p>{@link #logStructure()} dumps all of this; it runs on every compile.
 */
public final class CompiledCausalGraph implements GraphSnapshot {

    private static final Logger LOG = Logger.getLogger(CompiledCausalGraph.class.getName());

    private final Instant compiledAt;
    /** {@code compiledAt} of the snapshot this one was patched from, or null if built from scratch. */
    private final Instant patchedFrom;
    private final Map<String, Variable> variableById;
    private final Map<String, Set<String>> forwardAdj;
    private final Map<String, Set<String>> reverseAdj;
    private final List<String> topoOrder;

    // ── Linear-Gaussian SEM parameters ───────────────────────────────────────
    /** Edge coefficients β, keyed child → (parent → β). Immutable deep copy. */
    private final Map<String, Map<String, Double>> edgeWeights;
    /** True when at least one SEM parameter differs from the default. */
    private final boolean parameterized;

    // ── Dense layout + closures (see class Javadoc) ───────────────────────
    private final int n;
    private final Map<String, Integer> nameToIndex;
    private final Variable[] indexToVar;
    private final int[] fwdOffset, fwdTarget;
    private final int[] revOffset, revTarget;
    private final int[] topoOrderIdx;
    private final int[] topoPos;
    private final int[] topoLayer;
    private final int words;
    private final long[][] ancestorBits;
    private final long[][] descendantBits;
    /** Neo4j element id of each variable's node by dense index (null if not loaded from Neo4j). */
    private final String[] elementIds;

    /**
     * Full constructor including linear-Gaussian SEM edge weights, without Neo4j element ids.
     */
    CompiledCausalGraph(
            Map<String, Variable> variableById,
            Map<String, Set<String>> forwardAdj,
            Map<String, Set<String>> reverseAdj,
            List<String> topoOrder,
            Map<String, Map<String, Double>> edgeWeights,
            Instant compiledAt
    ) {
        this(variableById, forwardAdj, reverseAdj, topoOrder, edgeWeights, Map.of(), compiledAt);
    }

    /**
     * Full constructor; {@code elementIdById} maps variable id to its node's Neo4j element id,
     * so procedures can return nodes without searching for them (may be empty).
     */
    CompiledCausalGraph(
            Map<String, Variable> variableById,
            Map<String, Set<String>> forwardAdj,
            Map<String, Set<String>> reverseAdj,
            List<String> topoOrder,
            Map<String, Map<String, Double>> edgeWeights,
            Map<String, String> elementIdById,
            Instant compiledAt
    ) {
        this.variableById = Collections.unmodifiableMap(new LinkedHashMap<>(variableById));
        this.forwardAdj   = Collections.unmodifiableMap(deepCopy(forwardAdj));
        this.reverseAdj   = Collections.unmodifiableMap(deepCopy(reverseAdj));
        this.topoOrder    = Collections.unmodifiableList(new ArrayList<>(topoOrder));
        this.edgeWeights  = Collections.unmodifiableMap(deepCopyWeights(edgeWeights));
        this.compiledAt   = compiledAt;
        this.patchedFrom  = null;

        // 1. dense index in loader order
        this.n = this.variableById.size();
        Map<String, Integer> idx = new LinkedHashMap<>();
        this.indexToVar = new Variable[n];
        for (Variable v : this.variableById.values()) {
            indexToVar[idx.size()] = v;
            idx.put(v.id(), idx.size());
        }
        this.nameToIndex = Collections.unmodifiableMap(idx);
        this.elementIds = new String[n];
        for (int i = 0; i < n; i++) elementIds[i] = elementIdById.get(indexToVar[i].id());

        // 2. CSR adjacency
        this.fwdOffset = new int[n + 1];
        this.fwdTarget = csr(this.forwardAdj, fwdOffset);
        this.revOffset = new int[n + 1];
        this.revTarget = csr(this.reverseAdj, revOffset);
        if (fwdOffset[n] != revOffset[n]) {
            throw new IllegalStateException("forward/reverse adjacency disagree: "
                    + fwdOffset[n] + " vs " + revOffset[n] + " edges");
        }

        // 3. topological order as indices (TopologicalSorter already checked acyclicity)
        this.topoOrderIdx = new int[n];
        this.topoPos = new int[n];
        for (int k = 0; k < n; k++) {
            int i = nameToIndex.get(this.topoOrder.get(k));
            topoOrderIdx[k] = i;
            topoPos[i] = k;
        }

        // 4. layers, 5. ancestor closure: one forward-topological pass.
        // Over the closure budget, only layers are computed (layer-pruned branch).
        this.words = BitSet64.words(n);
        boolean closures = closureBytes(n) <= closureBudget();
        this.topoLayer = new int[n];
        this.ancestorBits = closures ? new long[n][] : null;
        for (int i : topoOrderIdx) {
            long[] anc = closures ? new long[words] : null;
            int layer = 0;
            for (int e = revOffset[i]; e < revOffset[i + 1]; e++) {
                int p = revTarget[e];
                if (closures) {
                    BitSet64.or(anc, ancestorBits[p]);
                    BitSet64.set(anc, p);
                }
                layer = Math.max(layer, topoLayer[p] + 1);
            }
            topoLayer[i] = layer;
            if (closures) ancestorBits[i] = anc;
        }

        // 6. descendant closure: one reverse-topological pass
        this.descendantBits = closures ? new long[n][] : null;
        for (int k = n - 1; closures && k >= 0; k--) {
            int i = topoOrderIdx[k];
            long[] des = new long[words];
            for (int e = fwdOffset[i]; e < fwdOffset[i + 1]; e++) {
                int c = fwdTarget[e];
                BitSet64.or(des, descendantBits[c]);
                BitSet64.set(des, c);
            }
            descendantBits[i] = des;
        }

        this.parameterized = computeParameterized();
    }

    /**
     * Assembly constructor for {@link SnapshotPatcher}: stores every field as
     * given, without copying or recomputing. The caller owns immutability:
     * maps must be unmodifiable and no array may be written afterwards.
     */
    CompiledCausalGraph(
            Map<String, Variable> variableById,
            Map<String, Set<String>> forwardAdj,
            Map<String, Set<String>> reverseAdj,
            List<String> topoOrder,
            Map<String, Map<String, Double>> edgeWeights,
            int n,
            Map<String, Integer> nameToIndex,
            Variable[] indexToVar,
            int[] fwdOffset, int[] fwdTarget,
            int[] revOffset, int[] revTarget,
            int[] topoOrderIdx, int[] topoPos, int[] topoLayer,
            int words,
            long[][] ancestorBits, long[][] descendantBits,
            String[] elementIds,
            Instant compiledAt,
            Instant patchedFrom
    ) {
        check(variableById.size() == n && nameToIndex.size() == n && indexToVar.length == n
                && topoOrder.size() == n, "variable count");
        check(fwdOffset.length == n + 1 && revOffset.length == n + 1, "CSR offsets");
        check(fwdOffset[n] == fwdTarget.length && revOffset[n] == revTarget.length
                && fwdTarget.length == revTarget.length, "CSR targets");
        check(topoOrderIdx.length == n && topoPos.length == n && topoLayer.length == n
                && elementIds.length == n, "order arrays");
        check(words == BitSet64.words(n) && ancestorBits.length == n && descendantBits.length == n, "closures");
        for (int i = 0; i < n; i++) {
            check(ancestorBits[i].length == words && descendantBits[i].length == words, "closure row " + i);
        }
        this.variableById = variableById;
        this.forwardAdj = forwardAdj;
        this.reverseAdj = reverseAdj;
        this.topoOrder = topoOrder;
        this.edgeWeights = edgeWeights;
        this.n = n;
        this.nameToIndex = nameToIndex;
        this.indexToVar = indexToVar;
        this.fwdOffset = fwdOffset;
        this.fwdTarget = fwdTarget;
        this.revOffset = revOffset;
        this.revTarget = revTarget;
        this.topoOrderIdx = topoOrderIdx;
        this.topoPos = topoPos;
        this.topoLayer = topoLayer;
        this.words = words;
        this.ancestorBits = ancestorBits;
        this.descendantBits = descendantBits;
        this.elementIds = elementIds;
        this.compiledAt = compiledAt;
        this.patchedFrom = patchedFrom;
        this.parameterized = computeParameterized();
    }

    /** Bytes of the two closures for {@code n} nodes: {@code 2 * n * ceil(n/64) * 8}. */
    public static long closureBytes(int n) {
        return 2L * n * BitSet64.words(n) * Long.BYTES;
    }

    /** {@code -Dcausal.closures.budget=<bytes>}, read at every build; default unlimited. */
    static long closureBudget() {
        return Long.getLong("causal.closures.budget", Long.MAX_VALUE);
    }

    /**
     * False when the closures exceeded {@code causal.closures.budget}: then
     * {@link #ancestorBits}, {@link #descendantBits} and {@link #reaches} must not
     * be called, and {@code ReachabilityIndex} answers with layer-pruned DFS.
     */
    public boolean hasClosures() {
        return ancestorBits != null;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("inconsistent snapshot arrays: " + what);
    }

    // Package-private views for SnapshotPatcher (all unmodifiable or never written).
    Map<String, Variable> variableMap()                 { return variableById; }
    Map<String, Set<String>> forwardMap()               { return forwardAdj; }
    Map<String, Set<String>> reverseMap()               { return reverseAdj; }
    Map<String, Map<String, Double>> edgeWeightMap()    { return edgeWeights; }
    Map<String, Integer> indexMap()                     { return nameToIndex; }
    Variable[] variableArray()                          { return indexToVar; }
    int[] topoOrderArray()                              { return topoOrderIdx; }
    int[] layerArray()                                  { return topoLayer; }
    long[][] ancestorRows()                             { return ancestorBits; }
    long[][] descendantRows()                           { return descendantBits; }
    String[] elementIdArray()                           { return elementIds; }

    /** Fill {@code offset} and return the target array for one adjacency direction. */
    private int[] csr(Map<String, Set<String>> adj, int[] offset) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            offset[i] = m;
            m += adj.getOrDefault(indexToVar[i].id(), Set.of()).size();
        }
        offset[n] = m;
        int[] target = new int[m];
        for (int i = 0; i < n; i++) {
            int e = offset[i];
            for (String id : adj.getOrDefault(indexToVar[i].id(), Set.of())) {
                Integer j = nameToIndex.get(id);
                if (j == null) throw new IllegalStateException("edge endpoint `" + id + "` is not a variable");
                target[e++] = j;
            }
        }
        return target;
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

    /** Topological position of the variable (0 = source), or -1 if unknown. */
    public int topoIndex(String id) {
        int i = indexOf(id);
        return i < 0 ? -1 : topoPos[i];
    }

    // -------------------------------------------------------------------------
    // Index-level accessors (dense int ids)
    // -------------------------------------------------------------------------

    /**
     * Neo4j element id of the variable's node as of the compile, or null (unknown id, a
     * variable added by a patch, or a snapshot not built from Neo4j). A stale element id
     * cannot occur: deleting a :Variable invalidates the snapshot.
     */
    public String elementIdOf(String id) {
        Integer i = nameToIndex.get(id);
        return i == null ? null : elementIds[i];
    }

    /** Dense index of {@code id}, or -1 if unknown. */
    public int indexOf(String id) {
        Integer i = nameToIndex.get(id);
        return i == null ? -1 : i;
    }

    public String idOf(int i)          { return indexToVar[i].id(); }
    public Variable variableAt(int i)  { return indexToVar[i]; }
    public int wordCount()             { return words; }
    public int layer(int i)            { return topoLayer[i]; }
    public int topoPosition(int i)     { return topoPos[i]; }

    /** Ancestors of {@code i} as a bitset. The internal array: callers must not mutate it. */
    public long[] ancestorBits(int i)   { return ancestorBits[i]; }

    /** Descendants of {@code i} as a bitset. The internal array: callers must not mutate it. */
    public long[] descendantBits(int i) { return descendantBits[i]; }

    /** True if there is a directed path {@code from → … → to} of length ≥ 1. */
    public boolean reaches(int from, int to) {
        return BitSet64.get(descendantBits[from], to);
    }

    // Copy-free CSR access for hot loops: the k-th child / parent of i.
    public int childCount(int i)         { return fwdOffset[i + 1] - fwdOffset[i]; }
    public int child(int i, int k)       { return fwdTarget[fwdOffset[i] + k]; }
    public int parentCount(int i)        { return revOffset[i + 1] - revOffset[i]; }
    public int parent(int i, int k)      { return revTarget[revOffset[i] + k]; }

    /** Children of {@code i} (a copy of the CSR slice, in loader order). */
    public int[] childrenOf(int i) { return Arrays.copyOfRange(fwdTarget, fwdOffset[i], fwdOffset[i + 1]); }

    /** Parents of {@code i} (a copy of the CSR slice, in loader order). */
    public int[] parentsOf(int i)  { return Arrays.copyOfRange(revTarget, revOffset[i], revOffset[i + 1]); }

    /** Ids of the set bits, in ascending index order. */
    public Set<String> toIds(long[] bits) {
        Set<String> out = new LinkedHashSet<>();
        BitSet64.stream(bits).forEach(i -> out.add(indexToVar[i].id()));
        return out;
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
    // Structural logging
    // -------------------------------------------------------------------------

    /**
     * Logs the full compiled graph structure at INFO level.
     *
     * <p>Called automatically by
     * {@link com.example.causal.runtime.DefaultCausalGraphRuntime#compile} after
     * every compile. When you add new internal structures, add a section here.
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
        if (patchedFrom != null) sb.append("  (SnapshotPatcher: patched from ").append(patchedFrom).append(')');

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

        sb.append("\n\n── Topo layer per node (index : id : layer) ────────────────");
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%n  [%2d]  %-30s  layer=%d", i, idOf(i), topoLayer[i]));
        }
        if (hasClosures()) {
            appendClosure(sb, "Ancestor closure", ancestorBits);
            appendClosure(sb, "Descendant closure", descendantBits);
        } else {
            sb.append("\n\n── Closures skipped: ").append(closureBytes(n))
              .append(" B exceed causal.closures.budget = ").append(closureBudget()).append(" B");
        }

        // ── Linear-Gaussian SEM parameters (when present) ────────────
        if (parameterized) {
            sb.append("\n\n── SEM parameters (linear-Gaussian) ────────────────────────");
            sb.append("\n  Per-variable (intercept μ₀, noise σ²):");
            for (String id : topoOrder) {
                Variable v = variableById.get(id);
                sb.append(String.format("%n    %-30s  intercept=%.4f  noise=%.4f",
                        id, v.intercept(), v.noiseVariance()));
            }
            sb.append("\n  Edge weights (from → to : β):");
            for (String to : topoOrder) {
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

    /** One row per node: the bit row (column j = node j) when n ≤ 64, else the row cardinality. */
    private void appendClosure(StringBuilder sb, String title, long[][] bits) {
        sb.append("\n\n── ").append(title)
          .append(n <= 64 ? " (row i, column j = index j) " : " (row i : |set|) ")
          .append("─".repeat(Math.max(0, 40 - title.length())));
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%n  [%2d]  %s", i,
                    n <= 64 ? BitSet64.toBinaryString(bits[i], n) : String.valueOf(BitSet64.cardinality(bits[i]))));
        }
    }

    // -------------------------------------------------------------------------

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> src) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : src.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        return copy;
    }

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
