package com.example.causal.runtime;

import com.example.causal.api.AdjustmentCandidateResult;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.algorithms.TopologicalSorter;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjustmentCandidateTest {

    @Test
    void returnsEmptySetWhenBackdoorAlreadyBlocked() throws Exception {
        CompiledCausalGraph g = graphFromEdges(List.of("T->Y"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertTrue(result.adjustmentSetExists());
        assertEquals(List.of(Set.of()), result.candidateSets());
    }

    @Test
    void excludesDescendantsOfTreatmentFromCandidateSets() throws Exception {
        // backdoor blocker is Z; M is a descendant of T and must never be proposed.
        CompiledCausalGraph g = graphFromEdges(List.of("Z->T", "Z->Y", "T->M"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertTrue(result.adjustmentSetExists());
        assertEquals(1, result.candidateSets().size());
        assertEquals(setOf("Z"), result.candidateSets().get(0));
        assertFalse(result.candidateSets().get(0).contains(ref("M")));
    }

    @Test
    void returnsAllMinimalSetsWhenMultipleExist() throws Exception {
        // both Z and W alone block the only backdoor path T <- Z -> W -> Y.
        CompiledCausalGraph g = graphFromEdges(List.of("Z->T", "Z->W", "W->Y"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertTrue(result.adjustmentSetExists());
        assertEquals(2, result.candidateSets().size());
        assertTrue(result.candidateSets().contains(setOf("Z")));
        assertTrue(result.candidateSets().contains(setOf("W")));
    }

    @Test
    void returnsMinimalSetsInDeterministicTopoCombinationOrder() throws Exception {
        // both Z and W are minimal size-1 blockers; order follows eligible topo-combination order.
        CompiledCausalGraph g = graphFromEdges(List.of("Z->T", "Z->W", "W->Y"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertTrue(result.adjustmentSetExists());
        assertEquals(List.of(setOf("Z"), setOf("W")), result.candidateSets());
    }

    @Test
    void returnsFalseAndNoCandidateSetWhenNoAdjustmentExists() throws Exception {
        // Y -> T creates a backdoor path T <- Y, but Y is excluded from candidate sets as outcome.
        CompiledCausalGraph g = graphFromEdges(List.of("Y->T"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertFalse(result.adjustmentSetExists());
        assertEquals(List.of(), result.candidateSets());
    }

    private static VariableRef ref(String id) {
        return new VariableRef(id);
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
        for (String id : ids) out.add(ref(id));
        return Collections.unmodifiableSet(out);
    }

    private static CompiledCausalGraph graphFromEdges(List<String> edges) {
        Set<String> nodes = new LinkedHashSet<>();
        Map<String, Set<String>> forward = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new LinkedHashMap<>();

        edges.stream().map(edge -> edge.split("->")).forEach(parts -> {
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
