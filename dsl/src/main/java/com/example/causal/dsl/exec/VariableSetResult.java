package com.example.causal.dsl.exec;

import java.util.Set;

/** Result of a structural query that returns a set of variable names. */
public record VariableSetResult(String query, Set<String> variables) implements QueryResult {

    @Override
    public String toString() {
        return query + " → " + variables;
    }
}
