package com.example.causal.runtime.index;

import java.util.stream.IntStream;

/**
 * Static helpers over plain {@code long[]} bitsets: bit {@code i} lives in word
 * {@code i >>> 6} at position {@code i & 63}. No object is allocated per bitset.
 * Binary operations require both arrays to have the same length.
 */
public final class BitSet64 {

    private BitSet64() {}

    public static int words(int n) { return (n + 63) >>> 6; }

    public static long[] empty(int n) { return new long[words(n)]; }

    public static void set(long[] b, int i) { b[i >>> 6] |= 1L << i; }

    public static boolean get(long[] b, int i) { return (b[i >>> 6] & (1L << i)) != 0; }

    public static void or(long[] dst, long[] src) {
        for (int w = 0; w < dst.length; w++) dst[w] |= src[w];
    }

    public static void and(long[] dst, long[] src) {
        for (int w = 0; w < dst.length; w++) dst[w] &= src[w];
    }

    public static void andNot(long[] dst, long[] src) {
        for (int w = 0; w < dst.length; w++) dst[w] &= ~src[w];
    }

    public static boolean intersects(long[] a, long[] b) {
        for (int w = 0; w < a.length; w++) if ((a[w] & b[w]) != 0) return true;
        return false;
    }

    public static boolean isEmpty(long[] b) {
        for (long word : b) if (word != 0) return false;
        return true;
    }

    public static int cardinality(long[] b) {
        int c = 0;
        for (long word : b) c += Long.bitCount(word);
        return c;
    }

    public static long[] copy(long[] b) { return b.clone(); }

    /** Set bits in ascending order. */
    public static IntStream stream(long[] b) {
        IntStream.Builder out = IntStream.builder();
        for (int w = 0; w < b.length; w++) {
            long word = b[w];
            while (word != 0) {
                out.add((w << 6) + Long.numberOfTrailingZeros(word));
                word &= word - 1;
            }
        }
        return out.build();
    }

    /**
     * First {@code n} bits as '0'/'1', with bit 0 leftmost (so column {@code j}
     * of a closure matrix row is node {@code j}). Words are not separated.
     */
    public static String toBinaryString(long[] b, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(get(b, i) ? '1' : '0');
        return sb.toString();
    }
}
