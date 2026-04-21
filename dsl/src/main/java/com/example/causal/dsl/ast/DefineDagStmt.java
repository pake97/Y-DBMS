package com.example.causal.dsl.ast;

import java.util.List;

/** DEFINE CAUSAL DAG <name> { A -> B  B -> C } */
public record DefineDagStmt(
    String name,
    List<DagEdge> edges
) implements Statement {}
