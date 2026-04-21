package com.example.causal.api;

import java.util.Optional;
import java.util.Set;

/**
 * Primary contract between the DSL/planner layer and the Neo4j runtime backend.
 *
 * <h2>Architectural boundary</h2>
 * <ul>
 *   <li>The <strong>DSL/planner</strong> (external project) depends <em>only</em> on this
 *       interface and the types in {@code com.example.causal.api}.</li>
 *   <li>The <strong>Neo4j procedures</strong> ({@code com.example.causal.procedures})
 *       call this interface and never touch runtime internals directly.</li>
 *   <li><strong>Student A</strong> implements this interface via
 *       {@code DefaultCausalGraphRuntime} and owns all types below the
 *       {@code com.example.causal.runtime} package.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Call {@link #compile} once (or after a graph change) to load Neo4j data.</li>
 *   <li>Issue structural queries ({@code parents}, {@code ancestors}, etc.).</li>
 *   <li>Call {@link #invalidate} when the Neo4j graph has been mutated and the
 *       snapshot must be refreshed.</li>
 * </ol>
 */
public interface CausalGraphRuntime {

    /**
     * Compile (or recompile) the causal graph from Neo4j using the supplied options.
     * Replaces any existing snapshot atomically.
     *
     * @param options wraps the active Neo4j transaction and any compile-time flags
     * @return the newly compiled snapshot
     * @throws IllegalStateException if the Neo4j graph contains a cycle
     */
    GraphSnapshot compile(GraphCompileOptions options);

    /**
     * Look up a variable by its {@code id} property value.
     *
     * @param key the {@code id} property of a {@code :Variable} node
     * @return an Optional containing the ref, or empty if the key is unknown
     */
    Optional<VariableRef> resolveVariable(String key);

    /**
     * Return the direct parents (immediate causes) of every variable in {@code vars}.
     * Variables in {@code vars} that are unknown in the snapshot are silently ignored.
     */
    Set<VariableRef> parents(Set<VariableRef> vars);

    /**
     * Return the direct children (immediate effects) of every variable in {@code vars}.
     * Variables in {@code vars} that are unknown in the snapshot are silently ignored.
     */
    Set<VariableRef> children(Set<VariableRef> vars);

    /**
     * Return all ancestors (transitive causes) of every variable in {@code vars}.
     * The variables in {@code vars} themselves are not included in the result.
     */
    Set<VariableRef> ancestors(Set<VariableRef> vars);

    /**
     * Return all descendants (transitive effects) of every variable in {@code vars}.
     * The variables in {@code vars} themselves are not included in the result.
     */
    Set<VariableRef> descendants(Set<VariableRef> vars);

    /**
     * True if there exists a directed path x → … → y in the compiled DAG.
     *
     * @param x source variable
     * @param y target variable
     */
    boolean hasDirectedPath(VariableRef x, VariableRef y);

    /**
     * Test d-separation: are the variable sets X and Y d-separated given Z?
     * (Written X ⊥ Y | Z in the causal DAG.)
     *
     * @param x first variable set
     * @param y second variable set
     * @param z conditioning set
     */
    DSeparationResult isDSeparated(Set<VariableRef> x, Set<VariableRef> y, Set<VariableRef> z);

    /**
     * Find valid adjustment sets for identifying the causal effect of
     * {@code treatment} on {@code outcome} from observational data.
     *
     * @param treatment the intervention variable
     * @param outcome   the response variable
     */
    AdjustmentCandidateResult candidateAdjustmentSet(VariableRef treatment, VariableRef outcome);

    /**
     * Return the current compiled snapshot, or {@code null} if {@link #compile}
     * has not yet been called (or the snapshot has been invalidated).
     */
    GraphSnapshot currentSnapshot();

    /**
     * Discard the current snapshot. The next call to {@link #compile} will
     * re-read the graph from Neo4j.
     */
    void invalidate();
}
