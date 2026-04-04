/**
 * AtomicCounterDemo
 *
 * Demonstrates:
 *   - AtomicInteger as a shared thread-safe counter
 *   - AtomicBoolean as a one-shot initialization guard (exactly one thread initializes)
 *   - AtomicIntegerArray for per-index counters with element-level atomicity
 *
 * Run:
 *   javac AtomicCounterDemo.java && java AtomicCounterDemo
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class AtomicCounterDemo {

    static final int THREAD_COUNT           = 8;
    static final int INCREMENTS_PER_THREAD  = 200_000;

    // Shared counter — all threads increment this
    static final AtomicInteger sharedCounter = new AtomicInteger(0);

    // One-shot flag — exactly one thread across all concurrent callers will
    // execute the initialization block
    static final AtomicBoolean initialized = new AtomicBoolean(false);

    // Per-thread counters — index i is written exclusively by thread i,
    // but AtomicIntegerArray still guarantees visibility to all threads
    static final AtomicIntegerArray perThreadCounter =
            new AtomicIntegerArray(THREAD_COUNT);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== AtomicCounterDemo ===");
        System.out.printf("Threads: %d, Increments per thread: %,d%n",
                THREAD_COUNT, INCREMENTS_PER_THREAD);
        System.out.printf("Expected total: %,d%n%n",
                (long) THREAD_COUNT * INCREMENTS_PER_THREAD);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(THREAD_COUNT);

        Thread[] threads = new Thread[THREAD_COUNT];

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                // --- One-shot initialization guard ---
                // compareAndSet atomically changes false -> true.
                // Only the first thread to call this gets true back.
                if (initialized.compareAndSet(false, true)) {
                    System.out.println("[init] " + Thread.currentThread().getName()
                            + " is performing one-time initialization");
                    // Simulate initialization work
                    try { Thread.sleep(5); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("[init] initialization complete");
                }

                // Wait for all threads to be ready before starting the count
                try { startLatch.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // --- Shared counter increments ---
                for (int i = 0; i < INCREMENTS_PER_THREAD; i++) {
                    sharedCounter.incrementAndGet();
                    perThreadCounter.incrementAndGet(threadId);
                }

                doneLatch.countDown();
            }, "worker-" + t);
        }

        // Start all threads
        for (Thread t : threads) t.start();

        // Release all threads simultaneously for maximum contention
        startLatch.countDown();

        // Wait for all to finish
        doneLatch.await();

        // --- Verify results ---
        System.out.println();
        int expected = THREAD_COUNT * INCREMENTS_PER_THREAD;
        int actual   = sharedCounter.get();

        System.out.println("=== Results ===");
        System.out.printf("sharedCounter.get()  = %,d%n", actual);
        System.out.printf("Expected             = %,d%n", expected);
        System.out.println("Correct: " + (expected == actual));

        // Sum per-thread counters; must equal sharedCounter
        int perThreadSum = 0;
        System.out.println();
        System.out.println("Per-thread breakdown:");
        for (int t = 0; t < THREAD_COUNT; t++) {
            int count = perThreadCounter.get(t);
            perThreadSum += count;
            System.out.printf("  worker-%-2d => %,d%n", t, count);
        }
        System.out.println();
        System.out.printf("Sum of per-thread counters = %,d%n", perThreadSum);
        System.out.println("Matches sharedCounter: " + (perThreadSum == actual));

        // --- Demonstrate updateAndGet and getAndUpdate ---
        System.out.println();
        System.out.println("=== updateAndGet vs getAndUpdate ===");
        AtomicInteger demo = new AtomicInteger(10);
        int newVal = demo.updateAndGet(x -> x * 3);   // returns NEW value
        System.out.println("updateAndGet(x -> x * 3): new value = " + newVal
                + ", counter now = " + demo.get());

        demo.set(10);
        int oldVal = demo.getAndUpdate(x -> x * 3);   // returns OLD value
        System.out.println("getAndUpdate(x -> x * 3): old value = " + oldVal
                + ", counter now = " + demo.get());

        // --- Demonstrate accumulateAndGet ---
        System.out.println();
        System.out.println("=== accumulateAndGet ===");
        AtomicInteger max = new AtomicInteger(Integer.MIN_VALUE);
        int[] samples = {42, 17, 99, 55, 3};
        for (int sample : samples) {
            max.accumulateAndGet(sample, Math::max);
        }
        System.out.println("Max of {42, 17, 99, 55, 3} = " + max.get()
                + " (expected 99)");

        // --- Demonstrate compareAndSet for a conditional update ---
        System.out.println();
        System.out.println("=== compareAndSet ===");
        AtomicInteger cas = new AtomicInteger(0);
        boolean first  = cas.compareAndSet(0, 1);  // should succeed
        boolean second = cas.compareAndSet(0, 2);  // should fail (value is now 1)
        System.out.println("CAS(0->1): " + first  + ", value = " + cas.get());
        System.out.println("CAS(0->2): " + second + ", value = " + cas.get());
    }
}
