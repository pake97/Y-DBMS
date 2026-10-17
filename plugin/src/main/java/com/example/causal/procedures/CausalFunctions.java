package com.example.causal.procedures;

import com.example.causal.api.CausalGraphRuntime;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.RuntimeRegistry;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.UserFunction;

/**
 * Neo4j user-defined functions for the causal graph runtime.
 *
 * <p>Functions return scalar values and can be used inside Cypher expressions.
 * Unlike procedures, they do not stream rows.
 *
 * <p>Like the read procedures, a function compiles the snapshot with its own transaction
 * when there is none ({@link CausalProcedures#ensureCompiled}).
 */
@SuppressWarnings("unused") // Neo4j discovers and calls these via reflection
public class CausalFunctions {

    @Context
    public Transaction tx;

    // =========================================================================
    // causal.hasDirectedPath(from, to) → Boolean
    // =========================================================================

    @UserFunction(name = "causal.hasDirectedPath")
    @Description(
        "Returns true if there is a directed causal path from 'from' to 'to' in the DAG. " +
        "Compiles the snapshot first if there is none."
    )
    public boolean hasDirectedPath(
            @Name("from") Node from,
            @Name("to")   Node to
    ) {
        CausalProcedures.ensureCompiled(tx);
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        VariableRef x = ProcedureVariables.resolve(runtime, from).orElseThrow(() -> new IllegalArgumentException("unknown variable: " + from));
        VariableRef y = ProcedureVariables.resolve(runtime, to).orElseThrow(() -> new IllegalArgumentException("unknown variable: " + to));
        return runtime.hasDirectedPath(x, y);
    }

    // =========================================================================
    // causal.topoOrder(variableId) → Long
    // =========================================================================

    @UserFunction(name = "causal.topoOrder")
    @Description(
        "Returns the topological order index of the named variable (0 = source node). " +
        "Returns -1 if the variable is not found in the current snapshot. " +
        "Compiles the snapshot first if there is none."
    )
    public long topoOrder(
            @Name("variableId") String variableId
    ) {
        CausalProcedures.ensureCompiled(tx);
        return RuntimeRegistry.getInstance().topoPosition(new VariableRef(variableId));
    }
}
