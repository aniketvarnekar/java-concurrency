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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Main {

    // -------------------------------------------------------------------------
    // Part 1: Per-thread request context
    // -------------------------------------------------------------------------

    static final ThreadLocal<String> CURRENT_USER =
            ThreadLocal.withInitial(() -> null);

    static void handleRequest(int threadNum, int requestNum) throws InterruptedException {
        String user = "user-" + threadNum + "-request-" + requestNum;
        CURRENT_USER.set(user);
        try {
            System.out.printf("[%s] handling request for user: %s%n",
                    Thread.currentThread().getName(), CURRENT_USER.get());
            Thread.sleep(30);
            // Any nested call retrieves the correct user without a parameter
            processInner();
        } finally {
            // Mandatory: remove before the thread returns to the pool, otherwise
            // the next request on this thread will see the previous user's value.
            CURRENT_USER.remove();
        }
    }

    static void processInner() {
        // No user parameter needed — identity is thread-local
        System.out.printf("[%s]   inner processing, user is still: %s%n",
                Thread.currentThread().getName(), CURRENT_USER.get());
    }

    // -------------------------------------------------------------------------
    // Part 2: Memory leak demonstration
    // -------------------------------------------------------------------------

    static final ThreadLocal<byte[]> LARGE_CONTEXT =
            ThreadLocal.withInitial(() -> null);

    static void requestWithLeak(int requestNum) {
        LARGE_CONTEXT.set(new byte[512 * 1024]); // 512 KB
        System.out.printf("[%s] Request %d: set 512KB context value (no remove)%n",
                Thread.currentThread().getName(), requestNum);
        // BUG: no remove() — value stays in the thread's ThreadLocalMap
    }

    static void showStaleLeak() {
        requestWithLeak(1);
        // Simulate the thread being returned to a pool and picking up request 2.
        // Request 2 does not call set() first — it receives the stale 512KB value.
        byte[] stale = LARGE_CONTEXT.get();
        System.out.printf("[%s] Request 2 (no set called): LARGE_CONTEXT is %s%n",
                Thread.currentThread().getName(),
                stale != null ? "NON-NULL (stale leak, " + stale.length / 1024 + "KB)" : "null");
        LARGE_CONTEXT.remove(); // clean up so demo is self-contained
    }

    static void requestFixed(int requestNum) {
        try {
            LARGE_CONTEXT.set(new byte[512 * 1024]); // 512 KB
            System.out.printf("[%s] Request %d: set 512KB context value (with remove in finally)%n",
                    Thread.currentThread().getName(), requestNum);
        } finally {
            LARGE_CONTEXT.remove(); // value is now eligible for GC
        }
    }

    static void showFixed() {
        requestFixed(1);
        // After the fixed version, the thread's slot is clean for the next request.
        byte[] value = LARGE_CONTEXT.get();
        System.out.printf("[%s] Request 2 (after fixed request 1): LARGE_CONTEXT is %s%n",
                Thread.currentThread().getName(),
                value == null ? "null (clean)" : "non-null (unexpected)");
    }

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        int numThreads       = 3;
        int requestsPerThread = 2;
        CountDownLatch done  = new CountDownLatch(numThreads);
        List<Thread> handlers = new ArrayList<>();

        for (int t = 1; t <= numThreads; t++) {
            final int threadNum = t;
            Thread thread = new Thread(() -> {
                try {
                    for (int r = 1; r <= requestsPerThread; r++) {
                        handleRequest(threadNum, r);
                        // Verify isolation: after remove(), get() returns null
                        System.out.printf("[%s] between requests: CURRENT_USER = %s%n",
                                Thread.currentThread().getName(), CURRENT_USER.get());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "request-handler-" + t);
            handlers.add(thread);
        }

        handlers.forEach(Thread::start);
        done.await();

        Thread leakThread = new Thread(Main::showStaleLeak, "leak-demo-thread");
        leakThread.start();
        leakThread.join();

        Thread fixedThread = new Thread(Main::showFixed, "fixed-demo-thread");
        fixedThread.start();
        fixedThread.join();
    }
}
