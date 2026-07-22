package com.example.causal.testsupport;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.model.Variable;
import com.example.causal.runtime.algorithms.TopologicalSorter;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.*;

public final class GraphTestSupport {

    private GraphTestSupport() {}

    public static class GraphInput {
        public final Map<String, Variable> variableById;
        public final Map<String, Set<String>> forward;
        public final Map<String, Set<String>> reverse;
        public final List<String> topoOrder;

        public GraphInput(Map<String, Variable> variableById, Map<String, Set<String>> forward, Map<String, Set<String>> reverse, List<String> topoOrder) {
            this.variableById = variableById;
            this.forward = forward;
            this.reverse = reverse;
            this.topoOrder = topoOrder;
        }
    }

    public static GraphInput parseEdges(List<String> edges) {
        Set<String> nodes = new LinkedHashSet<>();
        Map<String, Set<String>> forward = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new LinkedHashMap<>();

        edges.stream()
                .map(edge -> edge.split("->"))
                .forEach(parts -> {
                    String from = parts[0];
                    String to = parts[1];
                    nodes.add(from);
                    nodes.add(to);
                    forward.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(to);
                    reverse.computeIfAbsent(to, key -> new LinkedHashSet<>()).add(from);
                });

        nodes.forEach(node -> {
            forward.putIfAbsent(node, new LinkedHashSet<>());
            reverse.putIfAbsent(node, new LinkedHashSet<>());
        });

        Map<String, Variable> variableById = new LinkedHashMap<>();
        nodes.forEach(id -> variableById.put(id, Variable.from(id, id, true, false, 0.0, 1.0)));

        List<String> topoOrder = TopologicalSorter.sort(nodes, forward);
        return new GraphInput(variableById, forward, reverse, topoOrder);
    }

    public static CompiledCausalGraph graphFromEdges(List<String> edges) {
        return buildFromInput(parseEdges(edges));
    }

    public static CompiledCausalGraph buildFromInput(GraphInput input) {
        try {
            Constructor<CompiledCausalGraph> constructor = CompiledCausalGraph.class.getDeclaredConstructor(
                    Map.class, Map.class, Map.class, List.class, Map.class, Instant.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(input.variableById, input.forward, input.reverse, input.topoOrder, Map.of(), Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to build test compiled graph", e);
        }
    }
}
