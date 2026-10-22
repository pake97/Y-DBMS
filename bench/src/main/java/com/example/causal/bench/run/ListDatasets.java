package com.example.causal.bench.run;

import com.example.causal.bench.data.Datasets;
import com.example.causal.bench.data.Datasets.Dataset;

import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Prints comma-separated dataset ids for JMH's -p dataset=..., filtered by
 * family=, n=, degree=, model=, seed=, id= (repeatable, values comma-separated), e.g.
 * {@code family=synthetic degree=4 seed=0}; minm=&lt;M&gt; keeps datasets with at least M edges.
 */
public final class ListDatasets {
    public static void main(String[] args) {
        Predicate<Dataset> f = d -> true;
        for (String a : args) {
            String key = a.substring(0, a.indexOf('=')), vals = "," + a.substring(a.indexOf('=') + 1) + ",";
            if (key.equals("minm")) {
                int min = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                f = f.and(d -> d.meta().get("m").asInt() >= min);
                continue;
            }
            f = f.and(d -> vals.contains("," + switch (key) {
                case "family" -> d.family();
                case "n" -> String.valueOf(d.n());
                case "degree" -> String.valueOf(d.avgDegree());
                case "model" -> d.model();
                case "seed" -> String.valueOf(d.seed());
                case "id" -> d.id();
                default -> throw new IllegalArgumentException("unknown filter " + key);
            } + ","));
        }
        System.out.println(Datasets.where(f).stream().map(Dataset::id).collect(Collectors.joining(",")));
    }
}
