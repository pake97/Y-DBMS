package com.example.causal.dsl.repl;

import org.neo4j.driver.Driver;

/**
 * A source of a Neo4j {@link Driver} for the DSL's Neo4j-backed executor,
 * abstracting over <em>where</em> the database lives.
 *
 * <p>{@code Neo4jQueryExecutor} is connection-agnostic — it only needs a
 * {@link Driver} (and optionally a database name). The two implementations
 * differ solely in how that driver is produced and where the {@code causal.*}
 * procedures are registered:
 * <ul>
 *   <li>{@link LiveNeo4jBackend} — Bolt connection to a running server whose
 *       {@code plugins/} directory contains the {@code ydbms-plugin} JAR.</li>
 *   <li>{@link EmbeddedNeo4jBackend} — an in-process Neo4j with the procedures
 *       registered programmatically; no external server required.</li>
 * </ul>
 *
 * <p>The caller owns the lifecycle: {@link #close()} disposes the driver and,
 * for the embedded backend, the in-process database.
 */
public interface Neo4jBackend extends AutoCloseable {

    /** The driver the DSL executor should use. Live for the lifetime of this backend. */
    Driver driver();

    /** Target database name, or {@code null} to use the server's default database. */
    String database();

    @Override
    void close();
}
