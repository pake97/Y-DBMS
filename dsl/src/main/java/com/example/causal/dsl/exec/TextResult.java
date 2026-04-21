package com.example.causal.dsl.exec;

/** Free-text result for SHOW DAG, DESCRIBE VARIABLE, and stubs. */
public record TextResult(String text) implements QueryResult {

    @Override
    public String toString() { return text; }
}
