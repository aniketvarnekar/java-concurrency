/*
 * ConcurrentHashMapDemo — Main
 *
 * Demonstrates thread-safe aggregation using three atomic-per-key operations:
 *   1. merge(key, 1, Integer::sum): atomically inserts 1 if absent, or applies
 *      Integer::sum to accumulate. Equivalent to a thread-safe getOrDefault + put.
 *   2. compute(key, fn): atomically replaces the mapping for a key; the function
 *      receives the current value (null if absent) and returns the new value.
 *   3. putIfAbsent(key, value): atomically inserts only if the key is not yet present;
 *      shows which thread wins the race to populate each cache entry.
 *
 * THREAD_COUNT threads each process the full WORDS list once. The expected count for
 * each word is its frequency in WORDS multiplied by THREAD_COUNT. Verification confirms
 * that no updates were lost due to race conditions.
 */
package examples.concurrenthashmapdemo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class Main {

    private static final List<String> WORDS = Arrays.asList(
            "apple", "banana", "apple", "cherry", "banana",
            "apple", "date", "cherry", "banana", "date"
    );

    private static final int THREAD_COUNT = 4;

    @FunctionalInterface
    interface AggregationFn {
        void aggregate(ConcurrentHashMap<String, Integer> map, String word);
    }

    public static void main(String[] args) throws InterruptedException {
        runAggregation("merge",   Main::countWithMerge);
        runAggregation("compute", Main::countWithCompute);
        runCacheDemo();
    }

    // Spin up THREAD_COUNT threads, each applying fn to every word in WORDS.
    private static void runAggregation(String label, AggregationFn fn)
            throws InterruptedException {
        ConcurrentHashMap<String, Integer> freqMap = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 1; i <= THREAD_COUNT; i++) {
            final int id = i;
            new Thread(() -> {
                for (String word : WORDS) {
                    fn.aggregate(freqMap, word);
                }
                latch.countDown();
            }, "counter-" + id).start();
        }

        latch.await();
        printAndVerify(label, freqMap);
    }

    // merge is atomic per key: if absent inserts 1; otherwise applies Integer::sum.
    private static void countWithMerge(ConcurrentHashMap<String, Integer> map, String word) {
        map.merge(word, 1, Integer::sum);
    }

    // compute atomically replaces the mapping; receives current value (null if absent).
    private static void countWithCompute(ConcurrentHashMap<String, Integer> map, String word) {
        map.compute(word, (k, v) -> v == null ? 1 : v + 1);
    }

    // putIfAbsent: atomically inserts only if the key is absent.
    // Each print shows which thread won the race to populate that cache slot.
    private static void runCacheDemo() throws InterruptedException {
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 1; i <= THREAD_COUNT; i++) {
            final int id = i;
            new Thread(() -> {
                for (String word : WORDS) {
                    String prev = cache.putIfAbsent(word,
                            Thread.currentThread().getName() + "-computed");
                    if (prev == null) {
                        // This thread won the race — it performed the actual insertion.
                        System.out.printf("[%s] inserted cache entry for '%s'%n",
                                Thread.currentThread().getName(), word);
                    }
                }
                latch.countDown();
            }, "cache-worker-" + id).start();
        }

        latch.await();
        System.out.println("cache size: " + cache.size() + " (one entry per unique word)");
        cache.forEach((k, v) -> System.out.println("  " + k + " -> " + v));
    }

    // Verification: each word's count must be its frequency in WORDS × THREAD_COUNT.
    private static void printAndVerify(String label,
                                       ConcurrentHashMap<String, Integer> map) {
        Map<String, Long> expected = new java.util.HashMap<>();
        for (String w : WORDS) expected.merge(w, 1L, Long::sum);

        boolean allCorrect = true;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            long exp = expected.getOrDefault(entry.getKey(), 0L) * THREAD_COUNT;
            boolean ok = entry.getValue() == exp;
            if (!ok) allCorrect = false;
            System.out.printf("[%-7s] %-10s %3d  (expected %d) %s%n",
                    label, entry.getKey(), entry.getValue(), exp, ok ? "OK" : "MISMATCH");
        }
        System.out.println("[" + label + "] verification: " + (allCorrect ? "PASSED" : "FAILED"));
        System.out.println();
    }
}
