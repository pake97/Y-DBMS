package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.testsupport.GraphTestSupport;
import com.example.causal.testsupport.GraphTestSupport.GraphInput;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

public class ReachabilityPerformanceTest {

    @Test
    public void testPerformance() {
        int numNodes = 2000;
        int maxOutDegree = 15;
        
        System.out.println("Generating DAG with " + numNodes + " variables...");
        List<String> edges = new ArrayList<>();
        for (int i = 0; i < numNodes; i++) {
            for (int j = i + 1; j < Math.min(numNodes, i + 1 + maxOutDegree); j++) {
                edges.add("V" + i + "->V" + j);
            }
        }
        System.out.println("Generated " + edges.size() + " edges.");

        System.out.println("Parsing DAG strings into Maps and sorting topologically...");
        GraphInput input = GraphTestSupport.parseEdges(edges);

        System.out.println("\n--- COMPILATION PHASE ---");
        long compileStart = System.currentTimeMillis();
        CompiledCausalGraph graph = GraphTestSupport.buildFromInput(input);
        long compileEnd = System.currentTimeMillis();
        System.out.println("Graph Compilation Time: " + (compileEnd - compileStart) + " ms");

        System.out.println("\n--- QUERY PHASE ---");
        ReachabilityIndex index = new ReachabilityIndex(graph);
        List<String> nodes = graph.topoOrder();
        
        // JIT Warmup (1 iteration)
        for (String node : nodes) {
            index.ancestors(node);
            index.descendants(node);
        }

        long queryStart = System.currentTimeMillis();
        int iterations = 10;
        for (int i = 0; i < iterations; i++) {
            for (String node : nodes) {
                index.ancestors(node);
                index.descendants(node);
            }
        }
        long queryEnd = System.currentTimeMillis();
        
        long totalQueries = iterations * nodes.size() * 2;
        long totalTime = queryEnd - queryStart;
        
        System.out.println("Total Queries Executed: " + totalQueries);
        System.out.println("Total Query Time: " + totalTime + " ms");
        System.out.println("Average Query Time: " + (totalTime > 0 ? ((double)totalTime / totalQueries) : 0.0) + " ms per query");
        System.out.println("========================================\n");
    }

}
