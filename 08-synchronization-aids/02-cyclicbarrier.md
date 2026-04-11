# CyclicBarrier

## Overview

`CyclicBarrier` makes a fixed number of threads — the parties — wait for each other at a common synchronization point. When all parties have called `await()`, the barrier trips: an optional barrier action runs on the last-arriving thread, then all threads are released simultaneously and the barrier automatically resets for the next cycle. This makes `CyclicBarrier` ideal for parallel algorithms that proceed in phases, where every thread must complete one phase before any begins the next.

The automatic reset is the defining difference from `CountDownLatch`. After the barrier trips, the same instance is ready for another round without any manual intervention. A multi-phase matrix computation, a simulation with discrete time steps, or a parallel search that refines results over iterations are all natural fits for `CyclicBarrier`.

Error handling is more demanding than with `CountDownLatch` because one interrupted or failed thread breaks the barrier for all parties. When one thread is interrupted while waiting, all other waiting threads receive `BrokenBarrierException`. Production code must decide whether to reset the barrier and retry the phase or abort the entire computation.

## Key Concepts

### Construction

`new CyclicBarrier(int parties)` creates a barrier with no barrier action. `new CyclicBarrier(int parties, Runnable barrierAction)` adds an action that runs when the barrier trips.

```java
CyclicBarrier barrier = new CyclicBarrier(4, () ->
    System.out.println("All 4 workers reached the barrier — advancing phase"));
```

### await()

The calling thread declares it has reached the barrier. It blocks until all parties have arrived, or the barrier is broken. Returns the arrival index: `parties - 1` for the first thread to arrive, `0` for the last. Throws `InterruptedException` if interrupted, or `BrokenBarrierException` if the barrier was broken before or during the wait.

```java
try {
    int index = barrier.await();
    // index == 0 means this thread was the last to arrive
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
} catch (BrokenBarrierException e) {
    // another thread was interrupted, or the barrier was reset
}
```

### Barrier Action

A `Runnable` supplied at construction time. It runs on the last thread to arrive at the barrier, before any waiters are released. This makes it the right place to aggregate per-phase results — all worker threads have finished writing to shared state before the action runs, and no thread begins the next phase until the action completes.

```java
int[] phaseResults = new int[4];

CyclicBarrier barrier = new CyclicBarrier(4, () -> {
    int sum = 0;
    for (int r : phaseResults) sum += r;
    System.out.println("Phase result: " + sum);
});
```

### Cyclic Reset

After the barrier trips and all threads are released, the barrier resets to its initial state automatically. The same instance coordinates phase 1, phase 2, phase 3, and so on, without any external management.

```
Phase 1                Phase 2                Phase 3
W1 ──────────┐         W1 ──────────┐         W1 ──────┐
W2 ──────┐   │         W2 ──────────┤         W2 ──┐   │
W3 ──────┤ TRIP        W3 ──┐     TRIP         W3 ──┤ TRIP
W4 ──┐   │   │  reset  W4 ──┘       │  reset  W4 ──┘   │
     └───┘   │ ──────>               │ ──────>           │
             └ [barrier action runs] └ [action runs]     └ [action runs]
```

### BrokenBarrierException

Thrown by `await()` when the barrier is in a broken state. The barrier becomes broken if: a waiting thread is interrupted; the barrier action throws a runtime exception; `reset()` is called while threads are waiting; or the barrier was already broken. Once broken, all subsequent `await()` calls throw `BrokenBarrierException` until `reset()` is called.

```java
} catch (BrokenBarrierException e) {
    System.err.println("Barrier broken — resetting and retrying phase");
    barrier.reset();
}
```

### reset()

Forcibly resets the barrier to its initial count. Any threads currently waiting in `await()` receive `BrokenBarrierException`. Use this during error recovery when you want to restart a phase after a failure, rather than propagating the broken state.

### getNumberWaiting()

Returns the number of parties currently blocked in `await()`. Useful in monitoring or debugging to confirm threads are making progress toward the next barrier trip.

```java
System.out.println("Waiting parties: " + barrier.getNumberWaiting());
```

## Gotchas

`BrokenBarrierException` propagates to all waiting threads when one thread is interrupted. One bad thread breaks the barrier for everyone. Production code must decide whether to call `reset()` and retry the phase or abort the computation. Simply catching the exception and continuing into the next phase without resetting leaves the barrier in an inconsistent state for all remaining threads.

The barrier action runs on the last thread to arrive. If the action is slow or throws a runtime exception, all other parties remain blocked in `await()` until the action completes or throws. A thrown exception breaks the barrier and causes all waiters to receive `BrokenBarrierException`. Keep barrier actions fast, and wrap them defensively if they perform I/O or aggregation that might fail.

`CyclicBarrier`'s party count is fixed at construction. You cannot add or remove parties dynamically while the barrier is in use. If a thread leaves the group permanently (for example, a worker that finishes early and should no longer participate), the barrier will never trip because it keeps waiting for the departed party. For dynamic party counts, use `Phaser` with `arriveAndDeregister()`.

The arrival index returned by `await()` is not a stable thread identifier. Two runs of the same program may return different indices for the same thread depending on scheduling. Do not use the arrival index to assign roles or partition work across threads in a way that requires consistency between phases.

Unlike `CountDownLatch`, `CyclicBarrier.await()` throws `BrokenBarrierException` in addition to `InterruptedException`. Code that only catches `InterruptedException` will fail to compile cleanly (for checked exceptions) or silently miss the broken-barrier case (for unchecked). Both exceptions must be handled at every `await()` call site.

Calling `await()` from a thread that is not one of the N registered parties has no special meaning. The barrier counts arrivals, and if a non-party thread calls `await()`, it simply becomes another waiter. The barrier never trips because it expects exactly N arrivals, not N+1. This causes all parties and the extra thread to wait forever. Always count the exact number of threads that will call `await()` in each cycle.
