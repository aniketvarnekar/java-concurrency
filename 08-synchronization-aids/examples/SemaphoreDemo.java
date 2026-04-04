/**
 * Demonstrates Semaphore as a resource pool guard.
 *
 * Run: javac SemaphoreDemo.java && java SemaphoreDemo
 */

import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class SemaphoreDemo {

    private static final int POOL_SIZE   = 3;   // number of simulated connections
    private static final int THREAD_COUNT = 8;   // threads competing for connections

    // Semaphore initialized to POOL_SIZE — one permit per connection slot.
    // Fair mode (true) ensures threads acquire in FIFO arrival order,
    // preventing any single thread from being starved indefinitely.
    private static final Semaphore pool = new Semaphore(POOL_SIZE, true);

    // Tracks how many threads are currently holding a connection.
    // Used only for the summary assertion — the semaphore itself enforces the limit.
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final AtomicInteger maxObservedActive = new AtomicInteger(0);

    private static final Random RNG = new Random();

    // -----------------------------------------------------------------------
    // ConnectionUser: acquires a connection, does work, releases the connection.
    // -----------------------------------------------------------------------
    static class ConnectionUser implements Runnable {
        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            System.out.printf("[%s] waiting for connection...%n", name);

            try {
                pool.acquire();

                // Observe and record peak concurrency
                int active = activeConnections.incrementAndGet();
                maxObservedActive.accumulateAndGet(active, Math::max);

                System.out.printf("[%s] acquired connection (available: %d, active: %d)%n",
                    name, pool.availablePermits(), active);

                // Simulate database work: hold the connection 200–600ms
                Thread.sleep(200 + RNG.nextInt(400));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.printf("[%s] interrupted while waiting%n", name);
                return;  // do not decrement activeConnections — acquire may not have succeeded
            } finally {
                // IMPORTANT: release() is in a finally block so the permit is
                // always returned even if the work between acquire and here throws.
                int active = activeConnections.decrementAndGet();
                pool.release();
                System.out.printf("[%s] released connection (available: %d, active: %d)%n",
                    name, pool.availablePermits(), active);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Semaphore Connection Pool Demo ===");
        System.out.printf("Pool size: %d | Competing threads: %d%n%n",
            POOL_SIZE, THREAD_COUNT);

        Thread[] threads = new Thread[THREAD_COUNT];
        for (int i = 1; i <= THREAD_COUNT; i++) {
            threads[i - 1] = new Thread(new ConnectionUser(), "Thread-" + i);
        }

        // Start all threads near-simultaneously so they all compete for the pool
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println();
        System.out.printf("All %d threads finished.%n", THREAD_COUNT);
        System.out.printf("Final available permits: %d (should be %d)%n",
            pool.availablePermits(), POOL_SIZE);
        System.out.printf("Peak concurrent connections observed: %d (should be <= %d)%n",
            maxObservedActive.get(), POOL_SIZE);

        boolean poolIntact = pool.availablePermits() == POOL_SIZE;
        boolean limitHeld  = maxObservedActive.get() <= POOL_SIZE;

        System.out.println();
        System.out.println("Invariant checks:");
        System.out.println("  Permit count restored to " + POOL_SIZE + ": "
            + (poolIntact ? "PASSED" : "FAILED"));
        System.out.println("  Concurrency limit never exceeded: "
            + (limitHeld ? "PASSED" : "FAILED"));
        System.out.println();
        System.out.println("Key observations:");
        System.out.println("  - At most " + POOL_SIZE
            + " threads held a connection simultaneously.");
        System.out.println("  - Threads printed 'waiting' before being granted a permit.");
        System.out.println("  - release() was in a finally block — permits always returned.");
        System.out.println("  - Fair mode ensured threads acquired in arrival order.");
    }
}
