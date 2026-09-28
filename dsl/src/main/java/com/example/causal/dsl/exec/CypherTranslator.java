package com.example.causal.dsl.exec;

import com.example.causal.api.ProcedureNames;
import com.example.causal.api.ResultColumns;
import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.model.VariableType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates DSL AST statements into Cypher + parameter bindings for execution
 * against a Neo4j server that has the {@code causal4j-plugin} loaded.
 *
 * <p>The Cypher we generate is either:
 * <ul>
 *   <li>A write query that maintains the {@code :Variable}/{@code :CAUSES}
 *       schema the plugin expects, or</li>
 *   <li>A {@code CALL causal.*} procedure invocation that delegates to the
 *       plugin runtime.</li>
 * </ul>
 *
 * <p>This class is pure: it produces {@link CypherQuery} values and performs
 * no I/O. {@link Neo4jQueryExecutor} drives it against a real session.
 */
public final class CypherTranslator {

    /** A Cypher string + its parameters + the {@code YIELD} column (if any). */
    public record CypherQuery(String cypher, Map<String, Object> params, String yieldColumn) {
        public static CypherQuery write(String cypher, Map<String, Object> params) {
            return new CypherQuery(cypher, params, null);
        }
        public static CypherQuery read(String cypher, Map<String, Object> params, String yieldColumn) {
            return new CypherQuery(cypher, params, yieldColumn);
        }
        public boolean isWrite() { return yieldColumn == null && !cypher.toUpperCase().startsWith("CALL "); }
    }

    /**
     * Dispatch: convert any DSL statement into the list of Cypher queries it
     * would emit when executed against Neo4j.
     *
     * <p>Some statements produce multiple queries (e.g. {@code DEFINE MODEL}
     * writes one MERGE per variable, one batch MERGE for edges, then triggers
     * {@code causal.compile}). DSL-only statements (stubs like INTERVENE /
     * COUNTERFACTUAL / ESTIMATE v1) return an empty list.
     */
    public List<CypherQuery> translate(Statement stmt) {
        return switch (stmt) {
            case DefineVariableStmt s -> List.of(defineVariable(s));
            case DefineModelStmt    s -> translateDefineModel(s);
            case DefineDagStmt      s -> List.of(defineDag(s.edges()), compile());

            case CausesOfStmt        s -> List.of(causesOf(s.var()));
            case EffectsOfStmt       s -> List.of(effectsOf(s.var()));
            case AncestorsOfStmt     s -> List.of(ancestorsOf(s.var()));
            case DescendantsOfStmt   s -> List.of(descendantsOf(s.var()));
            case MediatorsBetweenStmt s -> List.of(mediators(s.from(), s.to()));
            case CollidersBetweenStmt s -> List.of(colliders(s.from(), s.to()));
            case ConfoundersOfStmt    s -> List.of(confounders(s.x(), s.y()));
            case BackdoorPathsStmt    s -> List.of(backdoorPaths(s.from(), s.to()));

            case IdentifyStmt s -> List.of(adjustmentCandidate(s.treatment(), s.outcome()));

            case ShowDagStmt         s -> List.of(showDagVariables(), showDagEdges());
            case DescribeVariableStmt s -> List.of(describeVariable(s.name()));

            case EstimateStmt       s -> List.of();  // requires OBSERVED AS execution — not wired yet
            case InterveneStmt      s -> List.of();  // do-operator handled DSL-side (derived world), no Cypher
            case CounterfactualStmt s -> List.of();
            case UseModelStmt       s -> List.of();  // context switch — no Cypher
            case ResetStmt          s -> List.of();
            case ShowModelsStmt     s -> List.of();  // registry-side listing — no Cypher
        };
    }

    private List<CypherQuery> translateDefineModel(DefineModelStmt s) {
        List<CypherQuery> out = new ArrayList<>();
        for (VariableDecl vd : s.variables()) {
            out.add(defineVariable(vd));
        }
        out.add(defineDag(s.edges()));
        out.add(compile());
        return out;
    }

    // ─── Writes (DEFINE statements) ──────────────────────────────────────────

