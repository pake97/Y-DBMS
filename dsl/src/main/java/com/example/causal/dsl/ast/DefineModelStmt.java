package com.example.causal.dsl.ast;

import java.util.List;

/** DEFINE MODEL <name> { VARIABLE ... DAG { edges } } */
public record DefineModelStmt(
    String name,
    List<VariableDecl> variables,
    List<DagEdge> edges
) implements Statement {}
