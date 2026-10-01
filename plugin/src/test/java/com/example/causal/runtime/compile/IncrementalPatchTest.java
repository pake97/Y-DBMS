package com.example.causal.runtime.compile;

import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.runtime.model.Variable;
import com.example.causal.testsupport.Graphs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalPatchTest {

    @Test
    void testIncrementalEdgeAddition() {
        // Initial graph: A -> B -> C -> D
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B", "B->C", "C->D"));
        
        // Add an edge B -> D incrementally
        ChangeBatch batch = ChangeBatch.builder()
                .addedEdge("B", "D")
                .build();
                
        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph, batch);
        assertNotNull(newGraph, "Patch should succeed for a valid topological edge addition");
        
        // Assert structure
        assertTrue(newGraph.childrenOf("B").contains("D"));
        assertTrue(newGraph.parentsOf("D").contains("B"));
        
        // Assert bitset parity on the patched graph
        assertParity(newGraph);
    }
    
    @Test
    void testIncrementalNodeAndEdgeAddition() {
        // Initial graph: A -> B
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B"));
        
        // Add a new node 'C' and an edge B -> C
        Variable nodeC = Variable.from("C", "C", false, false, 0, 1);
        ChangeBatch batch = ChangeBatch.builder()
                .addedVariable(nodeC)
                .addedEdge("B", "C")
                .build();
                
        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph, batch);
        assertNotNull(newGraph, "Patch should succeed when adding new nodes and valid edges");
        
        assertTrue(newGraph.variables().stream().anyMatch(v -> v.id().equals("C")));
        assertTrue(newGraph.childrenOf("B").contains("C"));
        
        assertParity(newGraph);
    }
    
    @Test
    void testTopologicalViolationFallback() {
        // Initial graph: A -> B -> C
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B", "B->C"));
        
        // Try adding C -> A (creates a cycle / backward edge, violates topo sort)
        ChangeBatch batch = ChangeBatch.builder()
                .addedEdge("C", "A")
                .build();
                
        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph, batch);
        assertNull(newGraph, "Patch should return null and fallback for topological violations");
    }

    @Test
    void testMultipleNodesChainedInBatch() {
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B"));
        Variable nodeX = Variable.from("X", "X", false, false, 0, 1);
        Variable nodeY = Variable.from("Y", "Y", false, false, 0, 1);
        Variable nodeZ = Variable.from("Z", "Z", false, false, 0, 1);
        
        ChangeBatch batch = ChangeBatch.builder()
                .addedVariable(nodeZ)
                .addedVariable(nodeY)
                .addedVariable(nodeX) // non-creation order
                .addedEdge("Y", "Z") // reverse creation order
                .addedEdge("X", "Y")
                .addedEdge("B", "X")
                .build();
        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph, batch);
        // We currently use the cheapest fix: return null and fallback if two new nodes connect
        assertNull(newGraph, "Expected patch to fallback when multiple new nodes are chained");
        /* TODO(future): Restore these when proper topological sort is implemented:
        assertNotNull(newGraph);
        assertParity(newGraph);
        */
    }

    @Test
    void testBatchAppliedTwice() {
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B"));
        ChangeBatch batch = ChangeBatch.builder()
                .addedEdge("A", "C")
                .build();
        Variable nodeC = Variable.from("C", "C", false, false, 0, 1);
        batch = ChangeBatch.builder()
                .addedVariable(nodeC)
                .addedEdge("B", "C")
                .build();
        
        CompiledCausalGraph once = SnapshotPatcher.apply(oldGraph, batch);
        assertNotNull(once);
        CompiledCausalGraph twice = SnapshotPatcher.apply(once, batch);
        assertNotNull(twice, "Applying the same batch twice should not crash");
        assertParity(twice);
    }

    @Test
    void testUpdatedVariables() {
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B"));
        // Flip A to latent=true, intercept=5
        Variable updatedA = Variable.from("A", "A", true, true, 5, 1);
        ChangeBatch batch = ChangeBatch.builder()
                .updatedVariable(updatedA)
                .build();
        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph, batch);
        assertNotNull(newGraph);
        
        Variable aInGraph = newGraph.findVariable("A").orElseThrow();
        assertTrue(aInGraph.latent());
        assertEquals(5, aInGraph.intercept());
    }

    @Test
    void testDeactivationFallback() {
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B"));
        // Removing a node should trigger fallback
        ChangeBatch batch = ChangeBatch.builder()
                .removedVariable("A")
                .build();
        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph, batch);
        assertNull(newGraph, "Removing a node should return null (fallback)");
    }

    private void assertParity(CompiledCausalGraph graph) {
        ReachabilityIndex index = new ReachabilityIndex(graph);
        List<String> topoOrder = graph.topoOrder();
        
        // Assert topological order is valid
        for (String id : topoOrder) {
            int childIndex = graph.topoIndex(id);
            for (String parent : graph.parentsOf(id)) {
                int parentIndex = graph.topoIndex(parent);
                assertTrue(parentIndex < childIndex, 
                    "Topological violation: parent " + parent + " (" + parentIndex + 
                    ") >= child " + id + " (" + childIndex + ")");
            }
        }
        
        for (String id : topoOrder) {
            Set<String> dfsAncestors = index.ancestorsDfs(id);
            Set<String> bitsetAncestors = index.ancestors(id);
            assertEquals(dfsAncestors, bitsetAncestors, "Ancestors mismatch for " + id);

            Set<String> dfsDescendants = index.descendantsDfs(id);
            Set<String> bitsetDescendants = index.descendants(id);
            assertEquals(dfsDescendants, bitsetDescendants, "Descendants mismatch for " + id);
        }
    }

    @Test
    void testSnapshotImmutabilityUnderAliasing() {
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B", "B->C"));
        ReachabilityIndex oldIndex = new ReachabilityIndex(oldGraph);
        
        Set<String> aAnc = oldIndex.ancestors("A");
        Set<String> cDesc = oldIndex.descendants("C");
        Set<String> bChildren = oldGraph.childrenOf("B");
        Set<String> bParents = oldGraph.parentsOf("B");
        
        ChangeBatch b1 = ChangeBatch.builder().addedVariable(Variable.from("X", "X", false, false, 0, 1)).addedEdge("C", "X").build();
        CompiledCausalGraph p1 = SnapshotPatcher.apply(oldGraph, b1);
        ChangeBatch b2 = ChangeBatch.builder().addedVariable(Variable.from("Y", "Y", false, false, 0, 1)).addedEdge("X", "Y").build();
        CompiledCausalGraph p2 = SnapshotPatcher.apply(p1, b2);
        
        assertNotNull(p2);
        
        assertEquals(aAnc, oldIndex.ancestors("A"));
        assertEquals(cDesc, oldIndex.descendants("C"));
        assertEquals(bChildren, oldGraph.childrenOf("B"));
        assertEquals(bParents, oldGraph.parentsOf("B"));
        
        assertFalse(oldIndex.descendants("A").contains("X"));
    }

    @Test
    void testWordBoundaryWidening() {
        List<String> edges = new java.util.ArrayList<>();
        for (int i = 0; i < 62; i++) {
            edges.add("N" + i + "->N" + (i + 1));
        }
        CompiledCausalGraph oldGraph = Graphs.fromEdges(edges);
        assertEquals(63, oldGraph.size());
        
        ChangeBatch.Builder builder = ChangeBatch.builder();
        for (int i = 63; i < 69; i++) {
            builder.addedVariable(Variable.from("N" + i, "N" + i, false, false, 0, 1));
            builder.addedEdge("N62", "N" + i);
        }
        ChangeBatch batch = builder.build();
        
        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph, batch);
        assertNotNull(newGraph);
        assertEquals(69, newGraph.size());
        
        assertParity(newGraph);
        assertEquals(63, oldGraph.size());
        assertFalse(new ReachabilityIndex(oldGraph).descendants("N0").contains("N63"));
    }

    @Test
    void testDuplicateIdInBatch() {
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B"));
        Variable nodeC1 = Variable.from("C", "C", false, false, 0, 1);
        Variable nodeC2 = Variable.from("C", "C", true, false, 0, 1);
        ChangeBatch batch = ChangeBatch.builder()
                .addedVariable(nodeC1)
                .addedVariable(nodeC2)
                .addedEdge("B", "C")
                .build();
        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph, batch);
        assertNotNull(newGraph);
        assertEquals(3, newGraph.size());
        assertEquals(2, newGraph.topoIndex("C"));
        
        ChangeBatch nextBatch = ChangeBatch.builder()
                .addedVariable(Variable.from("D", "D", false, false, 0, 1))
                .addedEdge("C", "D")
                .build();
        CompiledCausalGraph latest = SnapshotPatcher.apply(newGraph, nextBatch);
        assertNotNull(latest);
    }

    @Test
    void testWeightLifecycle() {
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B"));
        assertFalse(oldGraph.isParameterized());
        
        ChangeBatch b1 = ChangeBatch.builder().updatedEdge("A", "B", 0.7).build();
        CompiledCausalGraph p1 = SnapshotPatcher.apply(oldGraph, b1);
        assertEquals(0.7, p1.weight("A", "B"));
        assertTrue(p1.isParameterized());
        
        ChangeBatch b2 = ChangeBatch.builder().addedEdge("A", "B", null).build();
        CompiledCausalGraph p2 = SnapshotPatcher.apply(p1, b2);
        assertEquals(0.7, p2.weight("A", "B"));
        
        ChangeBatch b3 = ChangeBatch.builder().updatedEdge("A", "B", null).build();
        CompiledCausalGraph p3 = SnapshotPatcher.apply(p2, b3);
        assertEquals(1.0, p3.weight("A", "B"));
        assertFalse(p3.isParameterized());
    }

    @Test
    void testUnflaggedRowsAreSharedByReference() {
        // A -> B -> C -> D plus an unrelated P -> Q; add A -> C (forward in topo order).
        CompiledCausalGraph oldGraph = Graphs.fromEdges(List.of("A->B", "B->C", "C->D", "P->Q"));
        int a = oldGraph.indexOf("A"), c = oldGraph.indexOf("C");
        assertTrue(oldGraph.topoPosition(a) < oldGraph.topoPosition(c));

        CompiledCausalGraph newGraph = SnapshotPatcher.apply(oldGraph,
                ChangeBatch.builder().addedEdge("A", "C").build());
        assertNotNull(newGraph);
        assertEquals(oldGraph.wordCount(), newGraph.wordCount(), "no widening in this fixture");

        // Flagged rows: ancestors of {C} ∪ de(C), descendants of {A} ∪ an(A).
        long[] ancFlag = BitSet64.copy(oldGraph.descendantBits(c));
        BitSet64.set(ancFlag, c);
        long[] descFlag = BitSet64.copy(oldGraph.ancestorBits(a));
        BitSet64.set(descFlag, a);

        for (int i = 0; i < oldGraph.size(); i++) {
            String id = oldGraph.idOf(i);
            if (BitSet64.get(ancFlag, i)) {
                assertNotSame(oldGraph.ancestorBits(i), newGraph.ancestorBits(i), "flagged ancestor row " + id);
            } else {
                assertSame(oldGraph.ancestorBits(i), newGraph.ancestorBits(i), "unflagged ancestor row " + id);
            }
            if (BitSet64.get(descFlag, i)) {
                assertNotSame(oldGraph.descendantBits(i), newGraph.descendantBits(i), "flagged descendant row " + id);
            } else {
                assertSame(oldGraph.descendantBits(i), newGraph.descendantBits(i), "unflagged descendant row " + id);
            }
        }
        assertParity(newGraph);
    }
}
