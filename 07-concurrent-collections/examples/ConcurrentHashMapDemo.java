/**
 * Demonstrates ConcurrentHashMap: compute, merge, computeIfAbsent for thread-safe aggregation.
 *
 * Run: javac ConcurrentHashMapDemo.java && java ConcurrentHashMapDemo
 */

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class ConcurrentHashMapDemo {

    // A fixed word list processed by every thread.
    // Each word should appear (4 * its occurrences in the list) times in the final map.
    private static final List<String> WORDS = Arrays.asList(
        "apple", "banana", "apple", "cherry", "banana",
        "apple", "date", "cherry", "banana", "date"
    );

    // Expected count multiplier: 4 threads each process the full list once.
    private static final int THREAD_COUNT = 4;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== ConcurrentHashMap Demo ===");
        System.out.println("Word list size: " + WORDS.size()
            + ", threads: " + THREAD_COUNT);
        System.out.println();

        // --- Part 1: merge(key, 1, Integer::sum) ---
        System.out.println("--- Part 1: merge(key, 1, Integer::sum) ---");
        runAggregation("merge", ConcurrentHashMapDemo::countWithMerge);

        // --- Part 2: compute(key, (k, v) -> ...) ---
        System.out.println("--- Part 2: compute ---");
        runAggregation("compute", ConcurrentHashMapDemo::countWithCompute);

        // --- Part 3: putIfAbsent cache-like pattern ---
        System.out.println("--- Part 3: putIfAbsent (cache pattern) ---");
        runCacheDemo();
    }

    // -----------------------------------------------------------------------
    // Aggregation runner: spins up THREAD_COUNT threads, each calling fn
    // -----------------------------------------------------------------------
    @FunctionalInterface
    interface AggregationFn {
        void aggregate(ConcurrentHashMap<String, Integer> map, String word);
    }

    private static void runAggregation(String label, AggregationFn fn)
            throws InterruptedException {

        ConcurrentHashMap<String, Integer> freqMap = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 1; i <= THREAD_COUNT; i++) {
            final int threadNum = i;
            Thread t = new Thread(() -> {
                String tname = Thread.currentThread().getName();
                System.out.println("[" + tname + "] starting word count");
                for (String word : WORDS) {
                    fn.aggregate(freqMap, word);
                }
                System.out.println("[" + tname + "] finished word count");
                latch.countDown();
            }, "counter-thread-" + threadNum);
            t.start();
        }

        latch.await();
        printAndVerify(label, freqMap);
    }

    // -----------------------------------------------------------------------
    // Strategy 1: merge
    // merge is atomic per key: if key absent, inserts value; otherwise applies
    // the remapping function. Equivalent to a thread-safe getOrDefault + put.
    // -----------------------------------------------------------------------
    private static void countWithMerge(ConcurrentHashMap<String, Integer> map,
                                       String word) {
        map.merge(word, 1, Integer::sum);
    }

    // -----------------------------------------------------------------------
    // Strategy 2: compute
    // compute atomically replaces the mapping for a key. The lambda receives
    // the current value (null if absent) and returns the new value.
    // -----------------------------------------------------------------------
    private static void countWithCompute(ConcurrentHashMap<String, Integer> map,
                                         String word) {
        map.compute(word, (k, v) -> v == null ? 1 : v + 1);
    }

    // -----------------------------------------------------------------------
    // Strategy 3: putIfAbsent — cache pattern
    // putIfAbsent atomically inserts only if the key is not yet present.
    // Useful for "compute once" caches. Note: computeIfAbsent is preferred
    // when the value is expensive to construct, because the factory is only
    // called when the key is truly absent.
    // -----------------------------------------------------------------------
    private static void runCacheDemo() throws InterruptedException {
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 1; i <= THREAD_COUNT; i++) {
            final int threadNum = i;
            Thread t = new Thread(() -> {
                String tname = Thread.currentThread().getName();
                for (String word : WORDS) {
                    // putIfAbsent: only the first thread to insert wins
                    String prev = cache.putIfAbsent(word, tname + "-computed");
                    if (prev == null) {
                        System.out.println("[" + tname + "] inserted cache entry for '"
                            + word + "'");
                    }
                }
                latch.countDown();
            }, "counter-thread-" + threadNum);
            t.start();
        }

        latch.await();
        System.out.println("Cache contents (" + cache.size() + " unique keys):");
        cache.forEach((k, v) ->
            System.out.println("  " + k + " -> " + v));
        System.out.println("Each key mapped to exactly one inserting thread.\n");
    }

    // -----------------------------------------------------------------------
    // Verification: each word's count must equal THREAD_COUNT * occurrences
    // -----------------------------------------------------------------------
    private static void printAndVerify(String label,
                                       ConcurrentHashMap<String, Integer> map) {
        // Compute expected counts from the word list
        Map<String, Long> expected = new java.util.HashMap<>();
        for (String w : WORDS) {
            expected.merge(w, 1L, Long::sum);
        }

        System.out.println("Final frequency map (" + label + "):");
        boolean allCorrect = true;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            long exp = expected.getOrDefault(entry.getKey(), 0L) * THREAD_COUNT;
            boolean ok = entry.getValue() == exp;
            if (!ok) allCorrect = false;
            System.out.printf("  %-10s -> %3d  (expected %d) %s%n",
                entry.getKey(), entry.getValue(), exp, ok ? "OK" : "MISMATCH");
        }
        System.out.println("Verification: " + (allCorrect ? "PASSED" : "FAILED"));
        System.out.println();
    }
}
