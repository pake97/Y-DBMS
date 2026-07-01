package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
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
