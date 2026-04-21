package com.example.causal.dsl;

import com.example.causal.dsl.ast.Statement;
import com.example.causal.dsl.exec.InMemoryQueryExecutor;
import com.example.causal.dsl.exec.QueryExecutor;
import com.example.causal.dsl.exec.QueryResult;
import com.example.causal.dsl.model.ModelRegistry;
import com.example.causal.dsl.parser.AstBuilder;

import java.util.List;

/**
 * Main entry point for the Causal DSL.
 *
 * <p>By default uses an {@link InMemoryQueryExecutor} — self-contained, no
 * Neo4j required. Pass a {@link com.example.causal.dsl.exec.Neo4jQueryExecutor}
 * to the {@link #CausalDSL(QueryExecutor, ModelRegistry) overloaded constructor}
 * to target a running Neo4j server instead.
 *
 * <pre>{@code
 * // In-memory:
 * var dsl = new CausalDSL();
 * dsl.executeScript("DEFINE CAUSAL DAG M { A -> B }  CAUSES OF B");
 *
 * // Against Neo4j:
 * try (Driver driver = GraphDatabase.driver("bolt://localhost:7687",
 *          AuthTokens.basic("neo4j", "neo4j"))) {
 *     var registry = new ModelRegistry();
 *     var dsl = new CausalDSL(new Neo4jQueryExecutor(driver, registry), registry);
 *     dsl.executeScript(...);
 * }
 * }</pre>
 */
public class CausalDSL {

    private final ModelRegistry registry;
    private final AstBuilder    builder = new AstBuilder();
    private final QueryExecutor executor;

    /** Default: in-memory executor backed by a fresh {@link ModelRegistry}. */
    public CausalDSL() {
        this.registry = new ModelRegistry();
        this.executor = new InMemoryQueryExecutor(registry);
    }

    /**
     * Inject a custom executor (e.g. {@code Neo4jQueryExecutor}).
     * Supply the same {@link ModelRegistry} the executor uses so that
     * {@link #registry()} reflects accumulated state.
     */
    public CausalDSL(QueryExecutor executor, ModelRegistry registry) {
        this.executor = executor;
        this.registry = registry;
    }

    public List<QueryResult> executeScript(String script) {
        List<Statement> stmts = builder.buildStatements(script);
        return stmts.stream().map(executor::execute).toList();
    }

    public QueryResult execute(String statement) {
        List<QueryResult> results = executeScript(statement);
        if (results.size() != 1)
            throw new IllegalArgumentException("Expected exactly one statement, got " + results.size());
        return results.get(0);
    }

    public ModelRegistry registry() { return registry; }
}
