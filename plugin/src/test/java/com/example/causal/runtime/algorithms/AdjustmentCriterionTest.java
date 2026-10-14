package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.testsupport.Graphs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generalized adjustment criterion (Shpitser, VanderWeele and Robins 2010; Perković et al.
 * 2018) on hand-built DAGs where it differs from Pearl's backdoor criterion.
 */
class AdjustmentCriterionTest {

    private static boolean valid(CompiledCausalGraph g, String t, String y, String... z) {
        long[] zb = new long[g.wordCount()];
        for (String v : z) BitSet64.set(zb, g.indexOf(v));
        return Adjustment.isValid(g, g.indexOf(t), g.indexOf(y), zb);
    }

    @Test
    void descendantOfTreatmentOffTheCausalPathIsAllowed() {
        // (i) T -> Y, T -> D: D is a descendant of T but not of a causal node, so {D} is a valid
        // adjustment set under the generalized criterion; the backdoor criterion rejects every
        // descendant of T.
        CompiledCausalGraph g = Graphs.fromEdges(List.of("T->Y", "T->D"));
        assertTrue(valid(g, "T", "Y", "D"));
        assertTrue(valid(g, "T", "Y"));
        assertEquals(Set.of("T", "Y"), g.toIds(new PathAnalyzer(g).forbiddenBits(g.indexOf("T"), g.indexOf("Y"))));
    }

    @Test
    void confounderIsValidMediatorIsForbidden() {
        // (ii) T -> M -> Y, C -> T, C -> Y: {C} valid; {M} and {C, M} contain a causal node.
        CompiledCausalGraph g = Graphs.fromEdges(List.of("T->M", "M->Y", "C->T", "C->Y"));
        assertTrue(valid(g, "T", "Y", "C"));
        assertFalse(valid(g, "T", "Y", "M"));
        assertFalse(valid(g, "T", "Y", "C", "M"));
        assertFalse(valid(g, "T", "Y"));
        Adjustment.Decision d = Adjustment.decide(g, g.indexOf("T"), g.indexOf("Y"));
        assertTrue(d.identifiable());
        assertEquals(Set.of("C"), g.toIds(d.canonical()));
    }

    @Test
    void generalizedCriterionAdmitsSetsWithNonCausalDescendants() {
        // (iii) T -> M -> Y, Z -> T, Z -> Y, T -> D, D <- W -> Y: the canonical set {Z, W} is
        // valid, and so is {Z, W, D}: D (a descendant of T off every causal path) is admitted by
        // the generalized criterion; conditioning on D alone would open T -> D <- W -> Y, which
        // W then blocks. The backdoor criterion admits neither set that contains D.
        CompiledCausalGraph g = Graphs.fromEdges(List.of("T->M", "M->Y", "Z->T", "Z->Y", "T->D", "W->D", "W->Y"));
        Adjustment.Decision d = Adjustment.decide(g, g.indexOf("T"), g.indexOf("Y"));
        assertTrue(d.identifiable());
        assertEquals(Set.of("Z", "W"), g.toIds(d.canonical()));
        assertTrue(valid(g, "T", "Y", "Z", "W", "D"));
        assertFalse(valid(g, "T", "Y", "Z", "D"), "D opens T -> D <- W -> Y unless W is adjusted");
        assertTrue(valid(g, "T", "Y", "Z"), "{Z} is the minimal set");
    }

    @Test
    void latentConfounderMakesTheEffectNonIdentifiable() {
        // (iv) and addendum A: U -> T, U -> Y, T -> Y. With U latent no adjustment set exists;
        // with U observed the canonical set is {U}.
        CompiledCausalGraph latent = Graphs.fromEdgesWithLatent(List.of("U->T", "U->Y", "T->Y"), Set.of("U"));
        Adjustment.Decision dl = Adjustment.decide(latent, latent.indexOf("T"), latent.indexOf("Y"));
        assertFalse(dl.identifiable());
        assertTrue(Adjustment.findMinimal(latent, latent.indexOf("T"), latent.indexOf("Y")).isEmpty());

        CompiledCausalGraph observed = Graphs.fromEdges(List.of("U->T", "U->Y", "T->Y"));
        Adjustment.Decision d = Adjustment.decide(observed, observed.indexOf("T"), observed.indexOf("Y"));
        assertTrue(d.identifiable());
        assertEquals(Set.of("U"), observed.toIds(d.canonical()));
    }

    @Test
    void noDirectedPathStillHasAWellDefinedDecision() {
        // T does not reach Y: cn is empty, forb = {T}, and the criterion still applies.
        CompiledCausalGraph g = Graphs.fromEdges(List.of("C->T", "C->Y"));
        Adjustment.Decision d = Adjustment.decide(g, g.indexOf("T"), g.indexOf("Y"));
        assertTrue(d.identifiable());
        assertEquals(Set.of("C"), g.toIds(d.canonical()));
        assertEquals(Set.of("T"), g.toIds(d.forbidden()));
    }
}
