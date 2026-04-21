package com.example.causal.dsl.ast;

/** MEDIATORS BETWEEN <from> AND <to> */
public record MediatorsBetweenStmt(String from, String to) implements Statement {}
