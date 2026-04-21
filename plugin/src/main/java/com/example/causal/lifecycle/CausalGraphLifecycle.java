package com.example.causal.lifecycle;

import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;

import java.util.logging.Logger;

/**
 * Binds the lifetime of a {@link CausalGraphChangeListener} to the lifetime
 * of a Neo4j database: registers the listener on {@link #start()}, removes it
 * on {@link #shutdown()}.
 *
 * <p>Instantiated by {@link CausalGraphExtensionFactory} — not constructed
 * directly.
 */
public final class CausalGraphLifecycle extends LifecycleAdapter {

    private static final Logger LOG = Logger.getLogger(CausalGraphLifecycle.class.getName());

    private final DatabaseManagementService dbms;
    private final String databaseName;
    private final CausalGraphChangeListener listener;

    CausalGraphLifecycle(
            DatabaseManagementService dbms,
            String databaseName,
            CausalGraphChangeListener listener
    ) {
        this.dbms         = dbms;
        this.databaseName = databaseName;
        this.listener     = listener;
    }

    @Override
    public void start() {
        dbms.registerTransactionEventListener(databaseName, listener);
        LOG.info(() -> "Registered CausalGraphChangeListener on database '" + databaseName + "'");
    }

    @Override
    public void shutdown() {
        dbms.unregisterTransactionEventListener(databaseName, listener);
        LOG.info(() -> "Unregistered CausalGraphChangeListener on database '" + databaseName + "'");
    }
}
