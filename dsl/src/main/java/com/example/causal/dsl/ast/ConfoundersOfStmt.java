package com.example.causal.dsl.ast;

/** CONFOUNDERS OF <x> AND <y> */
public record ConfoundersOfStmt(String x, String y) implements Statement {}
