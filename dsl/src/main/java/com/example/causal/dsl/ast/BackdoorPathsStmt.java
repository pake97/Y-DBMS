package com.example.causal.dsl.ast;

/** BACKDOOR PATHS BETWEEN <from> AND <to> */
public record BackdoorPathsStmt(String from, String to) implements Statement {}
