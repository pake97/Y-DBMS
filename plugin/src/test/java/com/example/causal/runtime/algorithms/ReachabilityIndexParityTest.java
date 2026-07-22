package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.testsupport.SeedDagSupport;
import com.example.causal.procedures.CausalFunctions;
import com.example.causal.procedures.CausalProcedures;
import static com.example.causal.testsupport.GraphTestSupport.graphFromEdges;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReachabilityIndexParityTest {

    @TempDir
    Path tempDir;

    @Test
    void syntheticConfig1() {
        CompiledCausalGraph graph = graphFromEdges(List.of("A->B", "B->C", "A->C"));
        assertParity(graph);
    }

    @Test
    void syntheticConfig2() {
        CompiledCausalGraph graph = graphFromEdges(List.of(
            "Z->T", "Z->M", "M->Y", "T->M", "T->Y"
        ));
        assertParity(graph);
    }

    @Test
    void syntheticConfig3() {
        CompiledCausalGraph graph = graphFromEdges(List.of(
            "Z->T", "Z->W", "W->Y", "T->X", "Z->X", "X->Q", "Q->Y"
        ));
        assertParity(graph);
    }

    /** The only test that needs an embedded Neo4j — the harness is scoped to it. */
    @Test
    void covidSeedDag() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder(tempDir)
                .withProcedure(CausalProcedures.class)
                .withFunction(CausalFunctions.class)
                .build()) {
            SeedDagSupport.load(neo4j.defaultDatabaseService());
            SeedDagSupport.compile(neo4j.defaultDatabaseService());
            CompiledCausalGraph graph = (CompiledCausalGraph) RuntimeRegistry.getInstance().currentSnapshot();
            assertParity(graph);
        } finally {
            RuntimeRegistry.setInstance(null);
        }
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

        // Ancestor/Descendant duality
        for (String idA : graph.topoOrder()) {
            for (String idB : graph.topoOrder()) {
                boolean aInBAncestors = index.ancestors(idB).contains(idA);
                boolean bInADescendants = index.descendants(idA).contains(idB);
                assertEquals(aInBAncestors, bInADescendants, "Duality mismatch between " + idA + " and " + idB);
            }
        }
    }

}
