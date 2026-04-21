package com.example.causal.dsl.ast;

/** A directed edge in a DAG declaration: from -> to. */
public record DagEdge(String from, String to) {}
