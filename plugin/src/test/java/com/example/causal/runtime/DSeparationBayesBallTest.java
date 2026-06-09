package com.example.causal.runtime;

import com.example.causal.api.DSeparationResult;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.algorithms.TopologicalSorter;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DSeparationBayesBallTest {

    @Test
    void chainIsBlockedWhenConditioningOnMiddle() throws Exception {
        CompiledCausalGraph g = graphFromEdges(List.of("A->B", "B->C"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        DSeparationResult noCondition = rt.isDSeparated(setOf("A"), setOf("C"), Set.of());
        assertFalse(noCondition.separated(), "A and C should be dependent without conditioning on B");

        DSeparationResult conditionOnB = rt.isDSeparated(setOf("A"), setOf("C"), setOf("B"));
        assertTrue(conditionOnB.separated(), "A and C should be d-separated given B");
    }

    @Test
    void forkIsBlockedWhenConditioningOnCommonCause() throws Exception {
        // B -> A and B -> C
        CompiledCausalGraph g = graphFromEdges(List.of("B->A", "B->C"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        DSeparationResult noCondition = rt.isDSeparated(setOf("A"), setOf("C"), Set.of());
        assertFalse(noCondition.separated(), "A and C should be dependent without conditioning on B");

        DSeparationResult conditionOnB = rt.isDSeparated(setOf("A"), setOf("C"), setOf("B"));
        assertTrue(conditionOnB.separated(), "A and C should be d-separated given B");
    }

    @Test
    void colliderIsOnlyOpenedWhenConditioningOnColliderOrDescendant() throws Exception {
        // A -> B <- C
        CompiledCausalGraph colliderGraph = graphFromEdges(List.of("A->B", "C->B"));
        DefaultCausalGraphRuntime rt1 = runtimeWithSnapshot(colliderGraph);

        DSeparationResult noCondition = rt1.isDSeparated(setOf("A"), setOf("C"), Set.of());
        assertTrue(noCondition.separated(), "A and C should be d-separated when collider B is unobserved");

        DSeparationResult conditionOnB = rt1.isDSeparated(setOf("A"), setOf("C"), setOf("B"));
        assertFalse(conditionOnB.separated(), "A and C should be dependent when conditioning on collider B");

        // A -> B <- C and B -> D, condition on descendant D
        CompiledCausalGraph extended = graphFromEdges(List.of("A->B", "C->B", "B->D"));
        DefaultCausalGraphRuntime rt2 = runtimeWithSnapshot(extended);

        DSeparationResult conditionOnDescendant = rt2.isDSeparated(setOf("A"), setOf("C"), setOf("D"));
        assertFalse(conditionOnDescendant.separated(),
                "A and C should be dependent when a collider's descendant D is conditioned on");
    }

    @Test
    void overlappingEndpointsAreNotDSeparated() throws Exception {
        CompiledCausalGraph g = graphFromEdges(List.of("A->B"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        DSeparationResult overlap = rt.isDSeparated(setOf("A"), setOf("A"), Set.of());
        assertFalse(overlap.separated());
    }

    private static DefaultCausalGraphRuntime runtimeWithSnapshot(CompiledCausalGraph graph) throws Exception {
        DefaultCausalGraphRuntime rt = new DefaultCausalGraphRuntime();
        Field snapshotField = DefaultCausalGraphRuntime.class.getDeclaredField("snapshot");
        snapshotField.setAccessible(true);
        snapshotField.set(rt, graph);
        return rt;
    }

    private static Set<VariableRef> setOf(String... ids) {
        Set<VariableRef> out = new LinkedHashSet<>();
        for (String id : ids) out.add(new VariableRef(id));
        return out;
    }

    private static CompiledCausalGraph graphFromEdges(List<String> edges) {
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
        nodes.forEach(id -> variableById.put(id, Variable.from(id, id, true, false)));

        List<String> topoOrder = TopologicalSorter.sort(nodes, forward);

        try {
            Constructor<CompiledCausalGraph> constructor = CompiledCausalGraph.class.getDeclaredConstructor(
                    Map.class, Map.class, Map.class, List.class, Instant.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(variableById, forward, reverse, topoOrder, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to build test compiled graph", e);
        }
    }
}
