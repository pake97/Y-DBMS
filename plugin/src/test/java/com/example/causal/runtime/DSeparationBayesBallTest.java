package com.example.causal.runtime;

import com.example.causal.api.DSeparationResult;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.testsupport.Graphs;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DSeparationBayesBallTest {

    @Test
    void chainIsBlockedWhenConditioningOnMiddle() throws Exception {
        CompiledCausalGraph g = Graphs.fromEdges(List.of("A->B", "B->C"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        DSeparationResult noCondition = rt.isDSeparated(setOf("A"), setOf("C"), Set.of());
        assertFalse(noCondition.separated(), "A and C should be dependent without conditioning on B");

        DSeparationResult conditionOnB = rt.isDSeparated(setOf("A"), setOf("C"), setOf("B"));
        assertTrue(conditionOnB.separated(), "A and C should be d-separated given B");
    }

    @Test
    void forkIsBlockedWhenConditioningOnCommonCause() throws Exception {
        // B -> A and B -> C
        CompiledCausalGraph g = Graphs.fromEdges(List.of("B->A", "B->C"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        DSeparationResult noCondition = rt.isDSeparated(setOf("A"), setOf("C"), Set.of());
        assertFalse(noCondition.separated(), "A and C should be dependent without conditioning on B");

        DSeparationResult conditionOnB = rt.isDSeparated(setOf("A"), setOf("C"), setOf("B"));
        assertTrue(conditionOnB.separated(), "A and C should be d-separated given B");
    }

    @Test
    void colliderIsOnlyOpenedWhenConditioningOnColliderOrDescendant() throws Exception {
        // A -> B <- C
        CompiledCausalGraph colliderGraph = Graphs.fromEdges(List.of("A->B", "C->B"));
        DefaultCausalGraphRuntime rt1 = runtimeWithSnapshot(colliderGraph);

        DSeparationResult noCondition = rt1.isDSeparated(setOf("A"), setOf("C"), Set.of());
        assertTrue(noCondition.separated(), "A and C should be d-separated when collider B is unobserved");

        DSeparationResult conditionOnB = rt1.isDSeparated(setOf("A"), setOf("C"), setOf("B"));
        assertFalse(conditionOnB.separated(), "A and C should be dependent when conditioning on collider B");

        // A -> B <- C and B -> D, condition on descendant D
        CompiledCausalGraph extended = Graphs.fromEdges(List.of("A->B", "C->B", "B->D"));
        DefaultCausalGraphRuntime rt2 = runtimeWithSnapshot(extended);

        DSeparationResult conditionOnDescendant = rt2.isDSeparated(setOf("A"), setOf("C"), setOf("D"));
        assertFalse(conditionOnDescendant.separated(),
                "A and C should be dependent when a collider's descendant D is conditioned on");
    }

    @Test
    void overlappingEndpointsAreNotDSeparated() throws Exception {
        CompiledCausalGraph g = Graphs.fromEdges(List.of("A->B"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        DSeparationResult overlap = rt.isDSeparated(setOf("A"), setOf("A"), Set.of());
        assertFalse(overlap.separated());
    }

    private static DefaultCausalGraphRuntime runtimeWithSnapshot(CompiledCausalGraph graph) throws Exception {
        DefaultCausalGraphRuntime rt = new DefaultCausalGraphRuntime();
        rt.install(graph);
        return rt;
    }

    private static Set<VariableRef> setOf(String... ids) {
        Set<VariableRef> out = new LinkedHashSet<>();
        for (String id : ids) out.add(new VariableRef(id));
        return out;
    }

}
