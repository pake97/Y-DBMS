package com.example.causal.lifecycle;

import com.example.causal.api.GraphCompileOptions;
import com.example.causal.runtime.DefaultCausalGraphRuntime;
import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class IncrementalListenerCypherTest {

    @TempDir
    Path tempDir;

    private Neo4j neo4j;
    private GraphDatabaseService db;
    private DefaultCausalGraphRuntime runtime;

    @BeforeEach
    void setUp() {
        neo4j = Neo4jBuilders.newInProcessBuilder(tempDir).build();
        db = neo4j.defaultDatabaseService();
        runtime = new DefaultCausalGraphRuntime();
        RuntimeRegistry.setInstance(runtime);
        
        // Setup initial graph
        try (Transaction tx = db.beginTx()) {
            tx.execute("CREATE (a:Variable {id: 'A', name: 'A'}), (b:Variable {id: 'B', name: 'B'}), (a)-[:CAUSES {active: true}]->(b)");
            tx.commit();
        }
        
        try (Transaction tx = db.beginTx()) {
            runtime.compile(new GraphCompileOptions(tx));
        }
    }

    @AfterEach
    void tearDown() {
        if (neo4j != null) {
            neo4j.close();
        }
        RuntimeRegistry.setInstance(null);
    }

    @Test
    void testBlocker1_SameBatchAppliedTwice() {
        try (Transaction tx = db.beginTx()) {
            tx.execute("CREATE (c:Variable {id: 'C', name: 'C'}), (b:Variable {id: 'B'})-[:CAUSES {active: true}]->(c)");
            tx.commit();
        }
        CompiledCausalGraph snapshot = (CompiledCausalGraph) runtime.currentSnapshot();
        assertNotNull(snapshot);
        assertEquals(3, snapshot.variables().size());
    }

    @Test
    void testBlocker2_MultipleNewVariablesChained() {
        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (b:Variable {id: 'B'}) " +
                       "CREATE (x:Variable {id: 'X', name: 'X'}), " +
                       "(y:Variable {id: 'Y', name: 'Y'}), " +
                       "(z:Variable {id: 'Z', name: 'Z'}), " +
                       "(y)-[:CAUSES {active: true}]->(z), " +
                       "(x)-[:CAUSES {active: true}]->(y), " +
                       "(b)-[:CAUSES {active: true}]->(x)");
            tx.commit();
        }
        CompiledCausalGraph snapshot = (CompiledCausalGraph) runtime.currentSnapshot();
        assertNotNull(snapshot, "chained new variables are patched (WP3 step 1)");
        assertTrue(snapshot.variables().stream().anyMatch(v -> v.id().equals("X")));
        assertEquals(Set.of("A", "B", "X", "Y"), snapshot.toIds(snapshot.ancestorBits(snapshot.indexOf("Z"))));
    }
    
    @Test
    void testBlocker3_UpdatedVariables() {
        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (a:Variable {id: 'A'}) SET a.latent = true, a.intercept = 5.0");
            tx.commit();
        }
        CompiledCausalGraph snapshot = (CompiledCausalGraph) runtime.currentSnapshot();
        assertNotNull(snapshot);
        var a = snapshot.findVariable("A").get();
        assertTrue(a.latent());
        assertEquals(5.0, a.intercept());
    }

    @Test
    void testBlocker4_DeactivationFallback() {
        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH ()-[r:CAUSES]->() SET r.active = false");
            tx.commit();
        }
        CompiledCausalGraph snapshot = (CompiledCausalGraph) runtime.currentSnapshot();
        assertNull(snapshot, "Snapshot should be invalidated after deactivation fallback");
    }

    @Test
    void testBlocker3_CycleDetection() {
        // Validation should prevent this transaction from committing
        Exception ex = assertThrows(Exception.class, () -> {
            try (Transaction tx = db.beginTx()) {
                tx.execute("CREATE (x:Variable {id: 'X', name: 'X'}), (y:Variable {id: 'Y', name: 'Y'}), (x)-[:CAUSES {active:true}]->(y), (y)-[:CAUSES {active:true}]->(x)");
                tx.commit(); // This should throw due to cycle validation
            }
        });
        boolean foundCycle = false;
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("cycle")) {
                foundCycle = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(foundCycle, "Exception should mention cycle, but was: " + ex);
    }

    @Test
    void testBlocker4_WeightUpdates() {
        // Changing a weight property should be patched in-place
        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH ()-[r:CAUSES]->() SET r.weight = 0.9");
            tx.commit();
        }
        CompiledCausalGraph snapshot = (CompiledCausalGraph) runtime.currentSnapshot();
        assertNotNull(snapshot, "Snapshot should be patched in-place after weight update");
        assertEquals(0.9, snapshot.weight("A", "B"), 0.0001, "Edge weight should be updated to 0.9");

        // Adding an edge with a weight should also be patched in-place
        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (a:Variable {id: 'A'}) CREATE (c:Variable {id: 'C'}), (a)-[:CAUSES {active:true, weight:0.7}]->(c)");
            tx.commit();
        }
        CompiledCausalGraph snapshot2 = (CompiledCausalGraph) runtime.currentSnapshot();
        assertNotNull(snapshot2, "Snapshot should be patched in-place after adding weighted edge");
        assertEquals(0.7, snapshot2.weight("A", "C"), 0.0001, "New edge weight should be 0.7");
    }

    @Test
    void testEdgeReversalCommits() {
        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (a:Variable {id: 'A'})-[r:CAUSES]->(b:Variable {id: 'B'}) DELETE r CREATE (b)-[:CAUSES {active: true}]->(a)");
            tx.commit();
        }
        CompiledCausalGraph snapshot = (CompiledCausalGraph) runtime.currentSnapshot();
        // Fallback is fine, we just care that the listener didn't abort the transaction.
        assertNull(snapshot);
    }

    @Test
    void testCycleCreatedBeforeAnyCompileIsRejected() {
        runtime.invalidate();
        
        Exception ex = assertThrows(Exception.class, () -> {
            try (Transaction tx = db.beginTx()) {
                tx.execute("CREATE (x:Variable {id: 'X'}), (y:Variable {id: 'Y'}), (x)-[:CAUSES {active:true}]->(y), (y)-[:CAUSES {active:true}]->(x)");
                tx.commit();
            }
        });
        
        boolean foundCycle = false;
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("cycle")) {
                foundCycle = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(foundCycle, "Should detect cycle even with no existing snapshot");
    }

    // -------------------------------------------------------------------------
    // Deletion. These start from a *non-empty* database on purpose: beforeCommit
    // must not call hasLabel() / isType() / getProperty() on an entity the same
    // transaction deleted, or Neo4j throws EntityNotFoundException and the user's
    // transaction is aborted. Every deleted-entity guard therefore has to be
    // evaluated before any accessor on that entity.
    // -------------------------------------------------------------------------

    @Test
    void testDetachDeleteVariableDoesNotAbortTransaction() {
        assertDoesNotThrow(() -> {
            try (Transaction tx = db.beginTx()) {
                tx.execute("MATCH (b:Variable {id: 'B'}) DETACH DELETE b");
                tx.commit();
            }
        }, "Deleting a :Variable must not abort the transaction");

        assertNull(runtime.currentSnapshot(),
                "Removing a variable is a non-local change, so the snapshot should be invalidated");
    }

    @Test
    void testDeleteAllVariablesDoesNotAbortTransaction() {
        // This is the first statement of dev/seed-dag.cypher, so re-seeding a
        // populated database exercises exactly this path.
        assertDoesNotThrow(() -> {
            try (Transaction tx = db.beginTx()) {
                tx.execute("MATCH (v:Variable) DETACH DELETE v");
                tx.commit();
            }
        }, "Clearing the causal graph must not abort the transaction");

        try (Transaction tx = db.beginTx()) {
            runtime.compile(new GraphCompileOptions(tx));
        }
        assertEquals(0, ((CompiledCausalGraph) runtime.currentSnapshot()).size(),
                "Recompiling an emptied graph should yield no variables");
    }

    @Test
    void testPropertyUpdateThenDeleteInSameTransaction() {
        assertDoesNotThrow(() -> {
            try (Transaction tx = db.beginTx()) {
                tx.execute("MATCH (b:Variable {id: 'B'}) SET b.latent = true WITH b DETACH DELETE b");
                tx.commit();
            }
        }, "A property update followed by a delete in one transaction must not abort it");

        assertNull(runtime.currentSnapshot(), "Snapshot should be invalidated after the variable is removed");
    }

    @Test
    void testCreateThenDeleteInSameTransaction() {
        assertDoesNotThrow(() -> {
            try (Transaction tx = db.beginTx()) {
                tx.execute("CREATE (t:Variable {id: 'TMP'}) WITH t DELETE t");
                tx.commit();
            }
        }, "Creating and deleting a variable in one transaction must not abort it");

        try (Transaction tx = db.beginTx()) {
            runtime.compile(new GraphCompileOptions(tx));
        }
        CompiledCausalGraph snapshot = (CompiledCausalGraph) runtime.currentSnapshot();
        assertEquals(Set.of("A", "B"), Set.copyOf(snapshot.topoOrder()),
                "The transient variable should leave no trace in the snapshot");
    }
}
