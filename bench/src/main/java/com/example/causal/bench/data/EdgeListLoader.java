package com.example.causal.bench.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;

/** Reads a dataset's edges.csv (header from,to) and meta.json. */
public final class EdgeListLoader {

    private EdgeListLoader() {}

    public static EdgeList load(String datasetId) {
        return load(Datasets.byId(datasetId));
    }

    /**
     * Node order: meta.json "nodes" (bnlearn, covid), else v0..v{n-1} (synthetic).
     * Latent ids: meta.json "latent", which WP5a parses from dev/seed-dag.cypher's
     * latent flags for covid; empty for every other dataset.
     */
    public static EdgeList load(Datasets.Dataset d) {
        JsonNode meta = d.meta();
        List<String> ids = new ArrayList<>();
        if (meta.has("nodes")) meta.get("nodes").forEach(x -> ids.add(x.asText()));
        else for (int i = 0; i < d.n(); i++) ids.add("v" + i);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) index.put(ids.get(i), i);

        List<int[]> edges = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(Datasets.byId(d.id()).dir().resolve("edges.csv"))) {
            String header = r.readLine();
            if (!"from,to".equals(header)) throw new IllegalStateException("unexpected header " + header);
            for (String line; (line = r.readLine()) != null; ) {
                int comma = line.indexOf(',');
                Integer a = index.get(line.substring(0, comma)), b = index.get(line.substring(comma + 1));
                if (a == null || b == null) throw new IllegalStateException(d.id() + ": unknown id in " + line);
                edges.add(new int[]{a, b});
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (ids.size() != d.n() || edges.size() != meta.get("m").asInt()) {
            throw new IllegalStateException(d.id() + ": edges.csv does not match meta.json");
        }
        Set<String> latent = new LinkedHashSet<>();
        meta.path("latent").forEach(x -> latent.add(x.asText()));
        return new EdgeList(d.id(), List.copyOf(ids), edges.toArray(new int[0][]), Set.copyOf(latent));
    }
}
