package com.example.causal.procedures;

import com.example.causal.testsupport.SyntheticDataSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("synthetic")
public class SyntheticAlgorithmE2ETest {

    private static Neo4j embeddedDatabaseServer;
    private static GraphDatabaseService db;

    @BeforeAll
    static void setupDatabaseAndLoadData() {
        // Initialize Harness
        embeddedDatabaseServer = Neo4jBuilders.newInProcessBuilder()
                .withProcedure(CausalProcedures.class)
                .withFunction(CausalFunctions.class)
                .build();
        db = embeddedDatabaseServer.defaultDatabaseService();

        // Read parameters from Maven (or default to 1_1K)
        int configId = Integer.parseInt(System.getProperty("test.configId", "1"));
        String sampleSize = System.getProperty("test.sampleSize", "1K");

        System.out.println("Running Synthetic E2E Test on Config " + configId + " size " + sampleSize);
        SyntheticDataSupport.load(db, configId, sampleSize);
        
        // Very important: compile the graph in-memory so procedures can query it!
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute("CALL causal.compile()");
            while (r.hasNext()) r.next(); // exhaust result
            tx.commit();
        }
    }

    @AfterAll
    static void teardownDatabase() {
        if (embeddedDatabaseServer != null) {
            embeddedDatabaseServer.close();
        }
    }

    @Test
    void testDataIngestionWasSuccessful() {
        long[] counts = SyntheticDataSupport.graphCounts(db);
        assertTrue(counts[0] > 0, "Nodes were loaded");
        assertTrue(counts[1] > 0, "Edges were loaded");
    }

    @Test
    void testSampleMediatorQuery() {
        // We know from synthetic.py that 'v0' is the treatment and 'y' is the outcome,
        // and there is at least one frontdoor variable (FD0) acting as a mediator.
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute(
                "MATCH (t:Variable {id: 'v0'}), (y:Variable {id: 'y'}) " +
                "CALL causal.mediators(t, y) YIELD variable " +
                "RETURN count(variable) as mediatorCount"
            );
            assertTrue(r.hasNext(), "Query should return a result row");
            
            long mediatorCount = (long) r.next().get("mediatorCount");
            System.out.println("Found " + mediatorCount + " mediators between v0 and y.");
            assertTrue(mediatorCount > 0, "There should be at least one mediator (frontdoor variable) between v0 and y");
            
            tx.commit();
        }
    }
}
