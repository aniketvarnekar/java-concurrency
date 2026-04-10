/*
 * AtomicCounterDemo — Main
 *
 * Demonstrates three atomic primitive patterns under real concurrent contention:
 *   1. AtomicInteger as a shared counter: 8 threads each increment a single counter
 *      200,000 times. Every incrementAndGet() is an atomic CAS — no increment is lost.
 *   2. AtomicBoolean as a one-shot initialization guard: compareAndSet(false, true)
 *      is atomic, so exactly one thread among all concurrent callers transitions it.
 *   3. AtomicIntegerArray for per-index atomicity: each thread increments only its own
 *      slot, but reads from any index are visible to all threads via volatile semantics.
 */
package examples.atomiccounterdemo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class Main {

    static final int THREAD_COUNT          = 8;
    static final int INCREMENTS_PER_THREAD = 200_000;

    // All threads increment this single counter concurrently.
    static final AtomicInteger sharedCounter = new AtomicInteger(0);

    // compareAndSet(false, true) is atomic: exactly one thread transitions it,
    // so exactly one thread enters the initialization block.
    static final AtomicBoolean initialized = new AtomicBoolean(false);

    // Each thread writes only to its own index, but AtomicIntegerArray still provides
    // visibility across threads — any thread can read any index and see the latest value.
    static final AtomicIntegerArray perThreadCounter = new AtomicIntegerArray(THREAD_COUNT);

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(THREAD_COUNT);

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            new Thread(() -> {
                // compareAndSet is atomic: the read and conditional write happen as one
                // hardware instruction. Only the thread that flips false -> true proceeds.
                if (initialized.compareAndSet(false, true)) {
                    System.out.println(Thread.currentThread().getName()
                            + " performing one-time initialization");
                }

                // Hold all threads at the starting line so they all start together,
                // maximizing contention on the shared counter.
                try { startLatch.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                for (int i = 0; i < INCREMENTS_PER_THREAD; i++) {
                    sharedCounter.incrementAndGet();
                    perThreadCounter.incrementAndGet(threadId);
                }

                doneLatch.countDown();
            }, "worker-" + t).start();
        }

        startLatch.countDown();
        doneLatch.await();

        int expected     = THREAD_COUNT * INCREMENTS_PER_THREAD;
        int actual       = sharedCounter.get();
        int perThreadSum = 0;
        for (int t = 0; t < THREAD_COUNT; t++) {
            perThreadSum += perThreadCounter.get(t);
        }

        // Both checks must be true: AtomicInteger and AtomicIntegerArray both guarantee
        // that every increment is visible and no update is lost under concurrent access.
        System.out.println("sharedCounter correct: " + (actual == expected)
                + " (" + actual + " / " + expected + ")");
        System.out.println("perThreadSum correct:  " + (perThreadSum == actual)
                + " (" + perThreadSum + " / " + actual + ")");
    }
}
