package com.example.causal.bench.data;

import java.util.List;
import java.util.Set;

/** A DAG as WP5a wrote it: ids in dataset order, edges as {from, to} index pairs, latent ids. */
public record EdgeList(String dataset, List<String> ids, int[][] edges, Set<String> latent) {
    public int n() { return ids.size(); }
    public int m() { return edges.length; }
}
