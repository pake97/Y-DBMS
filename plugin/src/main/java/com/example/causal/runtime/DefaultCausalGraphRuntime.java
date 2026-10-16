package com.example.causal.runtime;

import com.example.causal.api.AdjustmentCandidateResult;
import com.example.causal.api.AdjustmentSetsResult;
import com.example.causal.api.CausalGraphRuntime;
import com.example.causal.api.DSeparationResult;
import com.example.causal.api.GraphCompileOptions;
import com.example.causal.api.GraphSnapshot;
import com.example.causal.api.VariableRef;
import com.example.causal.dto.CounterfactualRecord;
import com.example.causal.dto.LinearGaussianRecord;
import com.example.causal.runtime.algorithms.Adjustment;
import com.example.causal.runtime.algorithms.CounterfactualInference;
import com.example.causal.runtime.algorithms.DSeparation;
import com.example.causal.runtime.algorithms.LinearGaussianInference;
import com.example.causal.runtime.algorithms.PathAnalyzer;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphLoader;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.runtime.compile.SnapshotPatcher;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default implementation of {@link CausalGraphRuntime}.
 *
 * <p>Holds a volatile reference to the current {@link CompiledCausalGraph}.
 * {@link #compile} and {@link #invalidate} are synchronized to prevent torn
 * reads. All read-only queries are lock-free once the snapshot is captured.
 *
 * <p>Neo4j instantiates procedure classes per-call, so this runtime must not
 * live inside a procedure class. It is accessed through {@link RuntimeRegistry}.
 *
 * <p>One {@link ReachabilityIndex} is built per snapshot in {@link #compile};
 * it reads the closures {@link CompiledCausalGraph} precomputes. See
 * {@code CODE-PLAN.md} for the index work plan.
 */
public final class DefaultCausalGraphRuntime implements CausalGraphRuntime {

    private volatile CompiledCausalGraph snapshot;

    private volatile ReachabilityIndex reachability;

    public DefaultCausalGraphRuntime() {}

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public synchronized GraphSnapshot compile(GraphCompileOptions options) {
        CompiledCausalGraph g = GraphLoader.load(options);
        install(g);
        g.logStructure();  // adjacency, topo order, layers, closures
        return g;
    }

    /** Publish {@code g} and its index. Package-private so unit tests can skip Neo4j. */
    synchronized void install(CompiledCausalGraph g) {
        snapshot = g;
        reachability = new ReachabilityIndex(g);
    }

    @Override
    public synchronized void invalidate() {
        snapshot = null;
        reachability = null;
    }

    /**
     * Patch the current snapshot with {@code batch} via {@link SnapshotPatcher}.
     * Returns false when there is no snapshot, the batch needs a full recompile, or
     * {@code -Dcausal.patch.enabled=false} (read per call): then the listener invalidates.
     */
    public synchronized boolean applyChange(ChangeBatch batch) {
        if (snapshot == null || !Boolean.parseBoolean(System.getProperty("causal.patch.enabled", "true"))) return false;
        CompiledCausalGraph next = SnapshotPatcher.apply(snapshot, batch);
        if (next == null) {
            return false;
        }
        snapshot = next;
        reachability = new ReachabilityIndex(next);
        return true;
    }

    // -------------------------------------------------------------------------
    // Variable resolution
    // -------------------------------------------------------------------------

    @Override
    public Optional<VariableRef> resolveVariable(String key) {
        return requireSnapshot().findVariable(key).map(v -> new VariableRef(v.id()));
    }

    // -------------------------------------------------------------------------
    // Structural queries
    // -------------------------------------------------------------------------

    @Override
    public Set<VariableRef> parents(Set<VariableRef> vars) {
        CompiledCausalGraph g = requireSnapshot();
        Set<VariableRef> result = new LinkedHashSet<>();
        for (VariableRef v : vars) {
            for (String pid : g.parentsOf(v.id())) result.add(new VariableRef(pid));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<VariableRef> children(Set<VariableRef> vars) {
        CompiledCausalGraph g = requireSnapshot();
        Set<VariableRef> result = new LinkedHashSet<>();
        for (VariableRef v : vars) {
            for (String cid : g.childrenOf(v.id())) result.add(new VariableRef(cid));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<VariableRef> ancestors(Set<VariableRef> vars) {
        ReachabilityIndex idx = requireIndex();
        Set<VariableRef> result = new LinkedHashSet<>();
        for (VariableRef v : vars) {
            for (String aid : idx.ancestors(v.id())) result.add(new VariableRef(aid));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<VariableRef> descendants(Set<VariableRef> vars) {
        ReachabilityIndex idx = requireIndex();
        Set<VariableRef> result = new LinkedHashSet<>();
        for (VariableRef v : vars) {
            for (String did : idx.descendants(v.id())) result.add(new VariableRef(did));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public boolean hasDirectedPath(VariableRef x, VariableRef y) {
        return requireIndex().hasDirectedPath(x.id(), y.id());
    }

    // -------------------------------------------------------------------------
    // Causal identification
    // -------------------------------------------------------------------------

    @Override
    public DSeparationResult isDSeparated(Set<VariableRef> x, Set<VariableRef> y, Set<VariableRef> z) {
        CompiledCausalGraph g = requireSnapshot();

        Set<String> xIds = toIds(x);
        Set<String> yIds = toIds(y);
        if (xIds.isEmpty() || yIds.isEmpty()) {
            return DSeparationResult.separated("empty `x` or `y` => vacuously d-separated");
        }
        for (String id : xIds) {
            if (yIds.contains(id)) {
                return DSeparationResult.connected("`x` and `y` overlap at `" + id + "`");
            }
        }
        long[] xb = bits(g, xIds), yb = bits(g, yIds), zb = bits(g, toIds(z));
        int reached = BitSet64.isEmpty(xb) || BitSet64.isEmpty(yb)
                ? -1 : DSeparation.firstReached(g, xb, yb, zb, null, null);
        return reached >= 0
                ? DSeparationResult.connected("found an active path reaching `" + g.idOf(reached) + "`")
                : DSeparationResult.separated("no active path connects `x` to `y` given `z`");
    }

    /** Bitset of the known ids among {@code ids} (unknown ids have no edges and are dropped). */
    private static long[] bits(CompiledCausalGraph g, Set<String> ids) {
        long[] b = new long[g.wordCount()];
        for (String id : ids) {
            int i = g.indexOf(id);
            if (i >= 0) BitSet64.set(b, i);
        }
        return b;
    }

    /**
     * The canonical-set decision of {@link Adjustment#decide} in the pre-WP2 result shape: the
     * canonical set if the effect is identifiable by adjustment, no set otherwise. Kept as an
     * alias for {@code causal.adjustmentCandidate} and the DSL until WP4 switches them.
     */
    @Override
    public AdjustmentCandidateResult candidateAdjustmentSet(VariableRef treatment, VariableRef outcome) {
        AdjustmentSetsResult r = adjustmentSets(treatment, outcome, "canonical", 1);
        return new AdjustmentCandidateResult(r.identifiable(), r.sets(), r.explanation());
    }

    @Override
    public AdjustmentSetsResult adjustmentSets(VariableRef treatment, VariableRef outcome, String mode, int limit) {
        CompiledCausalGraph g = requireSnapshot();
        if (treatment == null || outcome == null) {
            throw new IllegalArgumentException("`treatment` and `outcome` must be non-null");
        }
        if (!List.of("canonical", "minimal", "all-minimal").contains(mode)) {
            throw new IllegalArgumentException("mode must be canonical, minimal or all-minimal, got: " + mode);
        }
        int t = requireIndex(g, treatment), y = requireIndex(g, outcome);
        Adjustment.Decision d = Adjustment.decide(g, t, y);
        Set<VariableRef> canonical = toRefs(g.toIds(d.canonical()));
        Set<VariableRef> forbidden = toRefs(g.toIds(d.forbidden()));
        if (t == y) {
            return new AdjustmentSetsResult(true, canonical, forbidden, List.of(Set.of()), mode,
                    "`treatment` equals `outcome`; empty adjustment set is sufficient");
        }
        if (!d.identifiable()) {
            return new AdjustmentSetsResult(false, canonical, forbidden, List.of(), mode,
                    "not identifiable by adjustment: the canonical set does not block all non-causal paths");
        }
        List<Set<VariableRef>> sets = new java.util.ArrayList<>();
        switch (mode) {
            case "canonical" -> sets.add(canonical);
            case "minimal" -> Adjustment.findMinimal(g, t, y).ifPresent(z -> sets.add(toRefs(g.toIds(z))));
            default -> Adjustment.listMinimal(g, t, y, limit, z -> sets.add(toRefs(g.toIds(z))));
        }
        String explanation = mode.equals("all-minimal")
                ? "found " + sets.size() + " minimal adjustment set(s)"
                : "identifiable by adjustment; canonical set has " + canonical.size() + " variables";
        return new AdjustmentSetsResult(true, canonical, forbidden, List.copyOf(sets), mode, explanation);
    }

    @Override
    public int topoPosition(VariableRef v) {
        return requireSnapshot().topoIndex(v.id());
    }

    private static int requireIndex(CompiledCausalGraph g, VariableRef v) {
        int i = g.indexOf(v.id());
        if (i < 0) throw new IllegalArgumentException("Unknown variable: " + v.id());
        return i;
    }

    // -------------------------------------------------------------------------
    // private/ internal helper methods
    // main Bayes Ball algorithm implementation, getting node IDs and Refs
    // -------------------------------------------------------------------------

    private Set<VariableRef> toRefs(Set<String> ids) {
        Set<VariableRef> result = new LinkedHashSet<>();
        for (String id : ids) result.add(new VariableRef(id));
        return Collections.unmodifiableSet(result);
    }

    private Set<String> toIds(Set<VariableRef> refs) {
        if (refs == null || refs.isEmpty()) return Set.of();
        Set<String> ids = new LinkedHashSet<>();
        for (VariableRef v : refs) ids.add(v.id());
        return ids;
    }

    // -------------------------------------------------------------------------
    // Path-based structural queries — delegate to PathAnalyzer.
    // -------------------------------------------------------------------------

    @Override
    public Set<VariableRef> mediators(VariableRef from, VariableRef to) {
        return toRefs(new PathAnalyzer(requireSnapshot()).mediators(from.id(), to.id()));
    }

    @Override
    public Set<VariableRef> colliders(VariableRef x, VariableRef y) {
        return toRefs(new PathAnalyzer(requireSnapshot()).colliders(x.id(), y.id()));
    }

    @Override
    public Set<VariableRef> confounders(VariableRef x, VariableRef y) {
        return toRefs(new PathAnalyzer(requireSnapshot()).confounders(x.id(), y.id()));
    }

    @Override
    public Set<VariableRef> backdoorVariables(VariableRef treatment, VariableRef outcome) {
        return toRefs(new PathAnalyzer(requireSnapshot()).backdoorVariables(treatment.id(), outcome.id()));
    }

    // -------------------------------------------------------------------------

    @Override
    public LinearGaussianRecord linearGaussianInference(VariableRef treatment, VariableRef outcome, Map<VariableRef, Double> doValues) {
        CompiledCausalGraph g = requireSnapshot();
        if (!g.isParameterized()) {
            // Note: An identity-coefficient SEM (where explicitly all β=1, intercept=0, noise=1)
            // will currently be rejected here, as we rely on value deviation to detect parameterization.
            throw new IllegalStateException("Graph is not parameterized. Ensure linear-Gaussian parameters are set on nodes/edges.");
        }

        LinearGaussianInference engine = new LinearGaussianInference(g);

        Map<String, Double> stringDoValues = new HashMap<>();
        if (doValues != null) {
            for (Map.Entry<VariableRef, Double> e : doValues.entrySet()) {
                stringDoValues.put(e.getKey().id(), e.getValue());
            }
        }

        Map<String, Double> means = engine.interventionalMeans(stringDoValues);
        Map<String, Double> variances = engine.interventionalVariances(stringDoValues);
        Double totalEffect = engine.totalEffect(treatment.id(), outcome.id());

        return new LinearGaussianRecord(means, variances, totalEffect);
    }

    @Override
    public CounterfactualRecord counterfactual(VariableRef interventionVar, double counterfactualValue, VariableRef outcome, Map<VariableRef, Double> factualValues) {
        CompiledCausalGraph g = requireSnapshot();
        if (!g.isParameterized()) {
            throw new IllegalStateException("Graph is not parameterized. Ensure linear-Gaussian parameters are set on nodes/edges.");
        }

        Map<String, Double> factualById = new HashMap<>();
        if (factualValues != null) {
            for (Map.Entry<VariableRef, Double> e : factualValues.entrySet()) {
                factualById.put(e.getKey().id(), e.getValue());
            }
        }

        double yCf = new CounterfactualInference(g)
                .counterfactual(interventionVar.id(), counterfactualValue,
                        outcome.id(), factualById);
        double yFactual = factualById.get(outcome.id());
        
        return new CounterfactualRecord(yFactual, yCf, yCf - yFactual);
    }

    @Override
    public GraphSnapshot currentSnapshot() {
        return snapshot;
    }

    private CompiledCausalGraph requireSnapshot() {
        CompiledCausalGraph g = snapshot;
        if (g == null) {
            throw new IllegalStateException(
                    "No compiled snapshot available. Call CALL causal.compile() first.");
        }
        return g;
    }

    private ReachabilityIndex requireIndex() {
        ReachabilityIndex idx = reachability;
        if (idx == null) {
            throw new IllegalStateException(
                    "No compiled snapshot available. Call CALL causal.compile() first.");
        }
        return idx;
    }
}
