# Testing Concurrent Code

## Overview

Concurrent code is uniquely difficult to test because bugs are triggered by specific thread interleavings that occur probabilistically. A test can pass a million times and fail on the million-and-first run when the scheduler happens to switch threads at exactly the wrong moment. The core problem is that standard unit test frameworks execute test methods sequentially on a single thread, which means they never exercise the interactions between threads that cause concurrency bugs.

The situation is compounded by the fact that test environments differ from production environments in ways that matter for concurrency. A developer machine may have two cores while the production server has 64. A test run with no system load produces very different scheduling behavior than a server under heavy traffic. A bug that is impossible to reproduce locally may be triggered dozens of times per minute in production.

A further complication is the Heisenbug phenomenon: bugs that disappear or change character when you observe them. Adding a print statement or attaching a debugger introduces synchronization points that alter the thread interleaving, causing the bug to vanish. A breakpoint in a synchronized block, for example, holds a lock for seconds instead of microseconds, completely changing contention behavior. This makes traditional step-through debugging nearly useless for concurrency bugs.

The practical response to these challenges is a layered testing strategy. No single technique is sufficient. You combine fast single-threaded unit tests for functional correctness, structured multi-threaded tests to maximize overlap, long-running stress tests to exercise rare interleavings, and specialized tools like jcstress (covered in the next file) for systematic exploration of the Java Memory Model.

## Key Concepts

### Why It Is Hard

Thread scheduling is controlled by the operating system kernel, not the JVM. The JVM gives no guarantees about the order in which threads are scheduled or how long any thread runs before being preempted. Two threads incrementing a shared counter without synchronization may run correctly a billion times on a single-core machine (because context switches occur between bytecode instructions, and the increment may happen to be atomic in practice) but fail immediately on a machine where both threads run truly in parallel on separate cores.

The concept of a Heisenbug is worth understanding precisely. When you add `System.out.println` to a suspected race condition, the `println` method acquires an internal lock on the `PrintStream`. This lock introduces a happens-before relationship between the two threads that did not exist before. The lock contention also causes one thread to wait for the other, serializing what was previously unsynchronized parallel execution. The bug disappears — not because it was fixed, but because the observation changed the system.

### Strategy 1: Single-Threaded Unit Tests

Every thread-safe class should first be tested from a single thread to verify basic functional correctness. A `BlockingQueue` implementation should be tested for correct FIFO ordering, correct size reporting, and correct behavior when empty — all without any concurrency. These tests run fast, are deterministic, and catch the majority of logic errors. They do not catch concurrency bugs, but they ensure that concurrency bugs are not masked by functional bugs when you move to multi-threaded tests.

```java
// Single-threaded test: verifies basic functional behavior
// No concurrency bugs can be caught here, but functional bugs are found fast
ThreadSafeCounter counter = new ThreadSafeCounter();
counter.increment();
counter.increment();
counter.increment();
assert counter.get() == 3 : "expected 3, got " + counter.get();
```

### Strategy 2: Structured Concurrency Tests

A structured concurrency test uses a `CountDownLatch` as a start gate to hold all threads in a ready state and then release them simultaneously. This maximizes the overlap between threads and increases the probability of triggering race conditions. The pattern has three phases: a setup phase where all threads are created and blocked at the gate, a release phase where the gate is opened, and a verification phase where the test checks that the final state is correct.

```java
import java.util.concurrent.*;

// Pattern: start-gate latch synchronizes thread starts; end-gate latch waits for completion
int threadCount = 100;
int incrementsPerThread = 1000;
CountDownLatch startGate = new CountDownLatch(1);
CountDownLatch endGate = new CountDownLatch(threadCount);

ThreadSafeCounter counter = new ThreadSafeCounter();

for (int i = 0; i < threadCount; i++) {
    Thread t = new Thread(() -> {
        try {
            startGate.await();   // all threads wait here
            for (int j = 0; j < incrementsPerThread; j++) {
                counter.increment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            endGate.countDown();
        }
    }, "worker-" + i);
    t.start();
}

startGate.countDown();           // release all threads at once
endGate.await();                 // wait for all to finish
assert counter.get() == threadCount * incrementsPerThread;
```

