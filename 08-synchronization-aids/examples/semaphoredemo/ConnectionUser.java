/*
 * ConnectionUser — acquires a simulated database connection, holds it briefly,
 * then releases it.
 *
 * The Semaphore enforces that at most POOL_SIZE threads hold a connection at
 * once. release() is in a finally block so the permit is always returned even
 * if the code between acquire and release throws an unchecked exception — a
 * missing release would permanently shrink the pool's effective size.
 */
package examples.semaphoredemo;

import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

class ConnectionUser implements Runnable {

    private static final Random RNG = new Random();

    private final Semaphore pool;
    private final AtomicInteger activeConnections;
    private final AtomicInteger maxObservedActive;

    ConnectionUser(Semaphore pool,
                   AtomicInteger activeConnections,
                   AtomicInteger maxObservedActive) {
        this.pool              = pool;
        this.activeConnections = activeConnections;
        this.maxObservedActive = maxObservedActive;
    }

    @Override
    public void run() {
        String name = Thread.currentThread().getName();
        System.out.printf("[%s] waiting for connection...%n", name);

        try {
            pool.acquire();

            // Track peak concurrency to verify the semaphore limit held
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
            // IMPORTANT: release() is in finally so the permit is always returned
            // even if the work between acquire() and here throws.
            int active = activeConnections.decrementAndGet();
            pool.release();
            System.out.printf("[%s] released connection (available: %d, active: %d)%n",
                    name, pool.availablePermits(), active);
        }
    }
}
