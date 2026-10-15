package com.example.causal.bench;

import com.example.causal.bench.data.Datasets;
import com.example.causal.bench.data.EdgeList;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.db.CypherBaseline;
import com.example.causal.bench.db.EmbeddedNeo4j;
import com.example.causal.bench.db.GdsBaseline;
import com.example.causal.bench.graph.SnapshotFactory;
import com.example.causal.bench.jmh.ProcedureBenchmarks;
import com.example.causal.runtime.algorithms.PathAnalyzer;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Every baseline answers the same question as Causal4j before anything is timed. */
class BaselineAgreementTest {

    @ParameterizedTest
    @ValueSource(strings = {"asia", "alarm", "covid"})
    void cypherAndGdsAgreeWithTheIndex(String dataset) {
        // bnlearn edge lists are gitignored; the bench-quick CI job fetches asia and alarm.
        assumeTrue(java.nio.file.Files.exists(Datasets.edgesPath(dataset)),
                () -> dataset + " edges.csv missing: causal4j-bench fetch --only " + dataset);
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
                assertEquals(idx.ancestors(id).size(),
                        ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.ANCESTORS, Map.of("id", id)), "procedure an(" + id + ")");
                assertEquals(idx.descendants(id).size(),
                        ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.DESCENDANTS, Map.of("id", id)), "procedure de(" + id + ")");
                assertEquals(idx.ancestors(id).size(),
                        ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.ANCESTOR_IDS, Map.of("id", id)), "ancestorIds(" + id + ")");
                assertEquals(idx.descendants(id).size(),
                        ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.DESCENDANT_IDS, Map.of("id", id)), "descendantIds(" + id + ")");
            }
            for (Workloads.Pair p : Workloads.pairs(dataset)) {
                assertEquals(1, cy.reaches(p.t(), p.y()));
                assertEquals(1, gds.reaches(p.t(), p.y()));
                assertEquals(0, cy.reaches(p.y(), p.t()), "no path back in a DAG");
                assertEquals(0, gds.reaches(p.y(), p.t()), "no path back in a DAG");
                assertEquals(pa.confounders(p.t(), p.y()).size(), cy.confounders(p.t(), p.y()), "confounders " + p);
                assertEquals(pa.mediators(p.t(), p.y()).size(), cy.mediators(p.t(), p.y()), "mediators " + p);
                Map<String, Object> ty = Map.of("t", p.t(), "y", p.y());
                assertEquals(1, ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.REACHES, Map.of("u", p.t(), "v", p.y())));
                assertEquals(0, ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.REACHES, Map.of("u", p.y(), "v", p.t())));
                assertEquals(pa.confounders(p.t(), p.y()).size(),
                        ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.CONFOUNDERS, ty), "procedure confounders " + p);
                assertEquals(pa.mediators(p.t(), p.y()).size(),
                        ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.MEDIATORS, ty), "procedure mediators " + p);
                assertEquals(pa.confounders(p.t(), p.y()).size(),
                        ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.CONFOUNDER_IDS, ty), "confounderIds " + p);
                assertEquals(pa.mediators(p.t(), p.y()).size(),
                        ProcedureBenchmarks.answer(neo, ProcedureBenchmarks.MEDIATOR_IDS, ty), "mediatorIds " + p);
            }
        }
    }
}
