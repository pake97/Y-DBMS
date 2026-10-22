package com.example.causal.bench.baseline;

import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The July level-wise enumeration of {@code DefaultCausalGraphRuntime.candidateAdjustmentSet}
 * (tag {@code v0-dfs-baseline}; WP2 deleted it from the plugin): Pearl's backdoor criterion,
 * every non-descendant of {@code t} eligible, all minimum-cardinality sets by
 * {@code combinationsOfSize}, each tested with the reference Bayes-Ball with {@code t}'s outgoing
 * edges cut. Ported unchanged except for a deadline check in the enumeration loops, so the 60 s
 * cap of the evaluation can stop it.
 */
public final class LegacyEnumeration {

    /** Thrown when the deadline passes. */
    public static final class Timeout extends RuntimeException {
        Timeout() { super(null, null, false, false); }
    }

    private final CompiledCausalGraph g;
    private final ReachabilityIndex reach;

    public LegacyEnumeration(CompiledCausalGraph g) {
        this.g = g;
        this.reach = new ReachabilityIndex(g);
    }

    /** The minimum-cardinality backdoor sets (empty list: none), or {@link Timeout} after {@code deadlineNanos}. */
    public List<Set<String>> candidateAdjustmentSets(String t, String y, long deadlineNanos) {
        if (t.equals(y) || isBackdoorBlocked(t, y, Set.of())) return List.of(Set.of());
        List<String> eligible = eligibleAdjustmentIds(t, y);
        for (int size = 1; size <= eligible.size(); size++) {
            List<Set<String>> minimal = new ArrayList<>();
            for (Set<String> candidate : combinationsOfSize(eligible, size, deadlineNanos)) {
                check(deadlineNanos);
                if (isBackdoorBlocked(t, y, candidate)) minimal.add(candidate);
            }
            if (!minimal.isEmpty()) return minimal;
        }
        return List.of();
    }

    private static void check(long deadlineNanos) {
        if (System.nanoTime() - deadlineNanos > 0) throw new Timeout();
    }

    private List<String> eligibleAdjustmentIds(String t, String y) {
        Set<String> deT = reach.descendants(t);
        List<String> out = new ArrayList<>();
        for (String id : g.topoOrder()) {
            if (id.equals(t) || id.equals(y) || deT.contains(id)) continue;
            out.add(id);
        }
        return out;
    }

    private static List<Set<String>> combinationsOfSize(List<String> values, int size, long deadlineNanos) {
        if (size <= 0 || size > values.size()) return List.of();
        List<Set<String>> out = new ArrayList<>();
        build(values, size, 0, new ArrayList<>(), out, deadlineNanos);
        return out;
    }

    private static void build(List<String> values, int target, int start, List<String> current,
                              List<Set<String>> sink, long deadlineNanos) {
        if (current.size() == target) {
            sink.add(new LinkedHashSet<>(current));
            if ((sink.size() & 0x3ff) == 0) check(deadlineNanos);
            return;
        }
        int maxStart = values.size() - (target - current.size());
        for (int i = start; i <= maxStart; i++) {
            current.add(values.get(i));
            build(values, target, i + 1, current, sink, deadlineNanos);
            current.remove(current.size() - 1);
        }
    }

    private boolean isBackdoorBlocked(String t, String y, Set<String> z) {
        return DSeparationReference.separated(g, Set.of(t), Set.of(y), z, Set.of(t));
    }
}
