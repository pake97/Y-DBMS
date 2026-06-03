package com.example.causal.dsl.ast;

/**
 * A directed edge in a DAG declaration: {@code from -> to}.
 *
 * <p>{@code weight} is the linear-Gaussian structural coefficient β for the edge
 * (from an optional {@code WEIGHT} clause); null when none was declared, in which
 * case the SEM treats it as the default 1.0.
 */
public record DagEdge(String from, String to, Double weight) {}
