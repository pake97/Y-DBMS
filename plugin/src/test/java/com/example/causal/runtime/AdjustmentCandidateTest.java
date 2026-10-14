package com.example.causal.runtime;

import com.example.causal.api.AdjustmentCandidateResult;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.testsupport.Graphs;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjustmentCandidateTest {

    @Test
    void returnsEmptySetWhenBackdoorAlreadyBlocked() throws Exception {
        CompiledCausalGraph g = Graphs.fromEdges(List.of("T->Y"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertTrue(result.adjustmentSetExists());
        assertEquals(List.of(Set.of()), result.candidateSets());
    }

    @Test
    void excludesDescendantsOfTreatmentFromCandidateSets() throws Exception {
        // backdoor blocker is Z; M is a descendant of T and must never be proposed.
        CompiledCausalGraph g = Graphs.fromEdges(List.of("Z->T", "Z->Y", "T->M"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertTrue(result.adjustmentSetExists());
        assertEquals(1, result.candidateSets().size());
        assertEquals(setOf("Z"), result.candidateSets().get(0));
        assertFalse(result.candidateSets().get(0).contains(ref("M")));
    }

    @Test
    void returnsCanonicalSetWhenSeveralMinimalSetsExist() throws Exception {
        // both Z and W alone block the only backdoor path T <- Z -> W -> Y.
        // candidateAdjustmentSet is now the canonical-set decision (WP2): the canonical set
        // (an(T) ∪ an(Y)) \ forb = {Z, W} is valid; the minimal sets {Z}, {W} come from
        // adjustmentSets(T, Y, "all-minimal", limit).
        CompiledCausalGraph g = Graphs.fromEdges(List.of("Z->T", "Z->W", "W->Y"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertTrue(result.adjustmentSetExists());
        assertEquals(List.of(setOf("Z", "W")), result.candidateSets());
    }

    @Test
    void returnsExactlyOneSetInDenseIndexOrder() throws Exception {
        // The canonical decision returns one set, its ids in dense-index order (Z before W);
        // the old level-wise enumeration returned [{Z}, {W}] in topo-combination order.
        CompiledCausalGraph g = Graphs.fromEdges(List.of("Z->T", "Z->W", "W->Y"));
        DefaultCausalGraphRuntime rt = runtimeWithSnapshot(g);

        AdjustmentCandidateResult result = rt.candidateAdjustmentSet(ref("T"), ref("Y"));

        assertTrue(result.adjustmentSetExists());
        assertEquals(1, result.candidateSets().size());
        assertEquals(List.of(ref("Z"), ref("W")), List.copyOf(result.candidateSets().get(0)));
    }

    @Test
    void returnsFalseAndNoCandidateSetWhenNoAdjustmentExists() throws Exception {
        // Y -> T creates a backdoor path T <- Y, but Y is excluded from candidate sets as outcome.
        CompiledCausalGraph g = Graphs.fromEdges(List.of("Y->T"));
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
        rt.install(graph);
        return rt;
    }

    private static Set<VariableRef> setOf(String... ids) {
        Set<VariableRef> out = new LinkedHashSet<>();
        for (String id : ids) out.add(ref(id));
        return Collections.unmodifiableSet(out);
    }

}
