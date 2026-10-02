package com.example.causal.bench;

import com.example.causal.bench.data.EdgeList;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.db.CypherBaseline;
import com.example.causal.bench.db.EmbeddedNeo4j;
import com.example.causal.bench.db.GdsBaseline;
import com.example.causal.bench.graph.SnapshotFactory;
import com.example.causal.runtime.algorithms.PathAnalyzer;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every baseline answers the same question as Causal4j before anything is timed. */
class BaselineAgreementTest {

    @ParameterizedTest
    @ValueSource(strings = {"asia", "alarm", "covid"})
    void cypherAndGdsAgreeWithTheIndex(String dataset) {
        EdgeList el = EdgeListLoader.load(dataset);
        CompiledCausalGraph g = SnapshotFactory.fromEdgeList(el);
        ReachabilityIndex idx = new ReachabilityIndex(g);
        PathAnalyzer pa = new PathAnalyzer(g);
        try (EmbeddedNeo4j neo = EmbeddedNeo4j.start(el)) {
            CypherBaseline cy = new CypherBaseline(neo.db());
            GdsBaseline gds = new GdsBaseline(neo);
            for (String id : el.ids()) {
                assertEquals(idx.ancestors(id).size(), cy.ancestors(id), "cypher an(" + id + ")");
                assertEquals(idx.descendants(id).size(), cy.descendants(id), "cypher de(" + id + ")");
                assertEquals(idx.ancestors(id).size(), gds.ancestors(id), "gds an(" + id + ")");
                assertEquals(idx.descendants(id).size(), gds.descendants(id), "gds de(" + id + ")");
            }
            for (Workloads.Pair p : Workloads.pairs(dataset)) {
                assertEquals(1, cy.reaches(p.t(), p.y()));
                assertEquals(1, gds.reaches(p.t(), p.y()));
                assertEquals(0, cy.reaches(p.y(), p.t()), "no path back in a DAG");
                assertEquals(0, gds.reaches(p.y(), p.t()), "no path back in a DAG");
                assertEquals(pa.confounders(p.t(), p.y()).size(), cy.confounders(p.t(), p.y()), "confounders " + p);
                assertEquals(pa.mediators(p.t(), p.y()).size(), cy.mediators(p.t(), p.y()), "mediators " + p);
            }
        }
    }
}
