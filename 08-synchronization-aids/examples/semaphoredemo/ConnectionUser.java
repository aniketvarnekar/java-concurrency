/*
 * ConnectionUser — acquires a simulated database connection, holds it briefly,
 * then releases it.
 *
 * The Semaphore enforces that at most POOL_SIZE threads hold a connection at
 * once. release() is called in a finally block, but only when acquire()
 * succeeded — releasing a permit that was never acquired would inflate the
 * pool beyond its configured capacity.
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

        // Track whether acquire() succeeded. The finally block must not call
        // release() if acquire() never returned normally — doing so would add
        // a permit that was never taken, inflating the pool beyond its capacity.
        boolean acquired = false;
        try {
            pool.acquire();
            acquired = true;

            // Track peak concurrency to verify the semaphore limit held
            int active = activeConnections.incrementAndGet();
            maxObservedActive.accumulateAndGet(active, Math::max);

            System.out.printf("[%s] acquired connection (available: %d, active: %d)%n",
                    name, pool.availablePermits(), active);

            // Simulate database work: hold the connection 200–600ms
            Thread.sleep(200 + RNG.nextInt(400));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.printf("[%s] interrupted%n", name);
        } finally {
            // Only release if acquire() succeeded. If acquire() itself threw
            // InterruptedException, no permit was obtained and none should be returned.
            if (acquired) {
                int active = activeConnections.decrementAndGet();
                pool.release();
                System.out.printf("[%s] released connection (available: %d, active: %d)%n",
                        name, pool.availablePermits(), active);
            }
        }
    }
}
