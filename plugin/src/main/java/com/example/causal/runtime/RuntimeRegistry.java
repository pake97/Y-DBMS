package com.example.causal.runtime;

import com.example.causal.api.CausalGraphRuntime;

/**
 * Process-scoped singleton holder for {@link CausalGraphRuntime}.
 *
 * <h2>Why this exists</h2>
 * Neo4j instantiates procedure classes fresh for every procedure call.
 * The compiled graph snapshot must therefore live outside the procedure class.
 * This registry uses double-checked locking to create the runtime lazily.
 *
 * <h2>Testing</h2>
 * Call {@link #setInstance} with a test double or {@code null} in {@code @AfterEach}
 * to prevent snapshot state from leaking between integration tests.
 *
 * <h2>Single-Database Assumption</h2>
 * This registry is currently a process-wide singleton. On Enterprise or Aura setups with
 * multiple user databases, writes to different databases will incorrectly patch this single
 * shared snapshot. Future work should key this registry by database name.
 */
public final class RuntimeRegistry {

    private static volatile CausalGraphRuntime instance;

    private RuntimeRegistry() {}

    /**
     * Return the shared runtime instance, creating a {@link DefaultCausalGraphRuntime}
     * lazily on the first call.
     */
    public static CausalGraphRuntime getInstance() {
        if (instance == null) {
            synchronized (RuntimeRegistry.class) {
                if (instance == null) {
                    instance = new DefaultCausalGraphRuntime();
                }
            }
        }
        return instance;
    }

    /**
     * Replace the current instance. Pass {@code null} to force re-creation on the
     * next {@link #getInstance()} call. Intended for use in tests.
     */
    public static void setInstance(CausalGraphRuntime runtime) {
        synchronized (RuntimeRegistry.class) {
            instance = runtime;
        }
    }
}
