package com.example.causal.runtime.compile;

import com.example.causal.lifecycle.ChangeBatch;
import com.example.causal.runtime.algorithms.TopologicalSorter;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a transactional change batch to an already compiled snapshot.
 */
public final class CompiledCausalGraphEvolver {

    private CompiledCausalGraphEvolver() {}

    public static CompiledCausalGraph evolve(CompiledCausalGraph current, ChangeBatch batch) {
        Map<String, Variable> vars = new LinkedHashMap<>(current.variableByIdView());
        Map<String, Set<String>> forward = deepMutableCopy(current.forwardAdjView());
        Map<String, Set<String>> reverse = deepMutableCopy(current.reverseAdjView());
        Map<String, Map<String, Double>> edgeWeights = deepMutableCopyWeights(current.edgeWeightsView());

        for (String id : batch.addedVariables()) {
            vars.putIfAbsent(id, Variable.from(id, id, true, false, Variable.DEFAULT_INTERCEPT, Variable.DEFAULT_NOISE_VARIANCE));
            forward.putIfAbsent(id, new LinkedHashSet<>());
            reverse.putIfAbsent(id, new LinkedHashSet<>());
            edgeWeights.putIfAbsent(id, new LinkedHashMap<>());
        }

        for (String id : batch.removedVariables()) {
            vars.remove(id);
            forward.remove(id);
            reverse.remove(id);
            edgeWeights.remove(id); // remove inbound weights
            // also remove outbound weights
            for (Map<String, Double> inWeights : edgeWeights.values()) {
                inWeights.remove(id);
            }
        }

        for (Set<String> children : forward.values()) children.removeAll(batch.removedVariables());
        for (Set<String> parents : reverse.values()) parents.removeAll(batch.removedVariables());
        for (Map<String, Double> weights : edgeWeights.values()) weights.keySet().removeAll(batch.removedVariables());

        for (ChangeBatch.Edge e : batch.removedEdges()) {
            Set<String> children = forward.get(e.from());
            if (children != null) children.remove(e.to());
            Set<String> parents = reverse.get(e.to());
            if (parents != null) parents.remove(e.from());
            Map<String, Double> inWeights = edgeWeights.get(e.to());
            if (inWeights != null) inWeights.remove(e.from());
        }

        for (ChangeBatch.Edge e : batch.addedEdges()) {
            if (!vars.containsKey(e.from()) || !vars.containsKey(e.to())) continue;
            forward.computeIfAbsent(e.from(), k -> new LinkedHashSet<>()).add(e.to());
            reverse.computeIfAbsent(e.to(), k -> new LinkedHashSet<>()).add(e.from());
            edgeWeights.computeIfAbsent(e.to(), k -> new LinkedHashMap<>()).put(e.from(), Variable.DEFAULT_WEIGHT);
        }

        List<String> topoOrder = TopologicalSorter.sort(vars.keySet(), forward);
        return new CompiledCausalGraph(vars, forward, reverse, topoOrder, edgeWeights, Instant.now());
    }

    private static Map<String, Set<String>> deepMutableCopy(Map<String, Set<String>> src) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : src.entrySet()) {
            out.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        return out;
    }

    private static Map<String, Map<String, Double>> deepMutableCopyWeights(Map<String, Map<String, Double>> src) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : src.entrySet()) {
            out.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return out;
    }
}
