package com.example.causal.bench.db;

import org.neo4j.graphdb.GraphDatabaseService;

import java.util.Map;

/**
 * Neo4j Graph Data Science traversals over two in-memory projections (paper baseline c):
 * 'c4j' (NATURAL) and 'c4j_rev' (REVERSE). GDS has no path-role or d-separation operator.
 */
public final class GdsBaseline {

    private static final String DFS =
            "MATCH (s:Variable {id:$id}) CALL gds.dfs.stream($g, {sourceNode: s}) YIELD nodeIds RETURN size(nodeIds) - 1";
    private static final String REACHES =
            "MATCH (s:Variable {id:$u}), (t:Variable {id:$v}) "
            + "CALL gds.bfs.stream('c4j', {sourceNode: s, targetNodes: [t]}) YIELD nodeIds "
            + "RETURN s <> t AND last(nodeIds) = id(t)";

    private final GraphDatabaseService db;

    public GdsBaseline(EmbeddedNeo4j neo) {
        this.db = neo.db();
        neo.run("CALL gds.graph.project('c4j', 'Variable', {CAUSES: {orientation: 'NATURAL'}})", Map.of());
        neo.run("CALL gds.graph.project('c4j_rev', 'Variable', {CAUSES: {orientation: 'REVERSE'}})", Map.of());
    }

    /** |an(id)|, or QueryTimeout.TIMEOUT. */
    public long ancestors(String id)   { return size("c4j_rev", id); }
    /** |de(id)|, or QueryTimeout.TIMEOUT. */
    public long descendants(String id) { return size("c4j", id); }

    /** 1 if reachable, 0 if not, QueryTimeout.TIMEOUT. */
    public long reaches(String u, String v) {
        Object r = QueryTimeout.single(db, REACHES, Map.of("u", u, "v", v));
        return r == null ? QueryTimeout.TIMEOUT : ((Boolean) r ? 1 : 0);
    }

    private long size(String graph, String id) {
        Object r = QueryTimeout.single(db, DFS, Map.of("g", graph, "id", id));
        return r == null ? QueryTimeout.TIMEOUT : ((Number) r).longValue();
    }
}
