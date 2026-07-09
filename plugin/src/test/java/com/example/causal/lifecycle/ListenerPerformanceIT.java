package com.example.causal.lifecycle;

import com.example.causal.api.CausalGraphRuntime;
import com.example.causal.api.GraphCompileOptions;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.DefaultCausalGraphRuntime;
import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.testsupport.SeedDagSupport;import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.kernel.internal.GraphDatabaseAPI;


import java.nio.file.Path;
import java.util.Set;

public class ListenerPerformanceIT {

    @TempDir
    Path tempDir;

    private Neo4j neo4j;
    private GraphDatabaseService db;
    private DatabaseManagementService managementService;
    private CausalGraphChangeListener listener;

    private static final int BASE_GRAPH_SIZE = 2000;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASURE_ITERATIONS = 10;

    @BeforeEach
    void setUp() throws Exception {
        neo4j = Neo4jBuilders.newInProcessBuilder(tempDir).build();
        db = neo4j.defaultDatabaseService();
        managementService = ((GraphDatabaseAPI) db).getDependencyResolver().resolveDependency(DatabaseManagementService.class);
        listener = new CausalGraphChangeListener();
    }

    @AfterEach
    void tearDown() {
        if (neo4j != null) {
            neo4j.close();
        }
        RuntimeRegistry.setInstance(null);
    }

    @Test
    public void testSeedDagBenchmark() {
        System.out.println("Starting Seed DAG Benchmark...");
        DefaultCausalGraphRuntime runtime = new DefaultCausalGraphRuntime();
        RuntimeRegistry.setInstance(runtime);

        long totalMeasureTime = 0;

        for (int i = 0; i < WARMUP_ITERATIONS + MEASURE_ITERATIONS; i++) {
            boolean isWarmup = i < WARMUP_ITERATIONS;

            // Setup Seed DAG (COVID-19 model from seed-dag.cypher)
            SeedDagSupport.load(db);

            // Force initial compile so the graph is hot
            try (Transaction tx = db.beginTx()) {
                RuntimeRegistry.getInstance().compile(new GraphCompileOptions(tx));
                tx.commit();
            }

            long start = System.currentTimeMillis();

            // 1. Write Workload: Add exactly 1 new node and edge to the DAG
            String newId = "new_test_node_iter_" + i;
            try (Transaction tx = db.beginTx()) {
                Node prev = tx.findNode(CausalGraphChangeListener.VARIABLE, CausalGraphChangeListener.PROP_ID, "reported_cases");
                Node n = tx.createNode(CausalGraphChangeListener.VARIABLE);
                n.setProperty(CausalGraphChangeListener.PROP_ID, newId);
                prev.createRelationshipTo(n, CausalGraphChangeListener.CAUSES);
                tx.commit();
            }

            // 2. Read Workload: Query ancestors of the newly added node
            if (RuntimeRegistry.getInstance().currentSnapshot() == null) {
                try (Transaction tx = db.beginTx()) {
                    RuntimeRegistry.getInstance().compile(new GraphCompileOptions(tx));
                    tx.commit();
                }
            }

            Set<VariableRef> ancestors = RuntimeRegistry.getInstance().ancestors(Set.of(new VariableRef(newId)));

            long elapsed = System.currentTimeMillis() - start;

            if (!isWarmup) {
                totalMeasureTime += elapsed;
            }
            System.out.println("  " + (isWarmup ? "Warmup" : "Measured") + " iter " + (i + 1) + ": " + elapsed + " ms (Found " + ancestors.size() + " ancestors)");
        }

        long avgSeedDag = totalMeasureTime / MEASURE_ITERATIONS;
        System.out.println("\n=== Final Results (Seed DAG) ===");
        System.out.println("Average Time: " + avgSeedDag + " ms");
    }

    @Test
    public void testSyntheticBenchmark() {
        System.out.println("Starting Synthetic Benchmark...");
        DefaultCausalGraphRuntime runtime = new DefaultCausalGraphRuntime();
        RuntimeRegistry.setInstance(runtime);

        long totalMeasureTime = 0;

        for (int i = 0; i < WARMUP_ITERATIONS + MEASURE_ITERATIONS; i++) {
            boolean isWarmup = i < WARMUP_ITERATIONS;

            // Clear database
            try (Transaction tx = db.beginTx()) {
                tx.execute("MATCH ()-[r]->() DELETE r");
                tx.commit();
            }
            try (Transaction tx = db.beginTx()) {
                tx.execute("MATCH (n) DELETE n");
                tx.commit();
            }

            // Setup large base graph
            try (Transaction tx = db.beginTx()) {
                Node prev = null;
                for (int v = 0; v < BASE_GRAPH_SIZE; v++) {
                    Node n = tx.createNode(CausalGraphChangeListener.VARIABLE);
                    n.setProperty(CausalGraphChangeListener.PROP_ID, "V" + v);
                    if (prev != null) {
                        prev.createRelationshipTo(n, CausalGraphChangeListener.CAUSES);
                    }
                    prev = n;
                }
                tx.commit();
            }

            // Force initial compile so the graph is hot
            try (Transaction tx = db.beginTx()) {
                RuntimeRegistry.getInstance().compile(new GraphCompileOptions(tx));
                tx.commit();
            }

            long start = System.currentTimeMillis();

            // 1. Write Workload: Add exactly 1 new node and edge
            String newId = "V" + BASE_GRAPH_SIZE;
            try (Transaction tx = db.beginTx()) {
                Node prev = tx.findNode(CausalGraphChangeListener.VARIABLE, CausalGraphChangeListener.PROP_ID, "V" + (BASE_GRAPH_SIZE - 1));
                Node n = tx.createNode(CausalGraphChangeListener.VARIABLE);
                n.setProperty(CausalGraphChangeListener.PROP_ID, newId);
                prev.createRelationshipTo(n, CausalGraphChangeListener.CAUSES);
                tx.commit();
            }

            // 2. Read Workload: Query ancestors of the newly added node
            if (RuntimeRegistry.getInstance().currentSnapshot() == null) {
                try (Transaction tx = db.beginTx()) {
                    RuntimeRegistry.getInstance().compile(new GraphCompileOptions(tx));
                    tx.commit();
                }
            }

            Set<VariableRef> ancestors = RuntimeRegistry.getInstance().ancestors(Set.of(new VariableRef(newId)));

            long elapsed = System.currentTimeMillis() - start;

            if (!isWarmup) {
                totalMeasureTime += elapsed;
            }
            System.out.println("  " + (isWarmup ? "Warmup" : "Measured") + " iter " + (i + 1) + ": " + elapsed + " ms (Found " + ancestors.size() + " ancestors)");
        }

        long avgSynthetic = totalMeasureTime / MEASURE_ITERATIONS;
        System.out.println("\n=== Final Results (Synthetic) ===");
        System.out.println("Average Time: " + avgSynthetic + " ms");
    }
}
