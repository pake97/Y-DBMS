package com.example.causal.runtime.compile;

import com.example.causal.lifecycle.ChangeBatch;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.example.causal.testsupport.GraphTestSupport.graphFromEdges;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalPatchTest {

    @Test
    void testIncrementalEdgeAddition() {
        // Initial graph: A -> B -> C -> D
        CompiledCausalGraph oldGraph = graphFromEdges(List.of("A->B", "B->C", "C->D"));
        
        // Add an edge B -> D incrementally
        ChangeBatch batch = ChangeBatch.builder()
                .addedEdge("B", "D")
                .build();
                
        CompiledCausalGraph newGraph = oldGraph.patch(batch);
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
        CompiledCausalGraph oldGraph = graphFromEdges(List.of("A->B"));
        
        // Add a new node 'C' and an edge B -> C
        Variable nodeC = Variable.from("C", "C", false, false, 0, 1);
        ChangeBatch batch = ChangeBatch.builder()
                .addedVariable(nodeC)
                .addedEdge("B", "C")
                .build();
                
        CompiledCausalGraph newGraph = oldGraph.patch(batch);
        assertNotNull(newGraph, "Patch should succeed when adding new nodes and valid edges");
        
        assertTrue(newGraph.variables().stream().anyMatch(v -> v.id().equals("C")));
        assertTrue(newGraph.childrenOf("B").contains("C"));
        
        assertParity(newGraph);
    }
    
    @Test
    void testTopologicalViolationFallback() {
        // Initial graph: A -> B -> C
        CompiledCausalGraph oldGraph = graphFromEdges(List.of("A->B", "B->C"));
        
        // Try adding C -> A (creates a cycle / backward edge, violates topo sort)
        ChangeBatch batch = ChangeBatch.builder()
                .addedEdge("C", "A")
                .build();
                
        CompiledCausalGraph newGraph = oldGraph.patch(batch);
        assertNull(newGraph, "Patch should return null and fallback for topological violations");
    }

    private void assertParity(CompiledCausalGraph graph) {
        ReachabilityIndex index = new ReachabilityIndex(graph);
        for (String id : graph.topoOrder()) {
            Set<String> dfsAncestors = index.ancestorsDfs(id);
            Set<String> bitsetAncestors = index.ancestors(id);
            assertEquals(dfsAncestors, bitsetAncestors, "Ancestors mismatch for " + id);

            Set<String> dfsDescendants = index.descendantsDfs(id);
            Set<String> bitsetDescendants = index.descendants(id);
            assertEquals(dfsDescendants, bitsetDescendants, "Descendants mismatch for " + id);
        }
    }
}
