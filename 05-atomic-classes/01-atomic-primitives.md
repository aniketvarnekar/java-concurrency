# 01 — Atomic Primitives

## Overview

The `java.util.concurrent.atomic` package, introduced in Java 5, provides a set of classes that support lock-free, thread-safe operations on single variables. The key insight behind these classes is that modern CPUs offer an atomic instruction — compare-and-swap (CAS) — that can read a memory location, compare it to an expected value, and conditionally write a new value, all as a single uninterruptible operation. The atomic classes expose this hardware capability directly, eliminating the need for `synchronized` blocks or explicit locks for many common patterns.

`AtomicInteger`, `AtomicLong`, and `AtomicBoolean` are the workhorses of the package. Each wraps a single primitive value and provides a rich API for reading, writing, and conditionally updating that value. Because all updates go through CAS, multiple threads can operate on the same atomic variable concurrently without any thread ever holding a lock. Under low to moderate contention, this is significantly faster than using `synchronized`.

The package also includes array variants — `AtomicIntegerArray`, `AtomicLongArray`, and `AtomicReferenceArray` — that provide element-level atomic operations. Unlike a plain `int[]` wrapped in a `synchronized` block, these arrays allow concurrent updates to different indices without any mutual exclusion between threads targeting different elements.

The atomic classes are intentionally limited in scope. They guarantee atomicity for a single read-modify-write operation on a single variable. The moment you need to atomically update two variables together, or perform a sequence of atomic operations as a unit, you need a higher-level mechanism such as a lock or a data structure designed for that purpose.

## Key Concepts

### AtomicInteger

`AtomicInteger` is the most commonly used atomic class. Its API covers four categories of operation.

**Unconditional reads and writes:**

```java
AtomicInteger ai = new AtomicInteger(10);
int val = ai.get();        // read: 10
ai.set(20);                // write: 20 (volatile write)
int prev = ai.getAndSet(30); // atomic swap: returns 20, sets to 30
```

**Arithmetic — returning the old value:**

```java
int old = ai.getAndIncrement(); // post-increment: returns current, then adds 1
int old = ai.getAndDecrement(); // post-decrement
int old = ai.getAndAdd(5);      // adds 5, returns old value
```

**Arithmetic — returning the new value:**

```java
int next = ai.incrementAndGet(); // pre-increment: adds 1, returns new value
int next = ai.decrementAndGet();
int next = ai.addAndGet(5);
```

**Conditional update:**

```java
boolean success = ai.compareAndSet(expected, newValue);
// Atomically: if current == expected, set to newValue and return true.
// Otherwise, return false without modifying the value.
```

**Functional update:**

```java
// updateAndGet: applies operator and returns the NEW value
int newVal = ai.updateAndGet(x -> x * 2);

// getAndUpdate: applies operator and returns the OLD value
int oldVal = ai.getAndUpdate(x -> x * 2);

// accumulateAndGet: combines current value with the given int using a BinaryOperator,
// returns the new value
int result = ai.accumulateAndGet(5, Integer::max);

// getAndAccumulate: same but returns the old value
int before = ai.getAndAccumulate(5, Integer::max);
```

The full `AtomicInteger` method reference:

| Method | Returns | Meaning |
|---|---|---|
| `get()` | current value | Plain read |
| `set(v)` | void | Plain write (volatile) |
| `lazySet(v)` | void | Eventually consistent write (no StoreLoad fence) |
| `getAndSet(v)` | old value | Atomic swap |
| `compareAndSet(e, u)` | boolean | CAS; true if update happened |
| `weakCompareAndSet(e, u)` | boolean | May spuriously fail; use in loops |
| `getAndIncrement()` | old value | Atomic post-increment |
| `getAndDecrement()` | old value | Atomic post-decrement |
| `getAndAdd(d)` | old value | Atomic add, returns old |
| `incrementAndGet()` | new value | Atomic pre-increment |
| `decrementAndGet()` | new value | Atomic pre-decrement |
| `addAndGet(d)` | new value | Atomic add, returns new |
| `updateAndGet(op)` | new value | Apply `UnaryOperator`, return new |
| `getAndUpdate(op)` | old value | Apply `UnaryOperator`, return old |
| `accumulateAndGet(x, op)` | new value | Combine with `x` using `IntBinaryOperator`, return new |
| `getAndAccumulate(x, op)` | old value | Combine with `x` using `IntBinaryOperator`, return old |
| `intValue()` | int | Implements `Number` |
| `longValue()` | long | Widening conversion |

### AtomicLong

`AtomicLong` has exactly the same API as `AtomicInteger` with all method signatures widened to `long`. The internal CAS instruction used is 64-bit. On 32-bit JVMs, 64-bit CAS may require a platform-level lock, but on all modern 64-bit JVMs it is a single hardware instruction.

Use `AtomicLong` when the counter might exceed `Integer.MAX_VALUE` (2,147,483,647) or when you are working with timestamps, sequence numbers, or file offsets.

### AtomicBoolean

`AtomicBoolean` wraps a boolean value. Its primary use case is a one-shot initialization guard or a cancellation flag. The most important method is `compareAndSet`:

```java
AtomicBoolean initialized = new AtomicBoolean(false);

// Only one thread among all concurrent callers will see true returned.
if (initialized.compareAndSet(false, true)) {
    // This block executes exactly once across all threads.
    performExpensiveInitialization();
}
```

