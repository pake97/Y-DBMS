package com.example.causal.runtime;

import com.example.causal.api.GraphCompileOptions;
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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** compile() writes the structure dump only when FINE is enabled for CompiledCausalGraph's logger. */
class CompileStructureLogTest {

    @TempDir
    Path tempDir;

    private final Logger log = Logger.getLogger(CompiledCausalGraph.class.getName());
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { records.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    private Neo4j neo4j;
    private GraphDatabaseService db;

    @BeforeEach
    void setUp() {
        neo4j = Neo4jBuilders.newInProcessBuilder(tempDir).build();
        db = neo4j.defaultDatabaseService();
        try (Transaction tx = db.beginTx()) {
            tx.execute("CREATE (a:Variable {id: 'A'})-[:CAUSES {active: true}]->(b:Variable {id: 'B'})").accept(r -> true);
            tx.commit();
        }
        capture.setLevel(Level.ALL);
        log.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        log.removeHandler(capture);
        log.setLevel(null);
        if (neo4j != null) neo4j.close();
    }

    private void compile() {
        try (Transaction tx = db.beginTx()) {
            new DefaultCausalGraphRuntime().compile(new GraphCompileOptions(tx));
        }
    }

    @Test
    void defaultLoggingConfigurationWritesNothing() {
        assertNull(log.getLevel(), "default configuration: the logger inherits the root level");
        assertFalse(log.isLoggable(Level.FINE));
        compile();
        assertEquals(List.of(), records, "compile() logged from CompiledCausalGraph by default");
    }

    @Test
    void fineEnablesTheStructureDump() {
        log.setLevel(Level.FINE);
        compile();
        assertEquals(1, records.size());
        assertEquals(Level.FINE, records.get(0).getLevel());
        assertTrue(records.get(0).getMessage().contains("structure dump"));
    }
}
