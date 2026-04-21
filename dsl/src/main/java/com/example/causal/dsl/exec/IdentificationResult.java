package com.example.causal.dsl.exec;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Result of IDENTIFY EFFECT OF <treatment> ON <outcome>. */
public record IdentificationResult(
    String treatment,
    String outcome,
    boolean identifiable,
    String method,
    List<Set<String>> adjustmentSets,
    String explanation
) implements QueryResult {

    @Override
    public String toString() {
        String sets = adjustmentSets.stream()
            .map(s -> "[" + String.join(", ", s) + "]")
            .collect(Collectors.joining(", "));
        return String.format(
            "IDENTIFY EFFECT OF %s ON %s%n  identifiable: %s%n  method: %s%n  adjustment sets: %s%n  %s",
            treatment, outcome, identifiable, method,
            adjustmentSets.isEmpty() ? "(none)" : sets,
            explanation
        );
    }
}
