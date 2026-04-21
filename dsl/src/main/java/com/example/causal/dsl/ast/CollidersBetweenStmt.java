package com.example.causal.dsl.ast;

/** COLLIDERS BETWEEN <from> AND <to> */
public record CollidersBetweenStmt(String from, String to) implements Statement {}
