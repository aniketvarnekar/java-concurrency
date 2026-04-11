/*
 * SemaphoreDemo — Main
 *
 * Simulates a connection pool guarded by a Semaphore with POOL_SIZE permits.
 * THREAD_COUNT threads compete for connections simultaneously. The Semaphore
 * enforces that at most POOL_SIZE threads hold a connection at any moment.
 *
 * Fair mode (true) is used so threads acquire permits in FIFO arrival order,
 * preventing any thread from being starved indefinitely under contention.
 *
 * After all threads finish, invariant checks confirm:
 *   - The permit count has been fully restored to POOL_SIZE.
 *   - The observed peak concurrency never exceeded POOL_SIZE.
 */
package examples.semaphoredemo;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    private static final int POOL_SIZE    = 3;
    private static final int THREAD_COUNT = 8;

    public static void main(String[] args) throws InterruptedException {
        // One permit per connection slot; fair=true for FIFO ordering
        Semaphore pool = new Semaphore(POOL_SIZE, true);

        AtomicInteger activeConnections = new AtomicInteger(0);
        AtomicInteger maxObservedActive = new AtomicInteger(0);

        Thread[] threads = new Thread[THREAD_COUNT];
        for (int i = 1; i <= THREAD_COUNT; i++) {
            threads[i - 1] = new Thread(
                    new ConnectionUser(pool, activeConnections, maxObservedActive),
                    "conn-worker-" + i);
        }

        // Start all threads near-simultaneously so they all compete for the pool
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.printf("%nFinal available permits: %d (should be %d)%n",
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
    }
}
