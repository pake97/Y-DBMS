package com.example.causal.runtime;

import com.example.causal.api.VariableRef;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.testsupport.Graphs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Definitions, the active world, recursion and cache invalidation of {@link WorldRegistry}. */
class WorldRegistryTest {

    // Z -> X -> Y, Z -> Y, X -> M -> Y, plus five unrelated edges (14 variables)
    private static final List<String> EDGES = List.of(
            "Z->X", "X->Y", "Z->Y", "X->M", "M->Y", "P->Q", "R->S", "T->U", "V->W", "A->B");

    private DefaultCausalGraphRuntime base;
    private WorldRegistry worlds;

    @BeforeEach
    void setUp() {
        base = new DefaultCausalGraphRuntime();
        base.install(Graphs.fromEdges(EDGES));
        worlds = new WorldRegistry(() -> base);
    }

    private static Set<VariableRef> refs(String... ids) {
        Set<VariableRef> s = new java.util.LinkedHashSet<>();
        for (String id : ids) s.add(new VariableRef(id));
        return s;
    }

    @Test
    void baseIsListedActiveAndProtected() {
        assertEquals(WorldRegistry.BASE, worlds.active());
        WorldRegistry.Info b = worlds.list().get(0);
        assertEquals(WorldRegistry.BASE, b.name());
        assertTrue(b.active() && b.materialized());
        assertEquals(14, b.nodes());
        assertEquals(10, b.edges());
        assertSame(base.currentSnapshot(), worlds.snapshot(""));
        assertThrows(IllegalArgumentException.class, () -> worlds.drop(WorldRegistry.BASE));
        assertThrows(IllegalArgumentException.class, () -> worlds.define(WorldRegistry.BASE, "", refs("X")));
    }

    @Test
    void defineUseDropAndList() {
        assertEquals(List.of("X"), worlds.define("w", "", refs("X")));
        CompiledCausalGraph g = worlds.snapshot("w");
        assertEquals(Set.of(), g.parentsOf("X"));
        assertEquals(Set.of("X", "M", "Z"), g.toIds(g.ancestorBits(g.indexOf("Y"))), "Z -> Y remains");
        assertEquals(Set.of(), g.toIds(g.ancestorBits(g.indexOf("X"))));

        WorldRegistry.Derivation d = worlds.derivation("w");
        assertEquals(1, d.removedEdges());
        assertEquals(28, d.totalRows());
        assertEquals(d.totalRows() - d.sharedRows(), d.coneSize());
        assertFalse(d.fallback(), "cone {X, M, Y} + {Z} = 4 <= 0.5 * 14");

        worlds.use("w");
        assertEquals("w", worlds.active());
        assertSame(g, worlds.snapshot(""), "blank reads the active world, from the cache");
        assertEquals(List.of("base", "w"), worlds.list().stream().map(WorldRegistry.Info::name).toList());
        assertTrue(worlds.list().get(1).active());

        assertEquals(List.of("M", "X"), worlds.define("w2", "", refs("M")), "from the active world w");
        assertThrows(IllegalArgumentException.class, () -> worlds.define("w", "", refs("M")), "w from itself");
    }

    @Test
    void recursiveDefinitionsUnionTheirSets() {
        worlds.define("w1", "base", refs("X"));
        worlds.define("w2", "w1", refs("M"));
        assertEquals(List.of("M", "X"), worlds.resolved("w2"));
        CompiledCausalGraph g = worlds.snapshot("w2");
        assertEquals(Set.of(), g.parentsOf("X"));
        assertEquals(Set.of(), g.parentsOf("M"));
        assertEquals(Set.of("M", "X", "Z"), g.toIds(g.ancestorBits(g.indexOf("Y"))));
        assertEquals(Set.of(), g.toIds(g.ancestorBits(g.indexOf("M"))));
        assertThrows(IllegalArgumentException.class, () -> worlds.drop("w1"), "w2 is defined from w1");
        assertThrows(IllegalArgumentException.class, () -> worlds.define("w1", "w2", refs("Z")), "cycle");
        worlds.drop("w2");
        worlds.drop("w1");
        assertEquals(List.of("base"), worlds.list().stream().map(WorldRegistry.Info::name).toList());
    }

    @Test
    void droppingTheActiveWorldFallsBackToBase() {
        worlds.define("w", "", refs("X"));
        worlds.use("w");
        worlds.drop("w");
        assertEquals(WorldRegistry.BASE, worlds.active());
        assertThrows(IllegalArgumentException.class, () -> worlds.use("w"));
        assertThrows(IllegalArgumentException.class, () -> worlds.snapshot("nope"));
    }

    @Test
    void cacheIsDroppedWhenTheBaseChangesAndRecomputed() {
        worlds.define("w", "", refs("X"));
        CompiledCausalGraph before = worlds.snapshot("w");
        assertTrue(worlds.list().get(1).materialized());

        // the base gains P -> Y: the derived world follows without a new definition
        assertTrue(base.applyChange(com.example.causal.runtime.ChangeBatch.builder().addedEdge("P", "Y").build()));
        CompiledCausalGraph after = worlds.snapshot("w");
        assertNotSame(before, after);
        assertTrue(after.toIds(after.ancestorBits(after.indexOf("Y"))).contains("P"));
        assertEquals(Set.of(), after.parentsOf("X"));

        base.invalidate();
        assertFalse(worlds.list().get(1).materialized());
        assertThrows(IllegalStateException.class, () -> worlds.snapshot("w"));
    }

    @Test
    void aWorldRuntimeAnswersOnItsSnapshotAndIsReadOnly() {
        worlds.define("w", "", refs("X"));
        var rt = worlds.runtime("w");
        assertEquals(Set.of(new VariableRef("M"), new VariableRef("Y")), rt.descendants(refs("X")));
        assertEquals(Set.of(), rt.ancestors(refs("X")));
        assertEquals(refs("Z"), worlds.runtime("base").ancestors(refs("X")));
        assertSame(base, worlds.runtime(""), "the active world is base: the base runtime itself");
        assertTrue(rt.adjustmentSets(new VariableRef("X"), new VariableRef("Y"), "all-minimal", 10).identifiable());
        assertThrows(UnsupportedOperationException.class, rt::invalidate);
        assertNotNull(base.currentSnapshot(), "the base is untouched");
    }
}
