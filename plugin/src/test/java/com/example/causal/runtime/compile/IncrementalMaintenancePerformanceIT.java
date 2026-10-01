package com.example.causal.runtime.compile;

import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

@Tag("slow")
public class IncrementalMaintenancePerformanceIT {

    @Test
    public void benchmarkIncrementalPatch() throws Exception {
        int numNodes = 1000;
        Map<String, Variable> variables = new HashMap<>();
        List<String> topoOrder = new ArrayList<>();
        Map<String, Set<String>> forwardAdj = new HashMap<>();
        Map<String, Set<String>> reverseAdj = new HashMap<>();
        Map<String, Map<String, Double>> edgeWeights = new HashMap<>();

        for (int i = 0; i < numNodes; i++) {
            String id = "N" + i;
            variables.put(id, Variable.from(id, id, false, false, 0, 1));
            topoOrder.add(id);
            forwardAdj.put(id, new HashSet<>());
            reverseAdj.put(id, new HashSet<>());
            edgeWeights.put(id, new HashMap<>());
        }

        for (int i = 0; i < numNodes - 1; i++) {
            forwardAdj.get("N" + i).add("N" + (i + 1));
            reverseAdj.get("N" + (i + 1)).add("N" + i);
        }
        
        Random rng = new Random(42);
        for (int i = 0; i < numNodes; i++) {
            for (int j = i + 2; j < numNodes; j++) {
                if (rng.nextDouble() < 0.1) {
                    forwardAdj.get("N" + i).add("N" + j);
                    reverseAdj.get("N" + j).add("N" + i);
                }
            }
        }

        CompiledCausalGraph oldGraph = new CompiledCausalGraph(variables, forwardAdj, reverseAdj, topoOrder, edgeWeights, Instant.now());

        System.out.println("\n==================================================");
        System.out.println("BENCHMARK: Incremental patch()");
        System.out.println("Graph size: " + numNodes + " nodes, ~" + (numNodes * numNodes * 0.05) + " edges");
        System.out.println("==================================================");

        int queries = 20000;
        
        List<ChangeBatch> batches = List.of(
            ChangeBatch.builder().addedEdge("N499", "N501").build(),
            ChangeBatch.builder().addedEdge("N0", "N999").build()
        );

        for (ChangeBatch b : batches) {
            var e = b.addedEdges().get(0);
            if (oldGraph.childrenOf(e.from()).contains(e.to()))
                throw new IllegalStateException("benchmark edge already exists: " + e.from() + "->" + e.to());
        }

        // Warm up
        for (ChangeBatch batch : batches) {
            for (int i = 0; i < 1000; i++) {
                SnapshotPatcher.apply(oldGraph, batch);
            }
        }

        for (ChangeBatch batch : batches) {
            long start = System.nanoTime();
            for (int i = 0; i < queries; i++) {
                SnapshotPatcher.apply(oldGraph, batch);
            }
            long end = System.nanoTime();
            double ms = (end - start) / 1_000_000.0;
            
            String edgeStr = batch.addedEdges().get(0).from() + "->" + batch.addedEdges().get(0).to();
            System.out.printf("Patch %-10s : %8.2f ms (%7d ops/sec)\n", edgeStr, ms, (int)(queries / (ms / 1000.0)));
        }
        System.out.println("==================================================\n");
    }
}