`AtomicBoolean` also has `get()`, `set(boolean)`, and `getAndSet(boolean)`. It does not have arithmetic operations since booleans are not numeric.

### AtomicIntegerArray, AtomicLongArray, AtomicReferenceArray

These classes provide element-level atomic operations on arrays. Every method takes an index as the first argument.

```java
AtomicIntegerArray arr = new AtomicIntegerArray(10); // 10-element array, all zero

arr.set(3, 100);                     // set index 3 to 100
int v = arr.get(3);                  // read index 3
arr.incrementAndGet(3);              // atomically increment index 3
arr.compareAndSet(3, 101, 200);      // CAS at index 3
```

Because operations on different indices are independent, threads updating different elements never contend with each other. This makes atomic arrays useful for per-bucket counters, hash tables, and histograms.

### updateAndGet vs getAndUpdate

Both methods apply a `UnaryOperator` to the current value. The difference is what they return:

- `updateAndGet(op)` returns the **new** value (the result of applying `op`).
- `getAndUpdate(op)` returns the **old** value (before `op` was applied).

The analogy is the difference between pre-increment (`++i`, returns new value) and post-increment (`i++`, returns old value). Choose whichever the caller needs to avoid a redundant subsequent `get()` call.

```java
AtomicInteger counter = new AtomicInteger(5);

int after  = counter.updateAndGet(x -> x + 10);  // after  == 15
int before = counter.getAndUpdate(x -> x + 10);  // before == 15, counter is now 25
```

## Code Snippet

```java
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Demonstrates AtomicInteger as a shared counter, AtomicBoolean as a one-shot
 * initialization guard, and AtomicIntegerArray for per-index thread-local counters.
 *
 * Run: javac 01-atomic-primitives.md  (see AtomicCounterDemo.java for runnable version)
 */
public class AtomicPrimitivesSnippet {
    static final int THREADS = 8;
    static final int INCREMENTS_PER_THREAD = 100_000;

    static final AtomicInteger sharedCounter = new AtomicInteger(0);
    static final AtomicBoolean initialized   = new AtomicBoolean(false);
    static final AtomicIntegerArray perThread = new AtomicIntegerArray(THREADS);

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int id = t;
            Thread thread = new Thread(() -> {
                // One-shot initialization: only one thread will execute this block.
                if (initialized.compareAndSet(false, true)) {
                    System.out.println(Thread.currentThread().getName()
                            + " performing one-time initialization");
                }

                for (int i = 0; i < INCREMENTS_PER_THREAD; i++) {
                    sharedCounter.incrementAndGet();
                    perThread.incrementAndGet(id);
                }
                latch.countDown();
            }, "worker-" + t);
            thread.start();
        }

        latch.await();

        int expected = THREADS * INCREMENTS_PER_THREAD;
        int actual   = sharedCounter.get();
        System.out.println("Expected: " + expected + ", Actual: " + actual
                + ", Correct: " + (expected == actual));

        int perThreadSum = 0;
        for (int t = 0; t < THREADS; t++) {
            perThreadSum += perThread.get(t);
        }
        System.out.println("Per-thread sum: " + perThreadSum
                + ", Matches total: " + (perThreadSum == actual));
    }
}
```

## Gotchas

### Compound operations are not atomic

Each method on `AtomicInteger` is individually atomic, but a sequence of two calls is not. The pattern `if (ai.get() == 0) { ai.set(1); }` has a race condition: another thread can change the value between the `get` and the `set`. Use `compareAndSet` or `updateAndGet` instead.

### get() followed by set() is not compareAndSet()

A common mistake is to read the current value, compute a new value, and then call `set()` with the result. If another thread modifies the variable between your `get()` and your `set()`, you silently overwrite the other thread's update. `compareAndSet` detects this and lets you retry; `set` does not.

### updateAndGet and accumulateAndGet may apply the operator more than once

Internally, `updateAndGet` implements a CAS loop: it reads the current value, applies the operator, and attempts a CAS. If the CAS fails (another thread changed the value), it retries, applying the operator again. Operators passed to these methods must be side-effect-free. An operator that logs, counts, or mutates external state will behave incorrectly under contention.

### AtomicInteger is not a drop-in replacement for Integer

`AtomicInteger` does not extend `Integer` and does not implement `Comparable<AtomicInteger>`. Code that expects `Integer` — collections sorted by natural order, methods accepting `Integer` parameters, autoboxing — cannot use `AtomicInteger` directly. It is a concurrency utility, not a numeric type.

### lazySet() provides no ordering guarantees for reads

`lazySet(v)` is a relaxed write with no StoreLoad memory fence. Another thread may not see the written value immediately. It is useful in specific producer-consumer scenarios where you want to avoid the full barrier cost and subsequent reads are separated by other synchronization. Using it naively instead of `set()` introduces subtle visibility bugs.

### weakCompareAndSet() can fail spuriously

`weakCompareAndSet` may return `false` even when the current value equals the expected value. It is intended only for use inside a retry loop. On x86 it behaves identically to `compareAndSet`, but on ARM-based architectures (including Apple Silicon and many Android devices) spurious failures are real. Always wrap it in a loop.
