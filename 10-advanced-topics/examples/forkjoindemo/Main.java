/*
 * ForkJoinDemo — Main
 *
 * Benchmarks three approaches to summing a 10-million-element long array:
 *   1. Sequential loop (baseline)
 *   2. ForkJoinPool with explicit parallelism=4
 *   3. ForkJoinPool.commonPool() (parallelism = available processors - 1)
 *
 * Each result is verified against the closed-form Gauss sum to confirm
 * that no partial results were lost or duplicated by the parallel split.
 *
 * SumTask uses the fork-one-compute-one pattern: fork the left half,
 * compute the right half inline, then join the left result.
 */
package examples.forkjoindemo;

import java.util.concurrent.ForkJoinPool;

public class Main {

    static final int    ARRAY_SIZE = 10_000_000;
    static final int    THRESHOLD  = 10_000;
    static final long[] DATA       = new long[ARRAY_SIZE];

    static {
        for (int i = 0; i < ARRAY_SIZE; i++) DATA[i] = i + 1L; // 1 .. 10_000_000
    }

    // n*(n+1)/2 — the correct answer every run must produce
    static final long EXPECTED = (long) ARRAY_SIZE * (ARRAY_SIZE + 1) / 2;

    public static void main(String[] args) throws Exception {
        // Sequential baseline
        long seqStart = System.currentTimeMillis();
        long seqSum = 0;
        for (long v : DATA) seqSum += v;
        long seqTime = System.currentTimeMillis() - seqStart;

        System.out.println("Sequential sum : " + seqSum + "  (correct: " + (seqSum == EXPECTED) + ")");
        System.out.println("Sequential time: " + seqTime + " ms");
        System.out.println();

        // Parallel — custom ForkJoinPool with parallelism=4
        ForkJoinPool pool = new ForkJoinPool(4);
        long parStart = System.currentTimeMillis();
        long parSum   = pool.invoke(new SumTask(0, ARRAY_SIZE));
        long parTime  = System.currentTimeMillis() - parStart;
        pool.shutdown(); // custom pool must be shut down explicitly

        System.out.println("Parallel sum   : " + parSum + "  (correct: " + (parSum == EXPECTED) + ")");
        System.out.println("Parallel time  : " + parTime + " ms  (parallelism=4)");
        System.out.println();

        // Parallel — commonPool (parallelism = availableProcessors - 1)
        ForkJoinPool common = ForkJoinPool.commonPool();
        long comStart = System.currentTimeMillis();
        long comSum   = common.invoke(new SumTask(0, ARRAY_SIZE));
        long comTime  = System.currentTimeMillis() - comStart;

        System.out.println("CommonPool sum : " + comSum + "  (correct: " + (comSum == EXPECTED) + ")");
        System.out.println("CommonPool time: " + comTime + " ms  (parallelism=" + common.getParallelism() + ")");
    }
}
