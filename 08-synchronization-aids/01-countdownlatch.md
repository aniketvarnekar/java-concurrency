# CountDownLatch

## Overview

`CountDownLatch` is initialized with a positive integer count. Any number of threads can call `await()` to block until the count reaches zero. Other threads call `countDown()` to decrement the count without blocking. Once the count reaches zero, all threads blocked in `await()` are released simultaneously, and all subsequent calls to `await()` return immediately. The count cannot be reset — `CountDownLatch` is a one-shot device.

The primary use cases are the start-gate and end-gate patterns. A start-gate latch (initialized to 1) holds back a group of worker threads until the controller is ready to release them all at once, creating a fair race. An end-gate latch (initialized to the number of workers) lets a coordinator wait for all workers to complete without coupling itself to specific `Thread` objects.

Unlike `Thread.join()`, an end-gate latch works with any set of threads or tasks submitted to an `ExecutorService`, making it more flexible in modern concurrent code. The latch's one-shot nature is a deliberate design choice: it guarantees that once released, the latch never closes again, which eliminates an entire class of re-entrance and reset bugs.

## Key Concepts

### Construction

`new CountDownLatch(int count)` — the count represents the number of events that must occur before waiters are released. It must be positive.

```java
CountDownLatch startGate = new CountDownLatch(1);  // single release event
CountDownLatch endGate   = new CountDownLatch(5);  // 5 workers must finish
```

### await()

Blocks the calling thread until the count reaches zero. Throws `InterruptedException` if the thread is interrupted while waiting. Returns immediately if the count is already zero at the moment of the call.

```java
try {
    latch.await();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    // handle gracefully
}
```

### await(long timeout, TimeUnit unit)

Timed version. Returns `true` if the count reached zero before the timeout, `false` if the timeout elapsed first. Useful when a coordinator must not block indefinitely waiting for workers that may have failed.

```java
boolean completed = endGate.await(10, TimeUnit.SECONDS);
if (!completed) {
    System.out.println("Timed out waiting for workers");
}
```

### countDown()

Decrements the count by one. Does not block. Safe to call from any thread, including threads that never called `await()`. When the count reaches zero, all waiting threads are released. Calling `countDown()` after the count has already reached zero is a no-op.

```java
try {
    doWork();
} finally {
    endGate.countDown();  // always in finally to avoid leaving waiters stuck
}
```

### Start-Gate Pattern

A latch initialized to 1 holds all worker threads at a common starting point. The controller calls `countDown()` once, releasing all workers simultaneously. This ensures that no worker begins work before all workers are ready, creating a fair starting condition for benchmarking or parallel races.

```
Controller                 Worker-1   Worker-2   Worker-3
    |                          |          |          |
    |                       await()    await()    await()
    |                          |          |          |
countDown() ───────────────> [release all waiters simultaneously]
    |                          |          |          |
    |                        work       work       work
```

### End-Gate Pattern

A latch initialized to N (the number of workers) lets the coordinator block until every worker has completed. Each worker calls `countDown()` in a `finally` block so the latch is always decremented, even if the worker throws an exception.

```java
CountDownLatch end = new CountDownLatch(N);
for (int i = 0; i < N; i++) {
    executor.submit(() -> {
        try { doWork(); }
        finally { end.countDown(); }
    });
}
end.await();  // coordinator blocks here until all N workers are done
```

### One-Shot Limitation

`CountDownLatch` cannot be reset. Once the count reaches zero it stays at zero permanently. Any new `await()` calls return immediately without blocking. For reusable barriers, use `CyclicBarrier`. For dynamic party counts or multiple phases, use `Phaser`.

## Gotchas

`CountDownLatch` cannot be reused. Once the count reaches zero, it stays at zero permanently. Any new `await()` calls return immediately. If you need to reset the barrier after each phase, use `CyclicBarrier` instead. A common mistake is to allocate a new `CountDownLatch` inside a loop but forget to wait on the previous one, creating a logic error that is difficult to reproduce under normal load.

If a worker thread throws an exception and fails to call `countDown()`, threads blocked in `await()` will wait forever. The fix is always to call `countDown()` in a `finally` block, guaranteeing the decrement happens regardless of whether the work succeeds or throws. This is one of the most common bugs in code that uses latches.

`await()` is interruptible. A thread waiting in `await()` that is interrupted will throw `InterruptedException`. The correct response is to restore the interrupt flag (`Thread.currentThread().interrupt()`) and handle the incomplete state. Swallowing the exception and continuing as though the latch was released can leave the program in an inconsistent state where some work appears done when it is not.

Calling `countDown()` more times than the initial count is harmless. The count floors at zero, and the behavior is exactly as if it reached zero on the final legitimate call. This means redundant `countDown()` calls do not cause errors, but they can mask logic bugs where a caller miscounts the number of events.

A start-gate latch with a count greater than 1 is valid but unusual. If multiple preparatory events must all complete before workers begin, each event calls `countDown()` and all workers `await()` in the same pattern. This is less common; document the intent clearly so readers do not mistake it for an end-gate latch.

Using `Thread.join()` instead of an end-gate latch couples the coordinator to specific `Thread` objects. `CountDownLatch` works with threads, tasks submitted to an `ExecutorService`, or any code that can call `countDown()` at the right time. This makes it significantly more flexible in production systems where tasks are managed by a pool rather than individual named threads.
