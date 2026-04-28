package com.example.causal.runtime;

import com.example.causal.api.*;
import com.example.causal.runtime.algorithms.PathAnalyzer;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphLoader;

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

        // Vacuous cases: in standard definitions X and Y are disjoint/non-empty,
        // but we handle empty sets defensively.
        if (x == null || x.isEmpty() || y == null || y.isEmpty()) {
            return DSeparationResult.separated("empty `x` or `y` => vacuously d-separated");
        }

        Set<String> xIds = new LinkedHashSet<>();
        for (VariableRef v : x) xIds.add(v.id());
        Set<String> yIds = new LinkedHashSet<>();
        for (VariableRef v : y) yIds.add(v.id());

        Set<String> zIds = new LinkedHashSet<>();
        if (z != null) for (VariableRef v : z) zIds.add(v.id());

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

        // BFS over (node, direction) states.
        Deque<State> queue = new ArrayDeque<>();
        Set<State> visited = new HashSet<>();

        // Start a ball at every node in `x`, entering it from both directions.
        for (String start : xIds) {
            queue.add(new State(start, Dir.FROM_PARENT));
            queue.add(new State(start, Dir.FROM_CHILD));
        }

        while (!queue.isEmpty()) {
            State cur = queue.poll();
            if (!visited.add(cur)) continue;

            // Reaching any node in `y` means there exists an open active path.
            if (yIds.contains(cur.nodeId())) {
                return DSeparationResult.connected(
                        "found an active path reaching `" + cur.nodeId() + "`"
                );
            }

            boolean observed = zIds.contains(cur.nodeId());

            if (observed) {
                // Observed node:
                //   - ball entering from a parent can propagate to parents
                //     e.g., in a collider A -> M <- B, observing M opens
                //     the path and allows movement from M toward A or B.
                //   - ball entering from a child cannot propagate further
                //     e.g., in a chain A -> M -> B or fork A <- M -> B;
                //     conditioning on M blocks continuation through M.
                if (cur.dir() == Dir.FROM_PARENT) {
                    for (String p : g.parentsOf(cur.nodeId())) {
                        queue.add(new State(p, Dir.FROM_CHILD));
                    }
                }
            } else {
                // Unobserved node:
                //   - entering from parent: propagate to children as from parent
                //     e.g., chain A -> M -> B can continue through M.
                //   - entering from child: propagate to parents as from child,
                //     and to children as from parent
                //     e.g., in a fork A <- M -> B, arriving from A can
                //     continue to B.
                //     However, in both cases; in A -> M <- B (collider), unobserved M will
                //     still block crossing between parents under these transitions.
                if (cur.dir() == Dir.FROM_PARENT) {
                    for (String c : g.childrenOf(cur.nodeId())) {
                        queue.add(new State(c, Dir.FROM_PARENT));
                    }
                } else {
                    for (String p : g.parentsOf(cur.nodeId())) {
                        queue.add(new State(p, Dir.FROM_CHILD));
                    }
                    for (String c : g.childrenOf(cur.nodeId())) {
                        queue.add(new State(c, Dir.FROM_PARENT));
                    }
                }
            }
        }

        return DSeparationResult.separated("no active path connects `x` to `y` given `z`");
    }

    @Override
    public AdjustmentCandidateResult candidateAdjustmentSet(VariableRef treatment, VariableRef outcome) {
        requireSnapshot();
        // TODO(student, tier 1): implement the backdoor criterion.
        // Do isDSeparated first — this builds on it.
        //
        // A set Z satisfies the backdoor criterion relative to (treatment, outcome):
        //   1. No node in Z is a descendant of treatment
        //      (ReachabilityIndex.descendants(treatment)).
        //   2. Z blocks every backdoor path from treatment to outcome — i.e.
        //      treatment ⟂ outcome | Z in the graph with treatment's *outgoing*
        //      edges removed. Reuse isDSeparated on that mutilated graph.
        //
        // Candidate-set search: a common starting point is the parents of the
        // treatment, or subsets of the non-descendant variables; collect every Z
        // that satisfies (1) and (2) into candidateSets.
        //
        // Return new AdjustmentCandidateResult(exists, candidateSets, explanation)
        // — adjustmentSetExists is true iff at least one valid Z was found.
        return AdjustmentCandidateResult.unimplemented();
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

    private static Set<VariableRef> toRefs(Set<String> ids) {
        Set<VariableRef> result = new LinkedHashSet<>();
        for (String id : ids) result.add(new VariableRef(id));
        return Collections.unmodifiableSet(result);
    }

    // -------------------------------------------------------------------------

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
