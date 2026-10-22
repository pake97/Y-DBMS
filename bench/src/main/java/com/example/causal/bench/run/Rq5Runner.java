package com.example.causal.bench.run;

import com.example.causal.bench.data.Datasets;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.graph.SnapshotFactory;
import com.example.causal.runtime.algorithms.Adjustment;
import com.example.causal.runtime.algorithms.DSeparation;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * RQ5: Causal4j in process against the WP5a reference answers, for every dataset with a
 * bench/reference/&lt;ds&gt; directory (or the comma-separated ids in args[1]).
 *
 * <ul>
 *   <li>every triple of triples.csv (dsep.json) and triples_balanced.csv (dsep_balanced.json)
 *       through masked {@code DSeparation.separated};</li>
 *   <li>every pair through {@code Adjustment.decide}: {@code identifiable} and the canonical set
 *       (as sets; a non-identifiable pair agrees when both say so);</li>
 *   <li>non-null reference families against {@code listMinimal} with no limit, as sets of sets;
 *       null families from WP5a's per-pair limits (timeout, more than 1000 sets) get
 *       {@code listMinimal(limit = 100)} written to rq5_to_validate.json for
 *       {@code causal4j-bench reference --validate}. Pairs whose families WP5a skipped by design
 *       (synthetic n &gt; 316, {@code minimal_skipped} in meta.json) get decide only.</li>
 * </ul>
 * Writes rq5.csv (the validation columns are filled by --validate), rq5_disagreements.csv
 * (every mismatch, both answers; the run never fails on one) and rq5_to_validate.json into args[0].
 */
public final class Rq5Runner {

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        System.clearProperty("causal.dsep.mask");  // masked (the default)
        List<String> ids = args.length > 1 ? List.of(args[1].split(","))
                : Datasets.all().stream().map(Datasets.Dataset::id)
                  .filter(id -> Files.isDirectory(Datasets.referenceDir().resolve(id))).toList();
        ObjectNode validate = Datasets.JSON.createObjectNode();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("rq5.csv")));
             PrintWriter dis = new PrintWriter(Files.newBufferedWriter(out.resolve("rq5_disagreements.csv")))) {
            csv.println("dataset,triples,triples_agree,pairs,canonical_agree,identifiable,not_identifiable,"
                    + "families_compared,families_equal,families_validated,sets_validated,sets_failing");
            dis.println("dataset,kind,item,causal4j,reference");
            for (String ds : ids) {
                Rq5Runner r = new Rq5Runner(ds, dis);
                r.dsep(Workloads.dsepReference(ds));
                r.dsep(Workloads.dsepBalancedReference(ds));
                r.adjustment(Workloads.adjustmentReference(ds), validate.putArray(ds));
                csv.println(String.join(",", ds, ""+r.triples, ""+r.triplesAgree, ""+r.pairs, ""+r.canonicalAgree,
                        ""+r.identifiable, ""+(r.pairs - r.identifiable), ""+r.familiesCompared, ""+r.familiesEqual, "", "", ""));
                csv.flush();
                System.out.printf("rq5 %-14s dsep %d/%d, canonical %d/%d, families %d/%d, to validate %d%n", ds,
                        r.triplesAgree, r.triples, r.canonicalAgree, r.pairs, r.familiesEqual, r.familiesCompared,
                        validate.get(ds).size());
            }
        }
        Datasets.JSON.writeValue(out.resolve("rq5_to_validate.json").toFile(), validate);
    }

    private final String ds;
    private final CompiledCausalGraph g;
    private final PrintWriter dis;
    private final boolean minimalSkipped;
    int triples, triplesAgree, pairs, canonicalAgree, identifiable, familiesCompared, familiesEqual;

    private Rq5Runner(String ds, PrintWriter dis) throws IOException {
        this.ds = ds;
        this.dis = dis;
        this.g = SnapshotFactory.fromEdgeList(EdgeListLoader.load(ds));
        JsonNode meta = Datasets.JSON.readTree(Datasets.referenceDir().resolve(ds).resolve("meta.json").toFile());
        this.minimalSkipped = !meta.path("minimal_skipped").isNull() && !meta.path("minimal_skipped").isMissingNode();
    }

    private void dsep(JsonNode ref) {
        for (JsonNode row : ref.get("triples")) {
            List<String> z = strings(row.get("z"));
            boolean expected = row.get("dagitty").isNull() ? row.get("pgmpy").asBoolean() : row.get("dagitty").asBoolean();
            boolean got = DSeparation.separated(g, bits(List.of(row.get("x").asText())),
                    bits(List.of(row.get("y").asText())), bits(z));
            triples++;
            if (got == expected) triplesAgree++;
            else disagree("dsep", row.get("x").asText() + " _||_ " + row.get("y").asText() + " | " + String.join(" ", z), got, expected);
        }
    }

    private void adjustment(JsonNode ref, ArrayNode toValidate) {
        for (JsonNode row : ref.get("pairs")) {
            String ts = row.get("t").asText(), ys = row.get("y").asText();
            int t = g.indexOf(ts), y = g.indexOf(ys);
            String item = ts + " -> " + ys;
            Adjustment.Decision d = Adjustment.decide(g, t, y);
            pairs++;
            if (d.identifiable()) identifiable++;
            boolean refId = row.get("identifiable").asBoolean();
            Set<String> can = d.identifiable() ? g.toIds(d.canonical()) : null;
            Set<String> refCan = row.get("canonical").isNull() ? null : new TreeSet<>(strings(row.get("canonical")));
            if (d.identifiable() == refId && Objects.equals(can == null ? null : new TreeSet<>(can), refCan)) canonicalAgree++;
            else disagree("canonical", item, can, refCan);

            JsonNode minimal = row.get("minimal");
            if (!minimal.isNull()) {
                Set<Set<String>> family = new HashSet<>(), refFamily = new HashSet<>();
                Adjustment.listMinimal(g, t, y, 0, z -> family.add(new TreeSet<>(g.toIds(z))));
                for (JsonNode s : minimal) refFamily.add(new TreeSet<>(strings(s)));
                familiesCompared++;
                if (family.equals(refFamily)) familiesEqual++;
                else disagree("family", item, family.size() + " sets " + family, refFamily.size() + " sets " + refFamily);
            } else if (!minimalSkipped) {
                ObjectNode v = toValidate.addObject().put("t", ts).put("y", ys).put("identifiable", refId);
                ArrayNode sets = v.putArray("sets");
                Adjustment.listMinimal(g, t, y, 100, z -> {
                    ArrayNode s = sets.addArray();
                    g.toIds(z).forEach(s::add);
                });
            }
        }
    }

    private long[] bits(List<String> ids) {
        return DSeparation.bits(g, ids.stream().mapToInt(g::indexOf).toArray());
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(v -> out.add(v.asText()));
        return out;
    }

    private void disagree(String kind, String item, Object got, Object expected) {
        dis.println(String.join(",", ds, kind, quote(item), quote(String.valueOf(got)), quote(String.valueOf(expected))));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
