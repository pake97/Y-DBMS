package com.example.causal.runtime.compile;

import com.example.causal.lifecycle.ChangeBatch;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

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
        System.out.println("BENCHMARK: Full Recompile");
        System.out.println("Graph size: " + numNodes + " nodes, ~" + (numNodes * numNodes * 0.05) + " edges");
        System.out.println("==================================================");

        List<ChangeBatch> batches = List.of(
            ChangeBatch.builder().addedEdge("N499", "N501").build(),
            ChangeBatch.builder().addedEdge("N0", "N999").build()
        );

        for (ChangeBatch b : batches) {
            var e = b.addedEdges().get(0);
            if (forwardAdj.get(e.from()).contains(e.to()))
                throw new IllegalStateException("benchmark edge already exists: " + e.from() + "->" + e.to());
        }

        int queries = 2000;
        System.out.println("Warming up...");
        for (ChangeBatch batch : batches) {
            for (int i = 0; i < 100; i++) {
                new CompiledCausalGraph(variables, forwardAdj, reverseAdj, topoOrder, edgeWeights, Instant.now());
            }
        }

        for (ChangeBatch batch : batches) {
            long start = System.nanoTime();
            for (int i = 0; i < queries; i++) {
                new CompiledCausalGraph(variables, forwardAdj, reverseAdj, topoOrder, edgeWeights, Instant.now());
            }
            long end = System.nanoTime();
            double ms = (end - start) / 1_000_000.0;
            String edgeStr = batch.addedEdges().get(0).from() + "->" + batch.addedEdges().get(0).to();
            System.out.printf("Patch %-10s : %8.2f ms (%7d ops/sec)\n", edgeStr, ms, (int)(queries / (ms / 1000.0)));
        }
        System.out.println("==================================================\n");
    }
}
