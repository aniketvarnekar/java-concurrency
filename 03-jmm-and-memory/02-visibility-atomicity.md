# Visibility and Atomicity

## Overview

Visibility and atomicity are two distinct memory guarantees that concurrent programs require, and conflating them is one of the most common sources of subtle bugs. A variable can be visible — meaning every thread's read returns the most recently written value — without its operations being atomic. Conversely, an operation can be atomic — meaning it completes in a single indivisible step — without the result being immediately visible to other threads. Correct concurrent code must reason about both properties separately.

Visibility failures manifest as stale reads: one thread writes a value, and another thread reads the same variable but sees an old value because the write was cached in the writing thread's working memory and never flushed to main memory. Atomicity failures manifest as lost updates or inconsistent state: multiple threads interleave their operations on a shared variable in a way that produces an incorrect result, because what looks like a single operation in source code is actually multiple machine instructions.

The `volatile` keyword and `synchronized` blocks are the two primary language mechanisms for establishing these guarantees, but they address different subsets. `volatile` provides visibility and prevents certain reorderings but does not make compound operations atomic. `synchronized` provides both visibility and atomicity by granting exclusive access to the monitor and flushing the thread's working memory on entry and exit. The `java.util.concurrent.atomic` package provides classes that give both guarantees for single-variable operations using hardware-level compare-and-swap instructions.

Understanding which guarantee a given construct provides — and which it does not — is essential for selecting the right tool. Using `volatile` where you need atomicity is a correctness bug. Using `synchronized` where `volatile` would suffice is a performance issue. Using neither where both are needed is a data race.

## Key Concepts

### Visibility

A write to a variable is visible to a subsequent read in another thread when the JMM guarantees that the reading thread will see the written value rather than a stale one. Without a happens-before relationship between the write and the read, the JMM makes no such guarantee.

The classic visibility failure is the stop-flag pattern without `volatile`. The worker thread loads the flag into a register during the first iteration of its loop. The JIT compiler, observing that the flag is never written inside the loop, is permitted to hoist the load out of the loop entirely and treat it as a constant. The main thread's write to the flag goes to main memory, but the worker's loop body never re-reads from main memory and never sees the update.

```java
// Without volatile — visibility failure
private static boolean stopRequested = false;

// Worker sees stopRequested as false forever (JIT may hoist the read)
while (!stopRequested) { /* work */ }

// With volatile — visibility guaranteed
private static volatile boolean stopRequested = false;

// Worker re-reads stopRequested on every iteration from main memory
while (!stopRequested) { /* work */ }
```

Declaring a variable `volatile` inserts a memory barrier that forces the writing thread to flush the write to main memory and forces all reading threads to reload the value from main memory rather than from cache. The exact barrier instructions depend on the hardware architecture.

### Atomicity

An operation is atomic when it appears to other threads to complete instantaneously — there is no observable intermediate state. In Java, reads and writes to most primitive types and object references are atomic at the JVM level: no thread will ever observe a half-written `int`. However, compound operations — those that involve more than one machine-level step — are not atomic even if the source code suggests they are.

The most common example is `counter++`, which decompiles to three operations: read the current value, add one, write the result back. If two threads execute `counter++` concurrently without synchronization, they can both read the same initial value, both compute the incremented value, and both write the same result, effectively losing one increment. This is a read-modify-write race.

Check-then-act sequences have the same problem. The pattern `if (map.containsKey(key)) { map.get(key) }` is not atomic: between the `containsKey` check and the `get`, another thread can remove the key. Even if each individual call is thread-safe, the compound operation is not.

```java
// Not atomic — three machine instructions
counter++;

// Atomic — single compare-and-swap at the hardware level
AtomicInteger atomicCounter = new AtomicInteger(0);
atomicCounter.incrementAndGet();
```

### The volatile Trade-off

`volatile` provides visibility for individual reads and writes but does not extend that guarantee to compound operations. A `volatile int counter` ensures that every read sees the latest write, but `counter++` is still three steps: the read and the write are each individually visible, but nothing prevents two threads from interleaving between the read and the write.

The use cases for `volatile` are therefore narrow but important: single-writer, multiple-reader patterns (a status flag set by one thread and read by others), publication of immutable objects, and double-checked locking with a `volatile` reference (covered in `03-reordering-and-fences.md`). When you need both visibility and atomicity for a single numeric variable, use an `AtomicInteger`, `AtomicLong`, or similar class. When you need both guarantees for a block of multiple operations, use `synchronized` or an explicit lock.

