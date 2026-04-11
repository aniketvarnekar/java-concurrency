# Semaphore

## Overview

A `Semaphore` maintains a set of permits. `acquire()` blocks until a permit is available, then atomically decrements the permit count. `release()` atomically increments the count, potentially unblocking a waiting thread. The total number of permits controls the maximum concurrency — how many threads can proceed past the acquire point simultaneously. This makes `Semaphore` ideal for throttling access to a shared resource pool where the pool has a fixed number of slots.

Unlike `synchronized` or `ReentrantLock`, a `Semaphore` has no notion of thread ownership. Any thread can call `release()`, including a thread that never called `acquire()`. This asymmetry makes `Semaphore` suitable for signaling patterns where one thread produces capacity and another consumes it, not just for mutual exclusion. A semaphore with a single permit behaves like a mutex in terms of access control, but it can be released by a different thread than the one that acquired it.

The permit count can be set to any non-negative integer. A semaphore initialized to zero starts closed: all `acquire()` calls block until another thread calls `release()`. This pattern inverts the usual flow and is useful for implementing start signals or one-way gates without the one-shot limitation of `CountDownLatch`.

## Key Concepts

### Construction

`new Semaphore(int permits)` — creates a non-fair semaphore. `new Semaphore(int permits, boolean fair)` — when `fair` is `true`, threads acquire permits in FIFO arrival order, preventing starvation at the cost of reduced throughput.

```java
Semaphore pool = new Semaphore(3);           // 3 permits, non-fair
Semaphore fair = new Semaphore(3, true);     // 3 permits, FIFO fairness
```

### acquire()

Blocks until a permit is available, then takes one permit. Throws `InterruptedException` if the thread is interrupted while waiting.

```java
pool.acquire();
try {
    useResource();
} finally {
    pool.release();
}
```

### acquire(int n)

Acquires `n` permits atomically. Blocks until `n` permits are simultaneously available. This is not equivalent to calling `acquire()` in a loop — the single call avoids partial acquisition.

```java
pool.acquire(2);  // blocks until at least 2 permits are available
```

### tryAcquire()

Non-blocking. Returns `true` and takes a permit if one is immediately available, `false` otherwise. Useful for implementing fallback logic when a resource is not immediately available.

```java
if (pool.tryAcquire()) {
    try { useResource(); } finally { pool.release(); }
} else {
    handleResourceUnavailable();
}
```

### tryAcquire(long timeout, TimeUnit unit)

Timed attempt. Returns `true` if a permit was acquired before the timeout elapsed, `false` otherwise. Throws `InterruptedException` if interrupted.

```java
if (pool.tryAcquire(500, TimeUnit.MILLISECONDS)) {
    try { useResource(); } finally { pool.release(); }
}
```

### release()

Returns one permit to the semaphore. Does not require the releasing thread to be the one that acquired the permit. Must be called in a `finally` block to prevent permit leaks. If the semaphore was initialized with `N` permits, calling `release()` without a prior `acquire()` increases the permit count above `N`.

```java
pool.acquire();
try {
    doWork();
} finally {
    pool.release();  // always in finally
}
```

### release(int n)

Returns `n` permits in a single call. Equivalent to calling `release()` `n` times but slightly more efficient.

### Fairness

`new Semaphore(permits, true)` uses FIFO ordering for waiting threads. Threads acquire permits in the order they began waiting. This prevents thread starvation in high-contention scenarios but reduces throughput compared to the non-fair mode, where the JVM can hand off a permit to whichever thread is most convenient to schedule.

```
Non-fair: any waiting thread may be chosen — higher throughput, possible starvation
Fair:     strict FIFO arrival order — lower throughput, no starvation
```

### Resource Pool Guard

The canonical pattern: each resource slot corresponds to one permit. A thread must acquire a permit before borrowing a slot and release the permit in a `finally` block when returning the slot.

```
Available permits: [3 slots]

Thread-1 acquire() → permits=[2], uses slot
Thread-2 acquire() → permits=[1], uses slot
Thread-3 acquire() → permits=[0], uses slot
Thread-4 acquire() → BLOCKS (no permits available)

Thread-1 release() → permits=[1], Thread-4 unblocks → permits=[0]
```

### Binary Semaphore (permits=1)

A semaphore with one permit acts as a mutex but with cross-thread release capability. One thread acquires (closes the gate), another releases (opens the gate). This makes binary semaphores useful for producer-consumer signaling where the producer unblocks the consumer.

```java
Semaphore signal = new Semaphore(0);  // starts closed

// Consumer thread
signal.acquire();  // blocks until producer signals
processData();

// Producer thread
prepareData();
signal.release();  // unblocks consumer
```

## Gotchas

`Semaphore` is not reentrant. A thread that calls `acquire()` twice will block on the second call even though it already holds a permit, because the semaphore tracks permit counts, not thread ownership. This differs fundamentally from `ReentrantLock` and `synchronized`, which allow the holding thread to re-enter. Wrapping a non-reentrant `Semaphore` in a class that may call back into its own acquisition logic is a common source of deadlocks.

`release()` must be called in a `finally` block. If the code between `acquire()` and `release()` throws an unchecked exception and `release()` is not in a `finally` block, a permit is permanently lost. The pool's effective size silently shrinks until the JVM is restarted. This is often a latent bug that only manifests under error conditions that are rare in testing.

Calling `release()` without a prior `acquire()` increases the permit count above its initial value. There is no enforcement of the "acquire before release" invariant. Extra releases allow more threads to proceed simultaneously than intended, defeating the purpose of the resource guard. If you use semaphores to protect a fixed-size pool, be disciplined: only the thread (or its logical owner) responsible for a slot should release it.

Fair mode (`true`) prevents starvation but can significantly reduce throughput because permits must be handed to waiting threads in FIFO order even when a non-waiting thread could take the permit immediately. Measure the performance impact before enabling fairness in high-throughput systems. Non-fair mode is the default for a reason.

`acquire(n)` with `n` greater than the number of currently available permits blocks until exactly `n` permits are simultaneously available. If other threads hold permits indefinitely, or if the total available permits can never reach `n`, this blocks forever. A subtle deadlock arises when the thread blocked in `acquire(n)` is the only one able to trigger the conditions that would cause permit holders to release.

Semaphore permits are not associated with threads. A thread that crashes or is killed without calling `release()` permanently reduces the pool's available permit count. Monitoring `availablePermits()` over time can detect permit leaks: if the count trends steadily downward under steady load without recovering, a thread is failing to release. Add permit counts to application health metrics in production systems.
