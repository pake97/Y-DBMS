package com.example.causal.bench.db;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One read query with a transaction timeout; a timed-out query is reported, never dropped. */
public final class QueryTimeout {

    public static final long SECONDS = 60;
    /** Returned instead of a row count when the query hit the timeout. */
    public static final long TIMEOUT = -1;

    private QueryTimeout() {}

    /** Rows produced (fully consumed), or {@link #TIMEOUT}. */
    public static long count(GraphDatabaseService db, String cypher, Map<String, Object> params) {
        try (Transaction tx = db.beginTx(SECONDS, TimeUnit.SECONDS)) {
            Result r = tx.execute(cypher, params);
            long rows = 0;
            while (r.hasNext()) { r.next(); rows++; }
            tx.commit();
            return rows;
        } catch (RuntimeException e) {
            if (isTimeout(e)) return TIMEOUT;
            throw e;
        }
    }

    /** First column of the single row, or null on timeout. */
    public static Object single(GraphDatabaseService db, String cypher, Map<String, Object> params) {
        try (Transaction tx = db.beginTx(SECONDS, TimeUnit.SECONDS)) {
            Result r = tx.execute(cypher, params);
            Object v = r.hasNext() ? r.next().values().iterator().next() : null;
            while (r.hasNext()) r.next();
            tx.commit();
            return v;
        } catch (RuntimeException e) {
            if (isTimeout(e)) return null;
            throw e;
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage()).toLowerCase();
            if (m.contains("timeout") || m.contains("timed out") || m.contains("terminated")) return true;
        }
        return false;
    }
}
