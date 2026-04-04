/**
 * Demonstrates virtual threads: scale, carrier thread behavior, and pinning.
 *
 * Shows:
 *   - Creating 10,000 virtual threads that each sleep 100ms — completes in ~100ms total
 *   - Executors.newVirtualThreadPerTaskExecutor() for pool-like usage
 *   - Pinning: synchronized block holding while sleeping (pins carrier)
 *   - Fix: ReentrantLock allows virtual thread to unmount during sleep
 *
 * Run: javac VirtualThreadsDemo.java && java VirtualThreadsDemo
 *
 * Requires Java 21+.
 */
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class VirtualThreadsDemo {

    static final int THREAD_COUNT = 10_000;
    static final int SLEEP_MS     = 100;

    public static void main(String[] args) throws InterruptedException {
        demo1_scaleWithThreadOfVirtual();
        demo2_virtualThreadPerTaskExecutor();
        demo3_pinningVsSafeLock();
    }

    // ------------------------------------------------------------------
    // Demo 1: 10,000 virtual threads each sleeping SLEEP_MS ms.
    // Wall-clock time should be ~SLEEP_MS ms, not THREAD_COUNT * SLEEP_MS.
    // ------------------------------------------------------------------
    static void demo1_scaleWithThreadOfVirtual() throws InterruptedException {
        System.out.println("=== Demo 1: Scale with Thread.ofVirtual() ===");

        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger  done  = new AtomicInteger();

        long start = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int id = i;
            Thread.ofVirtual()
                  .name("vt-", id)   // names: vt-0, vt-1, ...
                  .start(() -> {
                      try {
                          Thread.sleep(SLEEP_MS);
                          done.incrementAndGet();
                      } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                      } finally {
                          latch.countDown();
                      }
                  });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("  Created  : " + THREAD_COUNT + " virtual threads");
        System.out.println("  Completed: " + done.get());
        System.out.println("  Elapsed  : " + elapsed + " ms  (expected ~" + SLEEP_MS + " ms)");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Demo 2: Same workload via newVirtualThreadPerTaskExecutor().
    // ------------------------------------------------------------------
    static void demo2_virtualThreadPerTaskExecutor() throws InterruptedException {
        System.out.println("=== Demo 2: Executors.newVirtualThreadPerTaskExecutor() ===");

        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger  done  = new AtomicInteger();

        long start = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        Thread.sleep(SLEEP_MS);
                        done.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                    return null;
                });
            }
        }
        // executor is shut down by try-with-resources; all tasks are submitted

        latch.await();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("  Completed: " + done.get() + " tasks");
        System.out.println("  Elapsed  : " + elapsed + " ms  (expected ~" + SLEEP_MS + " ms)");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Demo 3: Pinning vs ReentrantLock.
    //
    // A virtual thread inside `synchronized` that calls Thread.sleep()
    // is PINNED — it cannot unmount from its carrier thread.
    // With just a few carrier threads, this limits concurrency severely.
    //
    // The ReentrantLock version allows unmounting during the sleep,
    // freeing the carrier for other virtual threads.
    // ------------------------------------------------------------------
    static void demo3_pinningVsSafeLock() throws InterruptedException {
        System.out.println("=== Demo 3: Pinning (synchronized) vs Safe (ReentrantLock) ===");

        final int SMALL_COUNT = 200; // small count so pinning demo doesn't take too long

        // -- Pinned version (synchronized holds carrier during sleep) --
        Object monitor = new Object();
        CountDownLatch pinnedLatch = new CountDownLatch(SMALL_COUNT);
        long pinnedStart = System.currentTimeMillis();

        for (int i = 0; i < SMALL_COUNT; i++) {
            Thread.ofVirtual().name("pinned-", i).start(() -> {
                synchronized (monitor) {
                    try {
                        // Sleep inside synchronized: PINS the carrier thread.
                        // Add -Djdk.tracePinnedThreads=short to see pinning events.
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        pinnedLatch.countDown();
                    }
                }
            });
        }
        pinnedLatch.await();
        long pinnedElapsed = System.currentTimeMillis() - pinnedStart;
        System.out.println("  Pinned  (synchronized): " + pinnedElapsed + " ms for " + SMALL_COUNT + " threads");

        // -- Safe version (ReentrantLock allows unmount during sleep) --
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch safeLatch = new CountDownLatch(SMALL_COUNT);
        long safeStart = System.currentTimeMillis();

        for (int i = 0; i < SMALL_COUNT; i++) {
            Thread.ofVirtual().name("safe-", i).start(() -> {
                lock.lock();
                try {
                    // Sleep inside ReentrantLock: virtual thread UNMOUNTS here.
                    // Carrier is freed to run other virtual threads.
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                    safeLatch.countDown();
                }
            });
        }
        safeLatch.await();
        long safeElapsed = System.currentTimeMillis() - safeStart;
        System.out.println("  Safe    (ReentrantLock): " + safeElapsed + " ms for " + SMALL_COUNT + " threads");
        System.out.println();
        System.out.println("  Note: with synchronized, many virtual threads are serialized");
        System.out.println("  because pinning prevents carrier reuse. ReentrantLock is faster.");
    }
}