    /**
     * DEFINE CAUSAL VARIABLE → MERGE a :Variable node.
     * Extended DSL metadata (unit, role, binding) is NOT persisted here —
     * it's kept in the DSL-side registry. Only fields the plugin schema
     * requires are written. A standalone variable carries no linear-Gaussian
     * parameters (those are only declarable inside a DEFINE MODEL block).
     */
    public CypherQuery defineVariable(DefineVariableStmt s) {
        return variableMerge(s.name(), s.type() == VariableType.LATENT, null, null);
    }

    /**
     * A DEFINE MODEL variable declaration → MERGE a :Variable node, persisting
     * the linear-Gaussian parameters ({@code intercept} μ₀, {@code noise} σ²)
     * the plugin's SEM engine reads. Undeclared parameters are written as null
     * (removing the property) so the plugin falls back to its defaults.
     */
    public CypherQuery defineVariable(VariableDecl vd) {
        return variableMerge(vd.name(), vd.type() == VariableType.LATENT, vd.mean(), vd.noise());
    }

    /**
     * Shared MERGE for a :Variable node. {@code intercept}/{@code noise} may be
     * null (undeclared) — setting a property to null removes it, so the plugin's
     * GraphLoader reads its default (intercept 0.0, noise variance 1.0).
     */
    private CypherQuery variableMerge(String name, boolean latent, Double intercept, Double noise) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", name);
        params.put("name", name);
        params.put("observed", !latent);
        params.put("latent", latent);
        params.put("intercept", intercept);   // null → property removed → plugin default
        params.put("noise", noise);
        return CypherQuery.write(
            "MERGE (v:Variable {id: $id}) " +
            "SET v.name = $name, v.observed = $observed, v.latent = $latent, " +
            "    v.intercept = $intercept, v.noise = $noise",
            params
        );
    }

    /**
     * DEFINE CAUSAL DAG Model { A -> B  B -> C } → MERGE edges.
     * Returns a batch Cypher that creates all edges in one query via UNWIND.
     * The linear-Gaussian coefficient β is persisted as the {@code weight}
     * property (null → removed → plugin default 1.0), which the plugin's
     * GraphLoader reads via {@code coalesce(r.weight, 1.0)}.
     */
    public CypherQuery defineDag(List<DagEdge> edges) {
        List<Map<String, Object>> edgeMaps = new ArrayList<>();
        for (DagEdge e : edges) {
            Map<String, Object> m = new HashMap<>();
            m.put("from", e.from());
            m.put("to", e.to());
            m.put("weight", e.weight());   // null when no WEIGHT clause was declared
            edgeMaps.add(m);
        }
        return CypherQuery.write(
            "UNWIND $edges AS e " +
            "MERGE (a:Variable {id: e.from}) ON CREATE SET a.name = e.from, a.observed = true, a.latent = false " +
            "MERGE (b:Variable {id: e.to})   ON CREATE SET b.name = e.to,   b.observed = true, b.latent = false " +
            "MERGE (a)-[r:CAUSES]->(b) ON CREATE SET r.active = true " +
            "SET r.weight = e.weight",
            Map.of("edges", edgeMaps)
        );
    }

    /** Trigger a recompile of the plugin's in-memory snapshot. */
    public CypherQuery compile() {
        return CypherQuery.read(
            "CALL " + ProcedureNames.COMPILE + "() YIELD " + ResultColumns.VARIABLE_COUNT +
            " RETURN " + ResultColumns.VARIABLE_COUNT,
            Map.of(), ResultColumns.VARIABLE_COUNT);
    }

    // ─── Reads (structural + identification) ─────────────────────────────────

    public CypherQuery causesOf(String var)      { return callList(ProcedureNames.PARENTS,     var); }
    public CypherQuery effectsOf(String var)     { return callList(ProcedureNames.CHILDREN,    var); }
    public CypherQuery ancestorsOf(String var)   { return callList(ProcedureNames.ANCESTORS,   var); }
    public CypherQuery descendantsOf(String var) { return callList(ProcedureNames.DESCENDANTS, var); }

    public CypherQuery mediators(String from, String to)    { return callPair(ProcedureNames.MEDIATORS, from, to); }
    public CypherQuery colliders(String x, String y)        { return callPair(ProcedureNames.COLLIDERS, x, y); }
    public CypherQuery confounders(String x, String y)      { return callPair(ProcedureNames.CONFOUNDERS, x, y); }
    public CypherQuery backdoorPaths(String t, String y)    { return callPair(ProcedureNames.BACKDOOR_VARIABLES, t, y); }

    public CypherQuery adjustmentCandidate(String treatment, String outcome) {
        String cols = ResultColumns.ADJUSTMENT_SET_EXISTS + ", " +
                      ResultColumns.CANDIDATE_SETS + ", " +
                      ResultColumns.EXPLANATION;
        return CypherQuery.read(
            "MATCH (t:Variable {id: $t}), (o:Variable {id: $o}) " +
            "CALL " + ProcedureNames.ADJUSTMENT_CANDIDATE + "(t, o) " +
            "YIELD " + cols + " " +
            "RETURN " + cols,
            Map.of("t", treatment, "o", outcome),
            null // multi-column — handled specially
        );
    }

    // ─── Quantitative layer (linear-Gaussian SEM) ───────────────────────────

    /**
     * INTERVENE numerics → {@code causal.linearGaussianInference}. Returns the
     * interventional means/variances (keyed by variable id) and the total effect
     * of {@code treatment} on {@code outcome} under {@code do(doValues)}.
     *
     * <p>The procedure requires the persisted graph to carry linear-Gaussian
     * parameters; callers must only invoke this against a parameterized graph.
     */
    public CypherQuery linearGaussianInference(String treatment, String outcome, Map<String, Double> doValues) {
        String cols = ResultColumns.MEANS + ", " + ResultColumns.VARIANCES + ", " + ResultColumns.TOTAL_EFFECT;
        return CypherQuery.read(
            "MATCH (t:Variable {id: $t}), (o:Variable {id: $o}) " +
            "CALL " + ProcedureNames.LINEAR_GAUSSIAN_INFERENCE + "(t, o, $doValues) " +
            "YIELD " + cols + " " +
            "RETURN " + cols,
            Map.of("t", treatment, "o", outcome, "doValues", doValues),
            null // multi-column — read explicitly by the executor
        );
    }

    // ─── Inspection ──────────────────────────────────────────────────────────

    public CypherQuery showDagVariables() {
        return CypherQuery.read(
            "MATCH (v:Variable) RETURN v.id AS id ORDER BY id",
            Map.of(),
            "id"
        );
    }

    public CypherQuery showDagEdges() {
        return CypherQuery.read(
            "MATCH (a:Variable)-[r:CAUSES]->(b:Variable) " +
            "WHERE coalesce(r.active, true) = true RETURN a.id AS from, b.id AS to ORDER BY from, to",
            Map.of(),
            null
        );
    }

    public CypherQuery describeVariable(String id) {
        return CypherQuery.read(
            "MATCH (v:Variable {id: $id}) RETURN v.latent AS latent, v.observed AS observed",
            Map.of("id", id),
            null
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private CypherQuery callList(String procedure, String var) {
        String v = ResultColumns.VARIABLE;
        return CypherQuery.read(
            "MATCH (anchor:Variable {id: $var}) " +
            "CALL " + procedure + "([anchor]) YIELD " + v + " " +
            "RETURN " + v + ".id AS variableId ORDER BY variableId",
            Map.of("var", var),
            "variableId"
        );
    }

    private CypherQuery callPair(String procedure, String a, String b) {
        String v = ResultColumns.VARIABLE;
        return CypherQuery.read(
            "MATCH (a:Variable {id: $a}), (b:Variable {id: $b}) " +
            "CALL " + procedure + "(a, b) YIELD " + v + " " +
            "RETURN " + v + ".id AS variableId ORDER BY variableId",
            Map.of("a", a, "b", b),
            "variableId"
        );
    }
}
