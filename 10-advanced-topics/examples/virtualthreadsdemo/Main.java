/*
 * VirtualThreadsDemo — Main
 *
 * Demonstrates three virtual thread properties:
 *
 * Demo 1 — Scale: 10,000 virtual threads each sleeping 100ms complete in ~100ms
 *   total wall time, not 10,000 × 100ms. The JVM mounts and unmounts virtual
 *   threads onto a small pool of carrier (platform) threads.
 *
 * Demo 2 — Executor: the same workload via newVirtualThreadPerTaskExecutor(),
 *   which creates one virtual thread per submitted task.
 *
 * Demo 3 — Pinning: a virtual thread inside a synchronized block cannot unmount
 *   from its carrier during Thread.sleep(), pinning the carrier for the sleep
 *   duration. ReentrantLock allows the virtual thread to unmount and frees the
 *   carrier for other virtual threads, reducing total elapsed time significantly.
 *   Run with -Djdk.tracePinnedThreads=short to observe pinning events.
 *
 * Requires Java 21+.
 */
package examples.virtualthreadsdemo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class Main {

    static final int THREAD_COUNT = 10_000;
    static final int SLEEP_MS     = 100;

    public static void main(String[] args) throws InterruptedException {
        demo1_scaleWithThreadOfVirtual();
        demo2_virtualThreadPerTaskExecutor();
        demo3_pinningVsSafeLock();
    }

    // -------------------------------------------------------------------------
    // Demo 1: 10,000 virtual threads each sleeping SLEEP_MS ms.
    // Wall-clock time should be ~SLEEP_MS ms, not THREAD_COUNT * SLEEP_MS.
    // -------------------------------------------------------------------------
    static void demo1_scaleWithThreadOfVirtual() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger  done  = new AtomicInteger();

        long start = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int id = i;
            Thread.ofVirtual()
                  .name("vt-", id)
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

        System.out.println("demo1 — Thread.ofVirtual():");
        System.out.println("  completed: " + done.get() + " virtual threads");
        System.out.println("  elapsed  : " + elapsed + " ms  (expected ~" + SLEEP_MS + " ms)");
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Demo 2: Same workload via newVirtualThreadPerTaskExecutor().
    // -------------------------------------------------------------------------
    static void demo2_virtualThreadPerTaskExecutor() throws InterruptedException {
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
        // try-with-resources calls shutdown(); all tasks are submitted before we await

        latch.await();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("demo2 — newVirtualThreadPerTaskExecutor():");
        System.out.println("  completed: " + done.get() + " tasks");
        System.out.println("  elapsed  : " + elapsed + " ms  (expected ~" + SLEEP_MS + " ms)");
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Demo 3: Pinning vs ReentrantLock.
    //
    // A virtual thread inside synchronized that calls Thread.sleep() is PINNED:
    // it cannot unmount from its carrier. With few carriers, this limits
    // concurrency to at most carrierCount threads simultaneously.
    //
    // ReentrantLock allows the virtual thread to unmount during sleep,
    // freeing the carrier to run other virtual threads.
    // -------------------------------------------------------------------------
    static void demo3_pinningVsSafeLock() throws InterruptedException {
        final int SMALL_COUNT = 200;

        // Pinned version: synchronized + sleep keeps the carrier busy
        Object monitor = new Object();
        CountDownLatch pinnedLatch = new CountDownLatch(SMALL_COUNT);
        long pinnedStart = System.currentTimeMillis();

        for (int i = 0; i < SMALL_COUNT; i++) {
            Thread.ofVirtual().name("pinned-", i).start(() -> {
                synchronized (monitor) {
                    try {
                        // Carrier is pinned for the full 50ms sleep duration
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

        // Safe version: ReentrantLock allows unmounting during sleep
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch safeLatch = new CountDownLatch(SMALL_COUNT);
        long safeStart = System.currentTimeMillis();

        for (int i = 0; i < SMALL_COUNT; i++) {
            Thread.ofVirtual().name("safe-", i).start(() -> {
                lock.lock();
                try {
                    // Virtual thread unmounts here; carrier is freed for others
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

        System.out.println("demo3 — pinning (" + SMALL_COUNT + " virtual threads, 50ms sleep each):");
        System.out.println("  synchronized (pinned)  : " + pinnedElapsed + " ms");
        System.out.println("  ReentrantLock (safe)   : " + safeElapsed + " ms");
        System.out.println();
    }
}
