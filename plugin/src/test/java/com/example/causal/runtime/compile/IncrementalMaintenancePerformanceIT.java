package com.example.causal.runtime.compile;

import com.example.causal.lifecycle.ChangeBatch;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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

        Object oldGraph = null;
        for (Constructor<?> ctor : CompiledCausalGraph.class.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            try {
                if (ctor.getParameterCount() == 6) {
                    oldGraph = ctor.newInstance(variables, forwardAdj, reverseAdj, topoOrder, edgeWeights, Instant.now());
                } else if (ctor.getParameterCount() == 5) {
                    oldGraph = ctor.newInstance(variables, forwardAdj, reverseAdj, topoOrder, edgeWeights);
                } else if (ctor.getParameterCount() == 4) {
                    oldGraph = ctor.newInstance(variables, forwardAdj, reverseAdj, edgeWeights);
                }
                if (oldGraph != null) break;
            } catch (Exception ignored) { }
        }

        if (oldGraph == null) {
            throw new IllegalStateException("Could not construct CompiledCausalGraph");
        }

        Method patchMethod = null;
        Method evolverMethod = null;
        try {
            patchMethod = CompiledCausalGraph.class.getMethod("patch", ChangeBatch.class);
        } catch (NoSuchMethodException e) {
            try {
                Class<?> evolverClass = Class.forName("com.example.causal.runtime.compile.CompiledCausalGraphEvolver");
                evolverMethod = evolverClass.getMethod("evolve", CompiledCausalGraph.class, ChangeBatch.class);
            } catch (ClassNotFoundException | NoSuchMethodException ex) {
                // No incremental support
            }
        }

        int queries = 2000;
        String mode = "Full Recompile";
        if (patchMethod != null) mode = "Incremental patch()";
        else if (evolverMethod != null) mode = "CompiledCausalGraphEvolver";

        System.out.println("\n==================================================");
        System.out.println("BENCHMARK: " + mode);
        System.out.println("Graph size: " + numNodes + " nodes, ~" + (numNodes * numNodes * 0.05) + " edges");
        System.out.println("==================================================");

        List<ChangeBatch> batches = List.of(
            ChangeBatch.builder().addedEdge("N0", "N1").build(),
            ChangeBatch.builder().addedEdge("N400", "N600").build(),
            ChangeBatch.builder().addedEdge("N0", "N999").build()
        );

        System.out.println("Warming up...");
        if (patchMethod != null) {
            for (ChangeBatch batch : batches) {
                for (int i = 0; i < 100; i++) patchMethod.invoke(oldGraph, batch);
            }
            queries = 20000; 
        } else if (evolverMethod != null) {
            for (ChangeBatch batch : batches) {
                for (int i = 0; i < 100; i++) evolverMethod.invoke(null, oldGraph, batch);
            }
            queries = 20000; 
        }

        for (ChangeBatch batch : batches) {
            long start = System.nanoTime();
            for (int i = 0; i < queries; i++) {
                if (patchMethod != null) {
                    patchMethod.invoke(oldGraph, batch);
                } else if (evolverMethod != null) {
                    evolverMethod.invoke(null, oldGraph, batch);
                } else {
                    for (Constructor<?> ctor : CompiledCausalGraph.class.getDeclaredConstructors()) {
                        ctor.setAccessible(true);
                        try {
                            if (ctor.getParameterCount() == 6) ctor.newInstance(variables, forwardAdj, reverseAdj, topoOrder, edgeWeights, Instant.now());
                            else if (ctor.getParameterCount() == 5) ctor.newInstance(variables, forwardAdj, reverseAdj, topoOrder, edgeWeights);
                            else if (ctor.getParameterCount() == 4) ctor.newInstance(variables, forwardAdj, reverseAdj, edgeWeights);
                        } catch (Exception ignored) { }
                    }
                }
            }
            long end = System.nanoTime();
            double ms = (end - start) / 1_000_000.0;

            String edgeStr = batch.addedEdges().get(0).from() + "->" + batch.addedEdges().get(0).to();
            System.out.printf("Patch %-10s : %8.2f ms (%7d ops/sec)\n", edgeStr, ms, (int)(queries / (ms / 1000.0)));
        }
        System.out.println("==================================================\n");
    }
}
