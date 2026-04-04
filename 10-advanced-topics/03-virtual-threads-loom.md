# Virtual Threads (Project Loom)

## Overview

Virtual threads (Java 21, Project Loom) are lightweight threads managed entirely by the JVM rather than the operating system. Unlike platform threads, which map one-to-one to OS threads and carry a fixed ~1 MB OS stack, virtual threads are M:N: many virtual threads are multiplexed onto a small pool of OS platform threads called carrier threads. Virtual threads are cheap enough to create one per task — even millions of them — because they consume only a few hundred bytes of heap initially and their stacks grow dynamically on the heap rather than being pre-allocated in the OS.

The key mechanism is mount and unmount. When a virtual thread executes, it is mounted onto a carrier thread from a `ForkJoinPool`. When the virtual thread blocks — on socket I/O, `Thread.sleep()`, or a lock that uses `LockSupport.park()` — the JVM saves its stack frame to the heap, unmounts it from the carrier thread, and allows the carrier to immediately execute a different virtual thread. When the blocking operation completes, the virtual thread is rescheduled onto any available carrier. From the program's perspective, the code is written in a straightforward blocking style; from the OS's perspective, the carrier thread never blocks.

The primary use case for virtual threads is high-concurrency IO-bound workloads: web servers, RPC frameworks, and database-facing services that handle many simultaneous requests. A server that previously needed a thread pool of 200 threads to handle 200 concurrent requests can now handle 200,000 concurrent requests with virtual threads, each waiting on IO while its carrier thread serves others. Virtual threads do not improve CPU-bound throughput — a CPU-bound task running on a virtual thread offers no advantage over the same task running on a platform thread, because the CPU is the bottleneck, not the thread count.

## Key Concepts

### Virtual Thread Lifecycle

A virtual thread progresses through the following states: created, scheduled (runnable), mounted (executing on a carrier), unmounted (blocked, stack saved to heap), and terminated. The JVM's scheduler manages transitions between scheduled and mounted/unmounted. The carrier thread pool is a `ForkJoinPool` whose size defaults to the number of available CPU cores, configured by the system property `jdk.virtualThreadScheduler.parallelism`.

```
Virtual thread states:
  NEW → RUNNABLE → RUNNING (mounted on carrier)
                       ↓ blocks (IO, sleep, park)
                   PARKED (unmounted, stack on heap)
                       ↓ IO completes / unpark()
                   RUNNABLE → RUNNING (mounted on any carrier)
                       ↓ compute() returns
                   TERMINATED
```

### Carrier Threads

Carrier threads are ordinary platform threads from a `ForkJoinPool`. They are managed entirely by the JVM; application code cannot obtain a reference to a carrier thread from a virtual thread. `Thread.currentThread()` called from within a virtual thread returns the virtual thread object, not the carrier. The number of carriers equals the number of logical CPUs by default and is not intended to be configured manually.

### Pinning

A virtual thread is **pinned** to its carrier and cannot unmount when it is inside a `synchronized` block or method, or when it is executing native code (JNI). A pinned virtual thread that blocks — for example, by performing IO or calling `Thread.sleep()` inside a `synchronized` block — ties up its carrier for the full duration of the block. If all carrier threads are pinned simultaneously, the application loses the benefit of virtual threads entirely and behaves like a fixed thread pool under heavy load.

Pinning can be detected at runtime by setting the system property `-Djdk.tracePinnedThreads=short` (prints a summary) or `-Djdk.tracePinnedThreads=full` (prints the full stack trace). JFR records pinning events as `jdk.VirtualThreadPinned`.

### `synchronized` vs `ReentrantLock`

`ReentrantLock.lock()` internally uses `LockSupport.park()` when the lock is contended. `park()` causes the virtual thread to unmount, freeing the carrier. A `synchronized` monitor wait pins the virtual thread because the JVM's monitor implementation relies on OS primitives that are tied to the OS thread (the carrier). For virtual-thread-friendly code, replace `synchronized` blocks that may block during IO with `ReentrantLock`.

| Blocking operation | Virtual thread behavior |
|---|---|
| Socket read/write (NIO) | Unmounts, carrier freed |
| `Thread.sleep()` | Unmounts, carrier freed |
| `LockSupport.park()` | Unmounts, carrier freed |
| `ReentrantLock.lock()` (contended) | Unmounts, carrier freed |
| `synchronized` block (contended) | Pinned, carrier occupied |
| JNI call | Pinned, carrier occupied |

