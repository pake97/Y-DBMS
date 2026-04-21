package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.Statement;

/**
 * Contract for executing a DSL statement.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link InMemoryQueryExecutor} — no Neo4j required; algorithms live in the DSL
 *       module ({@code CausalModel}). Good for offline use and unit tests.</li>
 *   <li>{@link Neo4jQueryExecutor} — translates AST to Cypher and delegates to the
 *       {@code causal.*} procedures in the plugin. Neo4j is the source of truth.</li>
 * </ul>
 *
 * <p>{@code CausalDSL} is parametrised over this interface, so either backend
 * can be used interchangeably.
 */
public interface QueryExecutor {
    QueryResult execute(Statement stmt);
}
