package com.example.causal.runtime;

import com.example.causal.api.*;
import com.example.causal.runtime.algorithms.LinearGaussianInference;
import com.example.causal.runtime.algorithms.PathAnalyzer;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.algorithms.CounterfactualInference;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphLoader;
import com.example.causal.dto.CounterfactualRecord;
import com.example.causal.dto.LinearGaussianRecord;

import java.util.*;

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
 * <h2>Student A implementation surface</h2>
 * <ul>
 *   <li>{@link #isDSeparated} — implement Bayes Ball or Shpitser's algorithm.</li>
 *   <li>{@link #candidateAdjustmentSet} — implement backdoor criterion (or
 *       front-door, or ID algorithm) against the compiled graph.</li>
 *   <li>Replace per-query {@link ReachabilityIndex} construction with a cached
 *       index built at compile time once the bitset layout is ready.</li>
 * </ul>
 */
public final class DefaultCausalGraphRuntime implements CausalGraphRuntime {

    private volatile CompiledCausalGraph snapshot;

    // TODO(student, tier 2): cache a ReachabilityIndex here once bitsets are
    // pre-computed in CompiledCausalGraph. Tier 1 work can ignore this — the
    // current per-query allocation is cheap against today's DFS implementation.
    // private volatile ReachabilityIndex reachabilityIndex;

    public DefaultCausalGraphRuntime() {}

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public synchronized GraphSnapshot compile(GraphCompileOptions options) {
        snapshot = GraphLoader.load(options);
        snapshot.logStructure();  // prints adjacency lists + topo order — check the log to verify your structures
        // TODO(student, tier 2): rebuild the cached ReachabilityIndex here once
        // CompiledCausalGraph owns the ancestor/descendant bitsets.
        return snapshot;
    }

    @Override
    public synchronized void invalidate() {
        snapshot = null;
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
        CompiledCausalGraph g = requireSnapshot();
        ReachabilityIndex idx = new ReachabilityIndex(g);
        Set<VariableRef> result = new LinkedHashSet<>();
        for (VariableRef v : vars) {
            for (String aid : idx.ancestors(v.id())) result.add(new VariableRef(aid));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<VariableRef> descendants(Set<VariableRef> vars) {
        CompiledCausalGraph g = requireSnapshot();
        ReachabilityIndex idx = new ReachabilityIndex(g);
        Set<VariableRef> result = new LinkedHashSet<>();
        for (VariableRef v : vars) {
            for (String did : idx.descendants(v.id())) result.add(new VariableRef(did));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public boolean hasDirectedPath(VariableRef x, VariableRef y) {
        return new ReachabilityIndex(requireSnapshot()).hasDirectedPath(x.id(), y.id());
    }

    // -------------------------------------------------------------------------
    // Causal identification — Tier 1 for Student A
    // -------------------------------------------------------------------------

    @Override
    public DSeparationResult isDSeparated(Set<VariableRef> x, Set<VariableRef> y, Set<VariableRef> z) {
        CompiledCausalGraph g = requireSnapshot();

        Set<String> xIds = toIds(x);
        Set<String> yIds = toIds(y);
        Set<String> zIds = toIds(z);

        return bayesBallDSeparated(
                g,
                xIds,
                yIds,
                zIds,
                Set.of() // don't cut any outgoing edges from treatment (default d-separation behavior)
        );
    }

    @Override
    public AdjustmentCandidateResult candidateAdjustmentSet(VariableRef treatment, VariableRef outcome) {
        CompiledCausalGraph g = requireSnapshot();
        if (treatment == null || outcome == null) {
            throw new IllegalArgumentException("`treatment` and `outcome` must be non-null");
        }

        String treatmentId = treatment.id();
        String outcomeId = outcome.id();

        if (treatmentId.equals(outcomeId)) {
            return new AdjustmentCandidateResult(
                    true,
                    List.of(Set.of()),
                    "`treatment` equals `outcome`; empty adjustment set is sufficient"
            );
        }

        if (isBackdoorBlocked(g, treatmentId, outcomeId, Set.of())) {
            return new AdjustmentCandidateResult(
                    true,
                    List.of(Set.of()),
                    "empty set blocks all backdoor paths"
            );
        }

        List<String> eligibleIds = eligibleAdjustmentIds(g, treatmentId, outcomeId);
        for (int size = 1; size <= eligibleIds.size(); size++) {
            List<Set<VariableRef>> minimalCandidates = new ArrayList<>();
            for (Set<String> candidateIds : combinationsOfSize(eligibleIds, size)) {
                if (isBackdoorBlocked(g, treatmentId, outcomeId, candidateIds)) {
                    minimalCandidates.add(toRefs(candidateIds));
                }
            }

            if (!minimalCandidates.isEmpty()) {
                return new AdjustmentCandidateResult(
                        true,
                        Collections.unmodifiableList(minimalCandidates),
                        "found " + minimalCandidates.size() + " minimal backdoor adjustment set(s)"
                );
            }
        }

        return new AdjustmentCandidateResult(
                false,
                List.of(),
                "no valid adjustment set satisfies the backdoor criterion"
        );
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

    private List<String> eligibleAdjustmentIds(
            CompiledCausalGraph g,
            String treatmentId,
            String outcomeId
    ) {
        Set<String> descendantsOfTreatment = new ReachabilityIndex(g).descendants(treatmentId);
        List<String> eligibleIds = new ArrayList<>();
        for (String id : g.topoOrder()) {
            if (id.equals(treatmentId) || id.equals(outcomeId)) continue;
            // If the node is a descendant of the treatment, it is not eligible for adjustment (since it would block active causal paths in case of chain and open collider paths)
            if (descendantsOfTreatment.contains(id)) continue;
            eligibleIds.add(id);
        }
        return eligibleIds;
    }

    private List<Set<String>> combinationsOfSize(List<String> values, int size) {
        if (size <= 0 || size > values.size()) return List.of();
        List<Set<String>> combinations = new ArrayList<>();
        buildCombinations(values, size, 0, new ArrayList<>(), combinations);
        return combinations;
    }

    private void buildCombinations(
            List<String> values,
            int targetSize,
            int startIndex,
            List<String> current,
            List<Set<String>> sink
    ) {
        if (current.size() == targetSize) {
            sink.add(new LinkedHashSet<>(current));
            return;
        }

        int remainingNeeded = targetSize - current.size();
        int maxStart = values.size() - remainingNeeded;
        for (int i = startIndex; i <= maxStart; i++) {
            current.add(values.get(i));
            buildCombinations(values, targetSize, i + 1, current, sink);
            current.remove(current.size() - 1);
        }
    }

    /**
     * Returns true if {@code conditionedOn} blocks all backdoor paths from
     * {@code treatmentId} to {@code outcomeId}. Outgoing edges from
     * {@code treatmentId} are cut so causal paths are not traversed.
     */
    private boolean isBackdoorBlocked(
            CompiledCausalGraph g,
            String treatmentId,
            String outcomeId,
            Set<String> conditionedOn
    ) {
        return bayesBallDSeparated(
                g,
                Set.of(treatmentId),
                Set.of(outcomeId),
                conditionedOn,
                Set.of(treatmentId)   // cut outgoing edges so causal paths are not traversed
        ).separated();
    }

    /**
    * Returns a DSeparationResult object that tests if the variable sets X and Y are d-separated given Z. 
    *
    * @param  g the compiled causal graph
    * @param  xIds the set of variable IDs for the first variable set
    * @param  yIds the set of variable IDs for the second variable set
    * @param  zIds the set of variable IDs for the conditioning set (observed variables)
    * @param  nodesWithCutOutgoingEdges the set of variable IDs for the nodes with cut outgoing edges
    * @return      the DSeparationResult object
    */
    private DSeparationResult bayesBallDSeparated(
            CompiledCausalGraph g,
            Set<String> xIds,
            Set<String> yIds,
            Set<String> zIds,
            Set<String> nodesWithCutOutgoingEdges
    ) {
        // Vacuous cases: in standard definitions X and Y are disjoint/non-empty,
        // but we handle empty sets defensively.
        if (xIds.isEmpty() || yIds.isEmpty()) {
            return DSeparationResult.separated("empty `x` or `y` => vacuously d-separated");
        }

        // If endpoints overlap, there is a length-0 path connecting them.
        for (String id : xIds) {
            if (yIds.contains(id)) {
                return DSeparationResult.connected("`x` and `y` overlap at `" + id + "`");
            }
        }

        // Bayes ball (approach a): traverse on the original DAG directly.
        //
        // We represent where the ball came from when entering the current node:
        //   - FROM_PARENT: entered from a parent into this node
        //   - FROM_CHILD: entered from a child into this node
        enum Dir { FROM_CHILD, FROM_PARENT }
        record State(String nodeId, Dir dir) {}

        Deque<State> queue = new ArrayDeque<>();
        Set<State> visited = new HashSet<>();

        for (String start : xIds) {
            queue.add(new State(start, Dir.FROM_PARENT));
            queue.add(new State(start, Dir.FROM_CHILD));
        }

        while (!queue.isEmpty()) {
            State cur = queue.poll();
            if (!visited.add(cur)) continue;

            if (yIds.contains(cur.nodeId())) {
                return DSeparationResult.connected(
                        "found an active path reaching `" + cur.nodeId() + "`"
                );
            }

            boolean observed = zIds.contains(cur.nodeId());

            if (observed) {
                if (cur.dir() == Dir.FROM_PARENT) {
                    for (String p : g.parentsOf(cur.nodeId())) {
                        queue.add(new State(p, Dir.FROM_CHILD));
                    }
                }
            } else {
                if (cur.dir() == Dir.FROM_PARENT) {
                    for (String c : g.childrenOf(cur.nodeId())) {
                        if (!nodesWithCutOutgoingEdges.contains(cur.nodeId())) {
                            queue.add(new State(c, Dir.FROM_PARENT));
                        }
                    }
                } else {
                    for (String p : g.parentsOf(cur.nodeId())) {
                        queue.add(new State(p, Dir.FROM_CHILD));
                    }
                    for (String c : g.childrenOf(cur.nodeId())) {
                        if (!nodesWithCutOutgoingEdges.contains(cur.nodeId())) {
                            queue.add(new State(c, Dir.FROM_PARENT));
                        }
                    }
                }
            }
        }

        return DSeparationResult.separated("no active path connects `x` to `y` given `z`");
    }

    // -------------------------------------------------------------------------
    // Path-based structural queries — delegate to PathAnalyzer (implemented).
    // These are the worked Tier 1 example; isDSeparated / candidateAdjustmentSet
    // above follow the same delegation-to-an-algorithm-class shape.
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
}
