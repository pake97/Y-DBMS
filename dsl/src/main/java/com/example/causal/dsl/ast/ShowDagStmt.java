package com.example.causal.dsl.ast;

/** SHOW DAG (<name>)? — name is null to show the current/default model */
public record ShowDagStmt(String name) implements Statement {}
