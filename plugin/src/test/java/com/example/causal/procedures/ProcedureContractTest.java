package com.example.causal.procedures;

import com.example.causal.api.ProcedureNames;
import com.example.causal.api.ResultColumns;
import com.example.causal.dto.AdjustmentRecord;
import com.example.causal.dto.DSeparationRecord;
import com.example.causal.dto.SnapshotRecord;
import com.example.causal.dto.VariableNodeRecord;
import org.junit.jupiter.api.Test;
import org.neo4j.procedure.Procedure;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the contract that the DSL relies on across the Bolt boundary: the
 * {@code causal.*} procedure names and their {@code YIELD} column names must
 * match the {@link ProcedureNames} / {@link ResultColumns} constants the DSL's
 * {@code CypherTranslator} builds its queries from.
 *
 * <p>Pure reflection — no Neo4j harness. If a DTO field is renamed without
 * updating {@link ResultColumns} (the "YIELD size" class of bug), or a procedure
 * is registered under a name that is not a {@link ProcedureNames} constant, this
 * test fails at build time instead of at runtime against a live server.
 */
class ProcedureContractTest {

    /** Public, non-static fields of a DTO are exactly its Cypher output columns. */
    private static Set<String> outputColumns(Class<?> dto) {
        return Arrays.stream(dto.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()))
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** All String constant values declared on a constants holder class. */
    private static Set<String> constantValues(Class<?> holder) {
        return Arrays.stream(holder.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    void everyDtoFieldIsADeclaredResultColumn() {
        Set<String> declared = constantValues(ResultColumns.class);
        for (Class<?> dto : new Class<?>[]{
                SnapshotRecord.class, VariableNodeRecord.class,
                AdjustmentRecord.class, DSeparationRecord.class}) {
            for (String column : outputColumns(dto)) {
                assertTrue(declared.contains(column),
                        dto.getSimpleName() + " exposes YIELD column '" + column +
                        "' that is not a ResultColumns constant — the DSL cannot reference it");
            }
        }
    }

    @Test
    void everyResultColumnConstantBacksADtoField() {
        Set<String> dtoFields = new LinkedHashSet<>();
        for (Class<?> dto : new Class<?>[]{
                SnapshotRecord.class, VariableNodeRecord.class,
                AdjustmentRecord.class, DSeparationRecord.class}) {
            dtoFields.addAll(outputColumns(dto));
        }
        for (String constant : constantValues(ResultColumns.class)) {
            assertTrue(dtoFields.contains(constant),
                    "ResultColumns." + constant + " maps to no DTO field — stale constant");
        }
    }

    @Test
    void everyRegisteredProcedureNameIsAProcedureNamesConstant() {
        Set<String> declared = constantValues(ProcedureNames.class);
        for (Method m : CausalProcedures.class.getDeclaredMethods()) {
            Procedure ann = m.getAnnotation(Procedure.class);
            if (ann == null) continue;
            assertTrue(declared.contains(ann.name()),
                    "CausalProcedures." + m.getName() + " is registered as '" + ann.name() +
                    "' which is not a ProcedureNames constant — the DSL cannot call it by name");
        }
    }

    @Test
    void everyProcedureNamesConstantIsRegistered() {
        Set<String> registered = Arrays.stream(CausalProcedures.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(Procedure.class))
                .filter(a -> a != null)
                .map(Procedure::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String constant : constantValues(ProcedureNames.class)) {
            assertTrue(registered.contains(constant),
                    "ProcedureNames." + constant + " is registered by no procedure — stale constant");
        }
    }
}
