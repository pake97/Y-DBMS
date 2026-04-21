package com.example.causal.dsl.exec;

/** Sealed result type returned by QueryExecutor for every statement. */
public sealed interface QueryResult permits
    VariableSetResult,
    IdentificationResult,
    TextResult {}
