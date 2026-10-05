package com.example.causal.dto;

/**
 * One variable id: the output row of the {@code causal.*Ids} procedures, which return
 * ids straight from the snapshot without binding them to Neo4j nodes.
 */
public class IdRecord {
    public final String id;

    public IdRecord(String id) {
        this.id = id;
    }
}
