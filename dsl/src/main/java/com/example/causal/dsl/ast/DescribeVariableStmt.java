package com.example.causal.dsl.ast;

/** DESCRIBE VARIABLE <name> */
public record DescribeVariableStmt(String name) implements Statement {}
