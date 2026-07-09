package com.example.causal.dsl.repl;

import com.example.causal.procedures.CausalFunctions;
import com.example.causal.procedures.CausalProcedures;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

/**
 * {@link Neo4jBackend} that boots an in-process Neo4j with the {@code causal.*}
 * procedures registered — the same wiring {@code DslPluginE2ETest} uses, but
 * exposed to the REPL so the full DSL → plugin → Neo4j pipeline can be exercised
 * with zero external setup.
 *
 * <p>This is the <b>only</b> DSL class that names the plugin's procedure
 * classes; the coupling is deliberately isolated here so the rest of the DSL
 * keeps talking to the plugin purely through {@code causal.*} Cypher and the
 * {@code com.example.causal.api} layer.
 *
 * <p>The database is <b>ephemeral</b>: it lives in a temporary directory and is
 * discarded on {@link #close()}. Each REPL session starts empty — define a DAG
 * with {@code DEFINE CAUSAL DAG ...} to populate it.
 */
public final class EmbeddedNeo4jBackend implements Neo4jBackend {

    private final Neo4j neo4j;
    private final Driver driver;

    public EmbeddedNeo4jBackend() {
        this.neo4j = Neo4jBuilders.newInProcessBuilder()
                .withProcedure(CausalProcedures.class)
                .withFunction(CausalFunctions.class)
                .build();
        this.driver = GraphDatabase.driver(neo4j.boltURI());
    }

    @Override
    public Driver driver() {
        return driver;
    }

    /** Embedded Neo4j exposes only the default database. */
    @Override
    public String database() {
        return null;
    }

    @Override
    public void close() {
        driver.close();
        neo4j.close();
    }
}
