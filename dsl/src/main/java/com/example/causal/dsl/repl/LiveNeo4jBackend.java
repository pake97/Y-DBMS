package com.example.causal.dsl.repl;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/**
 * {@link Neo4jBackend} that connects over Bolt to an already-running Neo4j
 * server. The server must have the {@code causal4j-plugin} JAR installed in its
 * {@code plugins/} directory so the {@code causal.*} procedures resolve.
 */
public final class LiveNeo4jBackend implements Neo4jBackend {

    private final Driver driver;
    private final String database;

    public LiveNeo4jBackend(String uri, String user, String password, String database) {
        this.driver   = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        this.database = database;
    }

    @Override
    public Driver driver() {
        return driver;
    }

    @Override
    public String database() {
        return database;
    }

    @Override
    public void close() {
        driver.close();
    }
}