### Platform Thread vs Virtual Thread Comparison

| Property | Platform Thread | Virtual Thread |
|---|---|---|
| Managed by | OS | JVM |
| Stack | OS stack (~1 MB) | Heap-allocated (dynamic) |
| Creation cost | ~1 ms | ~1 μs |
| Max practical count | ~10,000 | Millions |
| Blocks on `synchronized` | Ties up OS thread | Pins carrier thread |
| Blocks on `ReentrantLock` | Ties up OS thread | Unmounts (carrier freed) |
| CPU-bound suitability | Good | No benefit |
| IO-bound suitability | Poor at scale | Excellent |

### When NOT to Use Virtual Threads

Virtual threads are not universally superior to platform threads. CPU-bound tasks (cryptography, compression, numerical computation) are bottlenecked by the CPU; adding more virtual threads does not add more CPU, so there is no benefit. `ThreadLocal` patterns that initialize per-thread caches or connection pools break at scale: millions of virtual threads mean millions of distinct `ThreadLocal` initializations, exhausting heap. Code with `synchronized` blocks around IO operations must be refactored to `ReentrantLock` or the pinning will waste carriers. Code that relies on thread identity — for example, mapping a thread to a CPU core or using thread priority for scheduling — does not apply to virtual threads.

### Creating Virtual Threads

```java
// Thread.Builder API (Java 21)
Thread vt = Thread.ofVirtual().name("my-vt").start(() -> System.out.println("hello"));

// Unstarted virtual thread
Thread vt2 = Thread.ofVirtual().name("worker").unstarted(task);
vt2.start();

// Executor that creates one virtual thread per task
ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
exec.submit(() -> doWork());
exec.shutdown();
```

### Observability

Virtual threads appear in thread dumps (`jstack`, `jcmd <pid> Thread.dump_to_file`) with a `virtual` label. The JFR event `jdk.VirtualThreadPinned` records pinning occurrences. The JFR event `jdk.VirtualThreadSubmitFailed` records failures to schedule a virtual thread (rare). Thread dumps for millions of virtual threads that are blocked on IO show all their stack frames, which is useful for diagnosing deadlocks and stalls.

## Code Snippet

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demonstrates virtual threads: scale, executor usage, and pinning vs ReentrantLock.
 *
 * Run: javac VirtualThreadsDemo.java && java VirtualThreadsDemo
 *
 * Note: Requires Java 21+.
 */
public class VirtualThreadsDemo {

