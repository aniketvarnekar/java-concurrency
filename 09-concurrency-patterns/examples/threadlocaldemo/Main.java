/*
 * ThreadLocalDemo — Main
 *
 * Demonstrates two ThreadLocal use cases:
 *
 * Part 1 — Per-thread request context isolation:
 *   Three handler threads each process two requests. CURRENT_USER is set per-request
 *   and removed in a finally block. The "Between requests" print confirms the value
 *   is null after removal — no cross-request contamination.
 *
 * Part 2 — Memory leak without remove():
 *   requestWithLeak sets a 512 KB value without calling remove(). The second check
 *   on the same thread shows the stale value still present, simulating the thread-pool
 *   leak scenario where a pooled thread carries one request's context into the next.
 *   requestFixed shows the correct pattern: always remove in a finally block.
 */
package examples.threadlocaldemo;

import java.util.concurrent.*;

public class Main {

    // -------------------------------------------------------------------------
    // Part 1: Per-thread request context (correct usage)
    // -------------------------------------------------------------------------

    static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    static void handleRequest(int requestNum) {
        String user = "user-" + requestNum;

        try {
            CURRENT_USER.set(user);

            System.out.printf("[%s] handling request for %s%n",
                    Thread.currentThread().getName(), CURRENT_USER.get());

            processInner();
        } finally {
            // CRITICAL: prevent leakage when using thread pools
            CURRENT_USER.remove();
        }
    }

    static void processInner() {
        System.out.printf("[%s]   inner processing user = %s%n",
                Thread.currentThread().getName(), CURRENT_USER.get());
    }

    // -------------------------------------------------------------------------
    // Part 2: ThreadLocal leak demonstration (REAL scenario)
    // -------------------------------------------------------------------------

    static final ThreadLocal<byte[]> LARGE_CONTEXT = new ThreadLocal<>();

    static void leakDemo() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);

        // Task 1: sets value but DOES NOT remove
        Runnable task1 = () -> {
            LARGE_CONTEXT.set(new byte[512 * 1024]); // 512 KB
            System.out.printf("[%s] Task1: set 512KB (no remove)%n",
                    Thread.currentThread().getName());
        };

        // Task 2: runs on SAME thread → sees stale value
        Runnable task2 = () -> {
            byte[] val = LARGE_CONTEXT.get();
            System.out.printf("[%s] Task2: LARGE_CONTEXT = %s%n",
                    Thread.currentThread().getName(),
                    val != null
                            ? "STALE VALUE (" + val.length / 1024 + " KB)"
                            : "null");

            // cleanup so demo doesn't pollute further runs
            LARGE_CONTEXT.remove();
        };

        pool.submit(task1).get();
        pool.submit(task2).get();

        pool.shutdown();
    }

    // -------------------------------------------------------------------------
    // Part 3: Fixed version (proper cleanup)
    // -------------------------------------------------------------------------

    static void fixedDemo() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);

        Runnable safeTask = () -> {
            try {
                LARGE_CONTEXT.set(new byte[512 * 1024]);
                System.out.printf("[%s] SafeTask: set 512KB%n",
                        Thread.currentThread().getName());
            } finally {
                LARGE_CONTEXT.remove(); // FIX
            }
        };

        Runnable verifyTask = () -> {
            byte[] val = LARGE_CONTEXT.get();
            System.out.printf("[%s] VerifyTask: LARGE_CONTEXT = %s%n",
                    Thread.currentThread().getName(),
                    val == null ? "null (clean)" : "unexpected value");
        };

        pool.submit(safeTask).get();
        pool.submit(verifyTask).get();

        pool.shutdown();
    }

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {

        System.out.println("=== Part 1: Correct ThreadLocal usage ===");

        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (int i = 1; i <= 4; i++) {
            final int requestNum = i;
            pool.submit(() -> handleRequest(requestNum));
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("\n=== Part 2: ThreadLocal leak demo ===");
        leakDemo();

        System.out.println("\n=== Part 3: Fixed (with remove) ===");
        fixedDemo();
    }
}