### long and double Non-Atomicity

On 32-bit JVMs, reads and writes to `long` and `double` variables are not guaranteed to be atomic. Because these types are 64 bits wide and a 32-bit word is only 32 bits, the JVM may implement a 64-bit write as two 32-bit writes. A concurrent reader could observe the first 32-bit half of a new value combined with the second 32-bit half of the old value — a "word tearing" that produces a value that was never assigned by any thread.

Declaring the variable `volatile` eliminates this problem even on 32-bit JVMs: the JMM specifies that reads and writes to `volatile long` and `volatile double` are atomic. On modern 64-bit JVMs, all `long` and `double` operations happen to be atomic even without `volatile`, but the language specification does not require it unless `volatile` is used. Writing code that relies on 64-bit atomicity without `volatile` is not portable.

```java
// Not guaranteed atomic on 32-bit JVMs — word tearing possible
private long timestamp;

// Atomic on all JVMs
private volatile long timestamp;

// Also atomic — synchronized provides atomicity for any type
private long timestamp;
public synchronized long getTimestamp() { return timestamp; }
public synchronized void setTimestamp(long t) { timestamp = t; }
```

### synchronized Gives Both

A `synchronized` method or block provides both visibility and atomicity. On entry to a synchronized block, the thread flushes its working memory and re-reads shared state from main memory (visibility on the read side). On exit, it writes all modified variables to main memory before releasing the monitor (visibility on the write side). Because only one thread can hold a given monitor at a time, the operations inside the block are executed without interleaving from other threads competing for the same monitor (atomicity).

The critical qualifier is "competing for the same monitor." Synchronization only establishes guarantees between threads that synchronize on the same object. Two threads calling `synchronized` methods on different objects do not coordinate with each other in any way. A common mistake is to synchronize reads and writes on different objects, achieving neither visibility nor atomicity.

```java
// Both reads and writes must synchronize on the SAME monitor
public class SafeCounter {
    private int count = 0;
    private final Object lock = new Object();

    public void increment() {
        synchronized (lock) { count++; }
    }

    public int get() {
        synchronized (lock) { return count; }
    }
}
```

## Gotchas

### volatile Does Not Replace synchronized for Compound Operations

A field marked `volatile` guarantees that each individual read and write is visible, but a compound operation like `count++` or `balance -= amount` is still a race condition. Every compound operation that must be indivisible requires either `synchronized` or an atomic class. Many developers add `volatile` to a counter and assume the problem is solved; the counter will still lose increments under concurrent access.

### Synchronizing Only Writes (Or Only Reads) Is Not Enough

Both the write and the read of a shared variable must use the same monitor for the visibility guarantee to hold. If a write is inside a `synchronized` block but the read is not, the reading thread is not required to see the written value. The monitor-exit flush is only observed by threads that enter the same monitor. An unsynchronized read simply reads from working memory without establishing any happens-before relationship with the write.

### volatile Is Not a General-Purpose Thread-Safety Tool

Seeing `volatile` on a field creates a false sense of security. It is only appropriate for specific patterns: a single-writer flag, a reference to an immutable object, or a value that is written once and then only read. For any scenario involving multiple threads writing, or any scenario where correctness depends on reading and writing multiple related variables together, `volatile` alone is insufficient.

### AtomicInteger.incrementAndGet() Does Not Compose

Individual atomic operations on `AtomicInteger` are each atomic, but a sequence of two separate atomic operations is not. The code `if (counter.get() == 0) { counter.set(1); }` has a check-then-act race even though `get()` and `set()` are individually atomic. For check-then-act patterns, use `compareAndSet()` which combines the check and the update into a single atomic operation.

### long and double Tearing Is a Real Platform Issue

On 32-bit JVMs (still used in embedded environments), unguarded reads and writes to `long` fields genuinely produce corrupted values. While most developers work on 64-bit systems where this does not occur in practice, code intended to be portable should always use `volatile` or `synchronized` for `long` and `double` fields that are accessed by multiple threads.

### Visibility and Atomicity Are Necessary But Not Sufficient

Even with both visibility and atomicity, a class can still be thread-unsafe if its invariants involve multiple variables that must be updated together. A class that maintains `min` and `max` as two separate `volatile` fields can still exhibit states where `min > max` between updates. Atomicity and visibility operate at the level of individual variable accesses; multi-variable invariants require locking that spans all related variables.