    // ---------------------------------------------------------------
    // Part 1: 10,000 virtual threads each sleeping 100ms
    // Total elapsed should be ~100ms, not 100ms * 10,000
    // ---------------------------------------------------------------
    static void part1ScaleDemo() throws InterruptedException {
        System.out.println("=== Part 1: 10,000 virtual threads sleeping 100ms ===");
        int count = 10_000;
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger completed = new AtomicInteger();

        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            Thread.ofVirtual()
                    .name("vt-", idx)
                    .start(() -> {
                        try {
                            Thread.sleep(100);
                            completed.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("  %,d virtual threads completed in %d ms (not %,d ms)%n%n",
                completed.get(), elapsed, (long) count * 100);
    }

    // ---------------------------------------------------------------
    // Part 2: Virtual thread per task executor, 10,000 tasks
    // ---------------------------------------------------------------
    static void part2ExecutorDemo() throws Exception {
        System.out.println("=== Part 2: newVirtualThreadPerTaskExecutor(), 10,000 tasks ===");
        int count = 10_000;
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger completed = new AtomicInteger();

        long start = System.currentTimeMillis();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                exec.submit(() -> {
                    try {
                        Thread.sleep(50);
                        completed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                    return null;
                });
            }
            latch.await();
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("  %,d tasks via VirtualThreadPerTaskExecutor completed in %d ms%n%n",
                completed.get(), elapsed);
    }

    // ---------------------------------------------------------------
    // Part 3: Pinning demo — synchronized vs ReentrantLock
    //
    // The synchronized version pins the carrier during sleep.
    // Add -Djdk.tracePinnedThreads=short to the run command to observe pinning.
    // The ReentrantLock version allows unmounting.
    // ---------------------------------------------------------------
    static final Object MONITOR = new Object();
    static final ReentrantLock LOCK = new ReentrantLock();

    static void part3PinningDemo() throws Exception {
        System.out.println("=== Part 3: Pinning — synchronized vs ReentrantLock ===");

        // --- synchronized version (causes pinning) ---
        int syncCount = 20;
        CountDownLatch syncLatch = new CountDownLatch(syncCount);
        long syncStart = System.currentTimeMillis();
        for (int i = 0; i < syncCount; i++) {
            Thread.ofVirtual().name("vt-sync-", i).start(() -> {
                try {
                    // synchronized block + sleep pins the carrier thread
                    // (Add -Djdk.tracePinnedThreads=short to see the pinning warning)
                    synchronized (MONITOR) {
                        Thread.sleep(50); // pins carrier during this sleep
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    syncLatch.countDown();
                }
            });
        }
        syncLatch.await();
        long syncTime = System.currentTimeMillis() - syncStart;
        System.out.printf("  synchronized + sleep (%d threads): %d ms  " +
                "[carrier pinned during sleep — run with -Djdk.tracePinnedThreads=short to observe]%n",
                syncCount, syncTime);

        // --- ReentrantLock version (no pinning — virtual thread unmounts on lock contention) ---
        int lockCount = 20;
        CountDownLatch lockLatch = new CountDownLatch(lockCount);
        long lockStart = System.currentTimeMillis();
        for (int i = 0; i < lockCount; i++) {
            Thread.ofVirtual().name("vt-lock-", i).start(() -> {
                try {
                    LOCK.lock();
                    try {
                        Thread.sleep(50); // virtual thread unmounts during sleep; carrier freed
                    } finally {
                        LOCK.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lockLatch.countDown();
                }
            });
        }
        lockLatch.await();
        long lockTime = System.currentTimeMillis() - lockStart;
        System.out.printf("  ReentrantLock + sleep (%d threads): %d ms  [no pinning]%n%n",
                lockCount, lockTime);
    }

    public static void main(String[] args) throws Exception {
        part1ScaleDemo();
        part2ExecutorDemo();
        part3PinningDemo();
        System.out.println("Done.");
    }
}
```

## Gotchas

**`synchronized` blocks that perform IO or `Thread.sleep()` pin the virtual thread's carrier.** The carrier thread is occupied for the full duration of the block and cannot serve other virtual threads. For high-concurrency virtual-thread code, every `synchronized` block that may block (including those in library code) must be identified and replaced with `ReentrantLock`. Use `-Djdk.tracePinnedThreads=short` during testing to surface all pinning occurrences.

**`ThreadLocal` patterns that initialize per-thread resources fail at scale with virtual threads.** A `ThreadLocal<DatabaseConnection>` that opens one connection per thread works with a fixed thread pool of 200 threads but opens millions of connections with millions of virtual threads. Connection pools, caches, or any per-thread resource that is expensive to initialize must not be placed in a `ThreadLocal` in virtual thread code. Use scoped values (Java 21 `ScopedValue`) or explicit parameter passing instead.

**Virtual threads do not improve CPU-bound throughput.** A computation that uses 100% of the CPU on one thread will not go faster on a virtual thread. Virtual threads allow more concurrency (more tasks in flight simultaneously) but not more parallelism (more work done per unit time) for CPU-bound tasks. For CPU-bound parallelism, use `ForkJoinPool` or parallel streams.

**Exhausting all carriers with pinned virtual threads degrades to fixed thread pool behavior.** If more virtual threads are simultaneously pinned inside `synchronized` blocks than there are carrier threads, new virtual threads cannot be scheduled. The application's throughput drops to at most `carrierCount` concurrent executions — the same ceiling as a platform thread pool of the same size. The advantage of virtual threads is lost entirely in this scenario.

**Virtual threads cannot be assigned scheduling priority or CPU affinity.** Unlike platform threads, virtual threads are not visible to the OS scheduler and cannot be given `Thread.NORM_PRIORITY` or higher priorities that affect OS scheduling. Code that relies on thread priority for timing guarantees or on CPU affinity for cache locality cannot use virtual threads for those properties.

**Framework and library code using `synchronized` internally causes invisible pinning.** Even if application code uses only `ReentrantLock`, libraries such as JDBC drivers, Hibernate's session management, and older versions of Jackson may use `synchronized` internally. When a virtual thread enters such code and blocks, it is pinned. Monitor with `-Djdk.tracePinnedThreads=full` to identify pinning originating in library code, and check whether newer versions of those libraries have migrated to `ReentrantLock` or `java.util.concurrent` primitives.
