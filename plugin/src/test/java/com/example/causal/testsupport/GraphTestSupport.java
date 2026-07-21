package com.example.causal.testsupport;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.model.Variable;
import com.example.causal.runtime.algorithms.TopologicalSorter;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.*;

public final class GraphTestSupport {

    private GraphTestSupport() {}

    public static CompiledCausalGraph graphFromEdges(List<String> edges) {
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

        try {
            Constructor<CompiledCausalGraph> constructor = CompiledCausalGraph.class.getDeclaredConstructor(
                    Map.class, Map.class, Map.class, List.class, Map.class, Instant.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(variableById, forward, reverse, topoOrder, Map.of(), Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to build test compiled graph", e);
        }
    }
}