### Strategy 3: Stress Tests

A stress test runs the operation under test for an extended period — seconds or minutes — rather than a fixed number of iterations. The goal is to accumulate enough executions that rare interleavings eventually occur. Effective stress tests use a high thread count (at least two per available CPU core), randomize delays between operations using `ThreadLocalRandom`, and are designed to run as part of a longer CI suite (not the fast unit test suite).

The key insight is that the probability of triggering a specific interleaving in any single operation is very low, but over billions of operations, rare interleavings become likely. A stress test should run long enough that the probability of missing a bug with a one-in-a-million interleaving drops below an acceptable threshold.

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// Stress test: run for N seconds, counting failures
AtomicInteger failures = new AtomicInteger(0);
AtomicBoolean running = new AtomicBoolean(true);
int threads = Runtime.getRuntime().availableProcessors() * 2;

ExecutorService pool = Executors.newFixedThreadPool(threads);
for (int i = 0; i < threads; i++) {
    pool.submit(() -> {
        while (running.get()) {
            // perform the operation under test
            // check invariants and record failures
        }
    });
}

Thread.sleep(10_000);     // run for 10 seconds
running.set(false);
pool.shutdown();
pool.awaitTermination(5, TimeUnit.SECONDS);
assert failures.get() == 0 : failures.get() + " invariant violations";
```

### Strategy 4: Property-Based and Model-Based Testing

Rather than asserting that a specific output equals a specific value, property-based testing asserts that invariants hold across all executions. For a thread-safe counter, the invariant is: after all threads complete, the counter value equals the total number of increments performed. For a concurrent queue, the invariant is: no element is lost and no element appears more than once.

Model-based testing takes this further: you maintain a sequential reference implementation alongside the concurrent implementation and verify that both produce identical results for any sequence of operations. The sequential model is trusted to be correct because it has no concurrency. The concurrent implementation is verified by comparing its state to the model after each operation sequence.

### Testing with Executors

Using `ExecutorService` for tests is more convenient than managing raw threads. You submit all tasks, call `shutdown()`, and use `awaitTermination()` to wait for completion. The `invokeAll()` method is particularly useful: it submits a list of `Callable` tasks, blocks until all complete, and returns a `List<Future<T>>` that you can inspect for exceptions.

```java
ExecutorService executor = Executors.newFixedThreadPool(16);
List<Callable<Void>> tasks = new ArrayList<>();

for (int i = 0; i < 1000; i++) {
    tasks.add(() -> {
        sharedResource.operation();
        return null;
    });
}

List<Future<Void>> results = executor.invokeAll(tasks);
executor.shutdown();

// Check that no task threw an exception
for (Future<Void> result : results) {
    result.get();  // rethrows ExecutionException if task failed
}
```

## Code Snippet

The following program demonstrates a structured stress test that discovers a bug in a non-thread-safe counter. `UnsafeCounter` uses a plain `int` field and is intentionally broken. `SafeCounter` uses `AtomicInteger`. The test shows that the unsafe version consistently loses increments under concurrent access.

```java
// File: ConcurrentCounterStressTest.java
// Demonstrates: structured stress test with start-gate latch finding a real race condition
// Run: javac ConcurrentCounterStressTest.java && java ConcurrentCounterStressTest

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ConcurrentCounterStressTest {

    // Intentionally broken counter — no synchronization
    static class UnsafeCounter {
        private int count = 0;

        public void increment() {
            count++;            // read-modify-write, not atomic
        }

        public int get() {
            return count;
        }
    }

    // Correct counter
    static class SafeCounter {
        private final AtomicInteger count = new AtomicInteger(0);

        public void increment() {
            count.incrementAndGet();
        }

        public int get() {
            return count.get();
        }
    }

    static void runTest(String label, Runnable incrementOp, java.util.function.IntSupplier getOp)
            throws InterruptedException {

        int threadCount = 50;
        int incrementsPerThread = 2000;
        int expected = threadCount * incrementsPerThread;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate   = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < incrementsPerThread; j++) {
                        incrementOp.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            }, "worker-" + id);
            t.setDaemon(true);
            t.start();
        }

        long start = System.nanoTime();
        startGate.countDown();         // release all 50 threads simultaneously
        endGate.await();               // wait for all to finish
        long elapsed = System.nanoTime() - start;

        int actual = getOp.getAsInt();
        String status = (actual == expected) ? "PASS" : "FAIL";
        System.out.printf("[%s] %s  expected=%d  actual=%d  lost=%d  time=%.1fms%n",
                label, status, expected, actual, expected - actual, elapsed / 1e6);
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Running stress test with 50 threads x 2000 increments each");
        System.out.println("Expected final count: " + (50 * 2000));
        System.out.println();

        // Run multiple rounds to show non-determinism in the failure
        for (int round = 1; round <= 5; round++) {
            System.out.println("--- Round " + round + " ---");
            UnsafeCounter unsafe = new UnsafeCounter();
            runTest("UnsafeCounter", unsafe::increment, unsafe::get);

            SafeCounter safe = new SafeCounter();
            runTest("SafeCounter ", safe::increment,   safe::get);
        }

        System.out.println();
        System.out.println("UnsafeCounter fails because count++ is not atomic.");
        System.out.println("It compiles to: GETFIELD, IADD, PUTFIELD.");
        System.out.println("Two threads can both read the same value before either writes back.");
    }
}
```

Sample output (actual lost count varies per run):

```
Running stress test with 50 threads x 2000 increments each
Expected final count: 100000

