package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.model.VariableType;

import java.util.ArrayList;
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
            case InterveneStmt      s -> List.of();
            case CounterfactualStmt s -> List.of();
        };
    }

    private List<CypherQuery> translateDefineModel(DefineModelStmt s) {
        List<CypherQuery> out = new ArrayList<>();
        for (VariableDecl vd : s.variables()) {
            out.add(defineVariable(new DefineVariableStmt(
                vd.name(), vd.unit(), vd.binding(), vd.type(), vd.role())));
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
     * requires are written.
     */
    public CypherQuery defineVariable(DefineVariableStmt s) {
        boolean latent = s.type() == VariableType.LATENT;
        return CypherQuery.write(
            "MERGE (v:Variable {id: $id}) " +
            "ON CREATE SET v.name = $name, v.observed = $observed, v.latent = $latent " +
            "ON MATCH  SET v.name = $name, v.observed = $observed, v.latent = $latent",
            Map.of(
                "id", s.name(),
                "name", s.name(),
                "observed", !latent,
                "latent", latent
            )
        );
    }

    /**
     * DEFINE CAUSAL DAG Model { A -> B  B -> C } → MERGE edges.
     * Returns a batch Cypher that creates all edges in one query via UNWIND.
     */
    public CypherQuery defineDag(List<DagEdge> edges) {
        List<Map<String, String>> edgeMaps = new ArrayList<>();
        for (DagEdge e : edges) edgeMaps.add(Map.of("from", e.from(), "to", e.to()));
        return CypherQuery.write(
            "UNWIND $edges AS e " +
            "MERGE (a:Variable {id: e.from}) ON CREATE SET a.name = e.from, a.observed = true, a.latent = false " +
            "MERGE (b:Variable {id: e.to})   ON CREATE SET b.name = e.to,   b.observed = true, b.latent = false " +
            "MERGE (a)-[r:CAUSES]->(b) ON CREATE SET r.active = true",
            Map.of("edges", edgeMaps)
        );
    }

    /** Trigger a recompile of the plugin's in-memory snapshot. */
    public CypherQuery compile() {
        return CypherQuery.read(
            "CALL causal.compile() YIELD variableCount RETURN variableCount",
            Map.of(), "variableCount");
    }

    // ─── Reads (structural + identification) ─────────────────────────────────

    public CypherQuery causesOf(String var)      { return callList("causal.parents",     var); }
    public CypherQuery effectsOf(String var)     { return callList("causal.children",    var); }
    public CypherQuery ancestorsOf(String var)   { return callList("causal.ancestors",   var); }
    public CypherQuery descendantsOf(String var) { return callList("causal.descendants", var); }

    public CypherQuery mediators(String from, String to)    { return callPair("causal.mediators", from, to); }
    public CypherQuery colliders(String x, String y)        { return callPair("causal.colliders", x, y); }
    public CypherQuery confounders(String x, String y)      { return callPair("causal.confounders", x, y); }
    public CypherQuery backdoorPaths(String t, String y)    { return callPair("causal.backdoorVariables", t, y); }

    public CypherQuery adjustmentCandidate(String treatment, String outcome) {
        return CypherQuery.read(
            "MATCH (t:Variable {id: $t}), (o:Variable {id: $o}) " +
            "CALL causal.adjustmentCandidate(t, o) " +
            "YIELD adjustmentSetExists, candidateSets, explanation " +
            "RETURN adjustmentSetExists, candidateSets, explanation",
            Map.of("t", treatment, "o", outcome),
            null // multi-column — handled specially
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
            "WHERE r.active = true RETURN a.id AS from, b.id AS to ORDER BY from, to",
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
        return CypherQuery.read(
            "MATCH (anchor:Variable {id: $var}) " +
            "CALL " + procedure + "([anchor]) YIELD variable " +
            "RETURN variable.id AS variableId ORDER BY variableId",
            Map.of("var", var),
            "variableId"
        );
    }

    private CypherQuery callPair(String procedure, String a, String b) {
        return CypherQuery.read(
            "MATCH (a:Variable {id: $a}), (b:Variable {id: $b}) " +
            "CALL " + procedure + "(a, b) YIELD variable " +
            "RETURN variable.id AS variableId ORDER BY variableId",
            Map.of("a", a, "b", b),
            "variableId"
        );
    }
}
