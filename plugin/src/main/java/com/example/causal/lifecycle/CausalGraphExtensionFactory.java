package com.example.causal.lifecycle;

import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.lifecycle.Lifecycle;

/**
 * Neo4j boot hook. Discovered via the service-loader entry
 * {@code META-INF/services/org.neo4j.kernel.extension.ExtensionFactory};
 * instantiated once per database on startup.
 *
 * <p>Its only job is to build a {@link CausalGraphLifecycle}, which registers
 * a {@link CausalGraphChangeListener} for the lifetime of that database.
 *
 * <p>Student A doesn't normally need to touch this class — extend
 * {@link CausalGraphChangeListener} instead.
 */
public final class CausalGraphExtensionFactory
        extends ExtensionFactory<CausalGraphExtensionFactory.Dependencies> {

    public interface Dependencies {
        DatabaseManagementService databaseManagementService();
        GraphDatabaseAPI          graphDatabaseAPI();
    }

    public CausalGraphExtensionFactory() {
        super(ExtensionType.DATABASE, "causal4j-listener");
    }

    @Override
    public Lifecycle newInstance(ExtensionContext context, Dependencies dependencies) {
        return new CausalGraphLifecycle(
            dependencies.databaseManagementService(),
            // dependencies.graphDatabaseAPI().databaseName(),
            new CausalGraphChangeListener()
        );
    }
}
