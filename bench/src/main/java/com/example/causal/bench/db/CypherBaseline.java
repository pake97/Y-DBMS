package com.example.causal.bench.db;

import org.neo4j.graphdb.GraphDatabaseService;

import java.util.Map;

/** Plain Cypher variable-length patterns for the tier (a) operators (paper baseline b). */
public final class CypherBaseline {

    public static final String ANCESTORS =
            "MATCH (x:Variable)-[:CAUSES*]->(v:Variable {id:$id}) RETURN DISTINCT x.id";
    public static final String DESCENDANTS =
            "MATCH (v:Variable {id:$id})-[:CAUSES*]->(x:Variable) RETURN DISTINCT x.id";
    public static final String REACHES =
            "RETURN EXISTS { MATCH (:Variable {id:$u})-[:CAUSES*]->(:Variable {id:$v}) } AS r";
    public static final String CONFOUNDERS =
            "MATCH (x:Variable)-[:CAUSES*]->(:Variable {id:$t}) WITH collect(DISTINCT x) AS a "
            + "MATCH (x:Variable)-[:CAUSES*]->(:Variable {id:$y}) WHERE x IN a RETURN DISTINCT x.id";
    public static final String MEDIATORS =
            "MATCH (:Variable {id:$t})-[:CAUSES*]->(m:Variable)-[:CAUSES*]->(:Variable {id:$y}) RETURN DISTINCT m.id";

    private final GraphDatabaseService db;

    public CypherBaseline(GraphDatabaseService db) { this.db = db; }

    /** Each returns the answer size, or QueryTimeout.TIMEOUT. */
    public long ancestors(String id)          { return QueryTimeout.count(db, ANCESTORS, Map.of("id", id)); }
    public long descendants(String id)        { return QueryTimeout.count(db, DESCENDANTS, Map.of("id", id)); }
    public long confounders(String t, String y) { return QueryTimeout.count(db, CONFOUNDERS, Map.of("t", t, "y", y)); }
    public long mediators(String t, String y)   { return QueryTimeout.count(db, MEDIATORS, Map.of("t", t, "y", y)); }

    /** 1 if reachable, 0 if not, QueryTimeout.TIMEOUT. */
    public long reaches(String u, String v) {
        Object r = QueryTimeout.single(db, REACHES, Map.of("u", u, "v", v));
        return r == null ? QueryTimeout.TIMEOUT : ((Boolean) r ? 1 : 0);
    }
}
