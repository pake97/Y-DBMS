package com.example.causal.runtime.algorithms;

import org.junit.jupiter.api.Test;
import static com.example.causal.testsupport.GraphTestSupport.graphFromEdges;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PathAnalyzer} path-based queries.
 * T: Treatement
 * Y: Outcome
 */
class PathAnalyzerTest {

    @Test
    void backdoorVariablesIncludeNodesOnBackdoorPathNotOnlyConfounders() {
        // Z -> T, Z -> M -> Y creates backdoor path T <- Z -> M -> Y.
        PathAnalyzer analyzer = new PathAnalyzer(graphFromEdges(
                List.of("Z->T", "Z->M", "M->Y")
        ));

        Set<String> actual = analyzer.backdoorVariables("T", "Y");

        assertEquals(Set.of("Z", "M"), actual);
    }

    @Test
    void backdoorVariablesExcludeDescendantsOfTreatment() {
        // Z -> T and Z -> Y creates a backdoor path through Z.
        // T -> D -> Y is a directed causal path; D is descendant of T and must be excluded.
        PathAnalyzer analyzer = new PathAnalyzer(graphFromEdges(
                List.of("Z->T", "Z->Y", "T->D", "D->Y")
        ));

        Set<String> actual = analyzer.backdoorVariables("T", "Y");

        assertEquals(Set.of("Z"), actual);
        assertTrue(!actual.contains("D"));
    }

    @Test
    void backdoorVariablesReturnEmptyWhenNoParentOfTreatment() {
        // T has no parents, so no path can start with an edge into T.
        PathAnalyzer analyzer = new PathAnalyzer(graphFromEdges(
                List.of("T->M", "M->Y")
        ));

        Set<String> actual = analyzer.backdoorVariables("T", "Y");

        assertTrue(actual.isEmpty());
    }

    @Test
    void backdoorVariablesIncludeMultipleNodesBetweenTreatmentParentAndOutcome() {
        // Z is a parent of T and reaches Y through two branches:
        // T <- Z -> A -> B -> Y and T <- Z -> C -> Y.
        PathAnalyzer analyzer = new PathAnalyzer(graphFromEdges(
                List.of("Z->T", "Z->A", "A->B", "B->Y", "Z->C", "C->Y")
        ));

        Set<String> actual = analyzer.backdoorVariables("T", "Y");

        assertEquals(Set.of("Z", "A", "B", "C"), actual);
    }

    @Test
    void backdoorVariablesExcludeDescendantOfTreatmentEvenOffDirectedPathToOutcome() {
        // X is a descendant of T via T -> X, but X is not on a directed path T -> ... -> Y.
        // A separate valid backdoor route still exists: T <- Z -> W -> Y.
        PathAnalyzer analyzer = new PathAnalyzer(graphFromEdges(
                List.of("Z->T", "Z->W", "W->Y", "T->X", "Z->X", "X->Q")
        ));

        Set<String> actual = analyzer.backdoorVariables("T", "Y");

        assertEquals(Set.of("Z", "W"), actual);
        assertTrue(!actual.contains("X"));
        assertTrue(!actual.contains("Q"));
    }

}