--- Round 1 ---
[UnsafeCounter] FAIL  expected=100000  actual=96731  lost=3269  time=8.2ms
[SafeCounter ] PASS  expected=100000  actual=100000  lost=0    time=6.1ms
--- Round 2 ---
[UnsafeCounter] FAIL  expected=100000  actual=97204  lost=2796  time=7.9ms
[SafeCounter ] PASS  expected=100000  actual=100000  lost=0    time=5.8ms
```

## Gotchas

### Tests Passing by Luck

A multi-threaded test that passes consistently on a developer machine may be passing because the machine has only two cores, the JVM happens to serialize the threads due to startup overhead, or the operations complete so fast that overlap never occurs. Running the same test on a high-core-count server or under JVM stress flags (`-XX:+StressGCM`, `-XX:-TieredCompilation`) may reveal bugs immediately. Never treat a passing concurrent test as proof of correctness without understanding the degree of overlap achieved.

### Using Thread.sleep for Synchronization

A common antipattern in concurrent tests is using `Thread.sleep(100)` to "wait for the other thread to finish." This is unreliable in both directions: the sleep may be too short on a loaded CI server, causing the test to check state before the other thread completes, or too long on a fast machine, wasting test time. Use `CountDownLatch`, `CyclicBarrier`, `Future.get()`, or `ExecutorService.awaitTermination()` for deterministic synchronization.

### Asserting on Non-Final State

If you check the counter value before all threads have completed, you may observe a correct intermediate value by coincidence. The assertion must happen after a happens-before relationship has been established between all writer threads and the test thread. Using `endGate.await()` (a `CountDownLatch`) before asserting is correct because `countDown()` happens-before `await()` returns in the thread that called `await()`.

### Test Infrastructure Race Conditions

The test framework itself can have concurrency bugs. If you re-use a `CountDownLatch` or a shared counter across test iterations, a slow thread from round N may still be running when round N+1 starts, corrupting the results. Create fresh instances for each test run. Similarly, if multiple test classes share a static field, parallel test execution (common in modern CI) can cause interference.

### Non-Reproducible Failures in CI

A concurrent test that fails intermittently in CI is almost always signaling a real race condition — do not mark it as flaky and suppress it. Each intermittent failure is evidence of a thread interleaving that your code does not handle correctly. The correct response is to treat it as a confirmed bug, capture the failure log, and fix the underlying race rather than adding a retry or a longer sleep.

### Deadlock Detection in Tests

A test that hangs forever has likely encountered a deadlock. Add a timeout to every multi-threaded test: `endGate.await(30, TimeUnit.SECONDS)` and fail if it returns `false`. Without a timeout, a deadlocked test will block the entire test suite indefinitely in CI. When a timeout occurs, take a thread dump (via `jstack` or a signal) to identify the deadlock.
