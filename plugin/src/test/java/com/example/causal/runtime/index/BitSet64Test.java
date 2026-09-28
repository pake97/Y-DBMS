package com.example.causal.runtime.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitSet64Test {

    private static final int[] BOUNDARY = {0, 63, 64, 65, 127, 128};

    @Test
    void setGetAcrossWordBoundaries() {
        long[] b = BitSet64.empty(130);
        assertEquals(3, b.length);
        for (int i : BOUNDARY) BitSet64.set(b, i);
        for (int i = 0; i < 130; i++) {
            boolean expected = false;
            for (int j : BOUNDARY) expected |= i == j;
            assertEquals(expected, BitSet64.get(b, i), "bit " + i);
        }
        assertArrayEquals(BOUNDARY, BitSet64.stream(b).toArray());
        assertEquals(BOUNDARY.length, BitSet64.cardinality(b));
    }

    @Test
    void wordCount() {
        assertEquals(0, BitSet64.words(0));
        assertEquals(1, BitSet64.words(1));
        assertEquals(1, BitSet64.words(64));
        assertEquals(2, BitSet64.words(65));
    }

    @Test
    void binaryOpsOnThreeWords() {
        long[] a = bits(0, 63, 64, 128);
        long[] b = bits(63, 65, 128, 129);

        long[] or = BitSet64.copy(a);
        BitSet64.or(or, b);
        assertArrayEquals(new int[]{0, 63, 64, 65, 128, 129}, BitSet64.stream(or).toArray());

        long[] and = BitSet64.copy(a);
        BitSet64.and(and, b);
        assertArrayEquals(new int[]{63, 128}, BitSet64.stream(and).toArray());

        long[] andNot = BitSet64.copy(a);
        BitSet64.andNot(andNot, b);
        assertArrayEquals(new int[]{0, 64}, BitSet64.stream(andNot).toArray());

        assertTrue(BitSet64.intersects(a, b));
        assertFalse(BitSet64.intersects(andNot, b));
        assertTrue(BitSet64.isEmpty(BitSet64.empty(130)));
        assertFalse(BitSet64.isEmpty(a));
        assertEquals(BitSet64.cardinality(or), BitSet64.stream(or).count());
        assertEquals("1000", BitSet64.toBinaryString(bits(0), 4));
    }

    private static long[] bits(int... idx) {
        long[] b = BitSet64.empty(130);
        for (int i : idx) BitSet64.set(b, i);
        return b;
    }
}
