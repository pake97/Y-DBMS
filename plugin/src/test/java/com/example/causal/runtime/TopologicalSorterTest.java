package com.example.causal.runtime;

import com.example.causal.runtime.algorithms.TopologicalSorter;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TopologicalSorter}.
 * No Neo4j harness needed — pure Java logic.
 */
class TopologicalSorterTest {

    @Test
    void singleNode() {
        List<String> order = TopologicalSorter.sort(Set.of("A"), Map.of("A", Set.of()));
        assertEquals(List.of("A"), order);
    }

    @Test
    void simpleChain() {
        // A → B → C
        Set<String> nodes = new LinkedHashSet<>(List.of("A", "B", "C"));
        Map<String, Set<String>> adj = new HashMap<>();
        adj.put("A", Set.of("B"));
        adj.put("B", Set.of("C"));
        adj.put("C", Set.of());

        List<String> order = TopologicalSorter.sort(nodes, adj);

        assertEquals(3, order.size());
        assertTrue(order.indexOf("A") < order.indexOf("B"), "A must precede B");
        assertTrue(order.indexOf("B") < order.indexOf("C"), "B must precede C");
    }

    @Test
    void diamondDag() {
        // A → B, A → C, B → D, C → D
        Set<String> nodes = new LinkedHashSet<>(List.of("A", "B", "C", "D"));
        Map<String, Set<String>> adj = new HashMap<>();
        adj.put("A", Set.of("B", "C"));
        adj.put("B", Set.of("D"));
        adj.put("C", Set.of("D"));
        adj.put("D", Set.of());

        List<String> order = TopologicalSorter.sort(nodes, adj);

        assertEquals(4, order.size());
        assertTrue(order.indexOf("A") < order.indexOf("B"));
        assertTrue(order.indexOf("A") < order.indexOf("C"));
        assertTrue(order.indexOf("B") < order.indexOf("D"));
        assertTrue(order.indexOf("C") < order.indexOf("D"));
    }

    @Test
    void disconnectedNodes() {
        // A → B   C (isolated)
        Set<String> nodes = new LinkedHashSet<>(List.of("A", "B", "C"));
        Map<String, Set<String>> adj = new HashMap<>();
        adj.put("A", Set.of("B"));
        adj.put("B", Set.of());
        adj.put("C", Set.of());

        List<String> order = TopologicalSorter.sort(nodes, adj);

        assertEquals(3, order.size());
        assertTrue(order.indexOf("A") < order.indexOf("B"));
    }

    @Test
    void cycleThrows() {
        // A → B → A
        Set<String> nodes = new LinkedHashSet<>(List.of("A", "B"));
        Map<String, Set<String>> adj = new HashMap<>();
        adj.put("A", Set.of("B"));
        adj.put("B", Set.of("A"));

        assertThrows(IllegalStateException.class, () -> TopologicalSorter.sort(nodes, adj));
    }
}
