/**
 * Demonstrates RecursiveTask for parallel array sum using ForkJoinPool.
 *
 * Shows:
 *   - RecursiveTask<Long> with fork/join pattern (fork left, compute right, join left)
 *   - Threshold-based recursion stopping
 *   - Comparison of parallel vs sequential sum
 *   - Custom ForkJoinPool with explicit parallelism
 *
 * Run: javac ForkJoinDemo.java && java ForkJoinDemo
 */
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class ForkJoinDemo {

    static final int  ARRAY_SIZE = 10_000_000;
    static final int  THRESHOLD  = 10_000;
    static final long[] DATA     = new long[ARRAY_SIZE];

    static {
        for (int i = 0; i < ARRAY_SIZE; i++) {
            DATA[i] = i + 1L;   // 1 .. 10_000_000
        }
    }

    // Expected sum: n*(n+1)/2 = 10_000_000 * 10_000_001 / 2
    static final long EXPECTED = (long) ARRAY_SIZE * (ARRAY_SIZE + 1) / 2;

    // -----------------------------------------------------------------
    // RecursiveTask: parallel sum of DATA[lo .. hi)
    // -----------------------------------------------------------------
    static class SumTask extends RecursiveTask<Long> {
        private final int lo;
        private final int hi;

        SumTask(int lo, int hi) {
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        protected Long compute() {
            if (hi - lo <= THRESHOLD) {
                // Base case: sum sequentially
                long sum = 0;
                for (int i = lo; i < hi; i++) {
                    sum += DATA[i];
                }
                return sum;
            }

            // Recursive case: fork left, compute right, join left
            int mid = lo + (hi - lo) / 2;
            SumTask left  = new SumTask(lo, mid);
            SumTask right = new SumTask(mid, hi);

            left.fork();                    // submit left to the pool
            long rightResult = right.compute(); // compute right in this thread
            long leftResult  = left.join();     // wait for left result

            return leftResult + rightResult;
        }
    }

    public static void main(String[] args) throws Exception {

        // --- Sequential baseline ---
        long seqStart = System.currentTimeMillis();
        long seqSum = 0;
        for (long v : DATA) seqSum += v;
        long seqTime = System.currentTimeMillis() - seqStart;

        System.out.println("Sequential sum : " + seqSum + " (correct: " + (seqSum == EXPECTED) + ")");
        System.out.println("Sequential time: " + seqTime + " ms");
        System.out.println();

        // --- Parallel with custom ForkJoinPool (parallelism = 4) ---
        ForkJoinPool pool = new ForkJoinPool(4);
        long parStart = System.currentTimeMillis();
        long parSum   = pool.invoke(new SumTask(0, ARRAY_SIZE));
        long parTime  = System.currentTimeMillis() - parStart;
        pool.shutdown();

        System.out.println("Parallel sum   : " + parSum + " (correct: " + (parSum == EXPECTED) + ")");
        System.out.println("Parallel time  : " + parTime + " ms");
        System.out.println("Parallelism    : " + 4 + " workers");
        System.out.println("Threshold      : " + THRESHOLD + " elements per task");
        System.out.println();

        // --- Parallel with commonPool ---
        ForkJoinPool common = ForkJoinPool.commonPool();
        long comStart = System.currentTimeMillis();
        long comSum   = common.invoke(new SumTask(0, ARRAY_SIZE));
        long comTime  = System.currentTimeMillis() - comStart;

        System.out.println("CommonPool sum : " + comSum  + " (correct: " + (comSum == EXPECTED) + ")");
        System.out.println("CommonPool time: " + comTime + " ms");
        System.out.println("CommonPool par : " + common.getParallelism() + " workers");
    }
}
