package com.example.causal.bench.baseline;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Copy of the plugin's test-scope {@code DSeparationReference} (the plugin publishes no test jar).
 * Reference Bayes-Ball on id sets: the pre-WP2 {@code bayesBallDSeparated} of
 * {@code DefaultCausalGraphRuntime}, unchanged, for the parity tests and as the baseline of
 * the evaluation. {@code cutOutgoing}: nodes whose outgoing edges are all ignored.
 */
public final class DSeparationReference {

    private DSeparationReference() {}

    public static boolean separated(CompiledCausalGraph g, Set<String> xIds, Set<String> yIds,
                                    Set<String> zIds, Set<String> nodesWithCutOutgoingEdges) {
        if (xIds.isEmpty() || yIds.isEmpty()) return true;
        for (String id : xIds) {
            if (yIds.contains(id)) return false;
        }

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
            if (yIds.contains(cur.nodeId())) return false;

            boolean observed = zIds.contains(cur.nodeId());
            if (observed) {
                if (cur.dir() == Dir.FROM_PARENT) {
                    for (String p : g.parentsOf(cur.nodeId())) queue.add(new State(p, Dir.FROM_CHILD));
                }
            } else {
                if (cur.dir() == Dir.FROM_PARENT) {
                    for (String c : g.childrenOf(cur.nodeId())) {
                        if (!nodesWithCutOutgoingEdges.contains(cur.nodeId())) queue.add(new State(c, Dir.FROM_PARENT));
                    }
                } else {
                    for (String p : g.parentsOf(cur.nodeId())) queue.add(new State(p, Dir.FROM_CHILD));
                    for (String c : g.childrenOf(cur.nodeId())) {
                        if (!nodesWithCutOutgoingEdges.contains(cur.nodeId())) queue.add(new State(c, Dir.FROM_PARENT));
                    }
                }
            }
        }
        return true;
    }
}
