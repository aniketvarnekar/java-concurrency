/**
 * Demonstrates ThreadLocal for per-thread request context and memory leak prevention.
 *
 * Run: javac ThreadLocalDemo.java && java ThreadLocalDemo
 */
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class ThreadLocalDemo {

    // ---------------------------------------------------------------
    // Part 1: Per-thread request context isolation
    // ---------------------------------------------------------------
    static final ThreadLocal<String> CURRENT_USER =
            ThreadLocal.withInitial(() -> null);

    static void handleRequest(int threadNum, int requestNum) throws InterruptedException {
        String user = "user-" + threadNum + "-request-" + requestNum;
        CURRENT_USER.set(user);
        try {
            System.out.printf("[%s] handling request for user: %s%n",
                    Thread.currentThread().getName(), CURRENT_USER.get());
            Thread.sleep(30); // simulate processing
            // Any nested call can retrieve the context without parameter passing
            processInner();
        } finally {
            // Must remove in finally — even if an exception is thrown
            CURRENT_USER.remove();
        }
    }

    static void processInner() {
        // No user parameter needed — context is thread-local
        System.out.printf("[%s]   inner processing, user is still: %s%n",
                Thread.currentThread().getName(), CURRENT_USER.get());
    }

    // ---------------------------------------------------------------
    // Part 2: Memory leak demonstration
    // ---------------------------------------------------------------
    static final ThreadLocal<byte[]> LARGE_CONTEXT =
            ThreadLocal.withInitial(() -> null);

    /**
     * Simulates a request handler that sets a large ThreadLocal value
     * but forgets to call remove(). The value persists on the thread
     * after the "request" completes.
     */
    static void requestWithLeak(int requestNum) {
        LARGE_CONTEXT.set(new byte[512 * 1024]); // 512 KB
        System.out.printf("[%s] Request %d: set 512KB context value (no remove)%n",
                Thread.currentThread().getName(), requestNum);
        // ... do work ...
        // BUG: no remove() call — value stays in thread's ThreadLocalMap
    }

    /**
     * Shows the stale value visible on the second request.
     */
    static void showStaleLeak() {
        requestWithLeak(1);
        // Simulate thread returning to pool and picking up request 2
        // Request 2 does NOT call set() first — it gets the stale value
        byte[] stale = LARGE_CONTEXT.get();
        System.out.printf("[%s] Request 2 (no set called): LARGE_CONTEXT is %s%n",
                Thread.currentThread().getName(),
                stale != null ? "NON-NULL (stale leak! " + stale.length / 1024 + " KB)" : "null");
        // Clean up for demo purposes
        LARGE_CONTEXT.remove();
    }

    /**
     * Fixed version: always removes in finally block.
     */
    static void requestFixed(int requestNum) {
        try {
            LARGE_CONTEXT.set(new byte[512 * 1024]); // 512 KB
            System.out.printf("[%s] Request %d: set 512KB context value (with remove in finally)%n",
                    Thread.currentThread().getName(), requestNum);
            // ... do work ...
        } finally {
            LARGE_CONTEXT.remove(); // value is now eligible for GC
        }
    }

    static void showFixed() {
        requestFixed(1);
        // Thread returns to pool and picks up request 2
        byte[] value = LARGE_CONTEXT.get();
        System.out.printf("[%s] Request 2 (after fixed request 1): LARGE_CONTEXT is %s%n",
                Thread.currentThread().getName(),
                value == null ? "null (clean)" : "non-null (unexpected)");
    }

    // ---------------------------------------------------------------
    // Main
    // ---------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Part 1: Per-thread context isolation ===\n");

        int numThreads = 3;
        int requestsPerThread = 2;
        CountDownLatch done = new CountDownLatch(numThreads);
        List<Thread> handlers = new ArrayList<>();

        for (int t = 1; t <= numThreads; t++) {
            final int threadNum = t;
            Thread thread = new Thread(() -> {
                try {
                    for (int r = 1; r <= requestsPerThread; r++) {
                        handleRequest(threadNum, r);
                        // After handleRequest returns, CURRENT_USER is removed
                        // Verify isolation: get() returns null between requests
                        System.out.printf("[%s] Between requests: CURRENT_USER = %s%n",
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

        System.out.println("\n=== Part 2: Memory leak without remove() ===\n");

        Thread leakThread = new Thread(ThreadLocalDemo::showStaleLeak, "request-handler-leak");
        leakThread.start();
        leakThread.join();

        System.out.println("\n=== Part 2: Fixed version with remove() in finally ===\n");

        Thread fixedThread = new Thread(ThreadLocalDemo::showFixed, "request-handler-fixed");
        fixedThread.start();
        fixedThread.join();

        System.out.println("\nDone.");
    }
}
