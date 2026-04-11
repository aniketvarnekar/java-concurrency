# Phaser

## Overview

`Phaser` is the most flexible synchronization aid in `java.util.concurrent`. It combines and generalizes both `CountDownLatch` and `CyclicBarrier`: it supports multiple phases (like `CyclicBarrier`), a dynamically changing number of parties (unlike both), and independent arrive-and-wait semantics that let threads signal arrival without immediately blocking. A `Phaser` advances to the next phase when all registered parties have arrived at the current phase.

The key innovation is the separation of "arriving" from "waiting". A thread can call `arrive()` to signal that it has completed its work for the current phase without blocking at all, then continue doing other things while other parties catch up. Later it can call `awaitAdvance(phase)` to block until the phase actually advances. This makes `Phaser` suitable for pipelines and asynchronous frameworks where blocking immediately after a phase signal is not always desirable.

Dynamic party registration is equally important. A new thread can register with a live `Phaser` at any time and immediately participates in the current phase. A thread that finishes its work permanently can call `arriveAndDeregister()` so the phaser no longer waits for it. This makes `Phaser` the right tool when the number of participating threads varies over the lifetime of the computation, such as a fork-join style computation where subtasks are added and removed dynamically.

## Key Concepts

### Registration

`new Phaser(int parties)` registers `parties` participants upfront. `register()` adds one party to the current phase's expected arrivals. `bulkRegister(n)` adds `n` parties. Registration is safe to call from any thread at any time.

```java
Phaser phaser = new Phaser(4);   // 4 parties registered at construction
phaser.register();               // a 5th party joins dynamically
phaser.bulkRegister(2);          // 2 more parties join — now 7 total
```

### Deregistration

`arriveAndDeregister()` signals arrival for the current phase and permanently removes the calling thread as a party. The phaser no longer waits for this thread in future phases. Returns the phase number at the time of arrival.

```java
// Thread finishes early — will not participate in phase 2 onward
phaser.arriveAndDeregister();
```

### arrive()

Signals arrival without blocking. The phase does not advance until all registered parties have arrived. Returns the current phase number. A thread that calls `arrive()` can continue executing and later call `awaitAdvance(phase)` to wait for the phase to complete.

```java
int phase = phaser.arrive();
// do other work here while waiting for other parties
phaser.awaitAdvance(phase);  // block until phase advances
```

### awaitAdvance(int phase)

Blocks until the phaser advances past the given phase number. Returns the next phase number, which is `phase + 1` if the phaser is still running, or a negative number if the phaser has terminated. If the phaser has already advanced past `phase` by the time this is called, it returns immediately.

```java
int phase = phaser.getPhase();
// ... do work ...
phaser.arrive();
int nextPhase = phaser.awaitAdvance(phase);
if (nextPhase < 0) {
    System.out.println("Phaser terminated");
}
```

### arriveAndAwaitAdvance()

The most common call: combines `arrive()` and `awaitAdvance()` in a single method. Equivalent to `CyclicBarrier.await()`. All parties block here until the last party arrives, at which point all are released together and the phase advances.

```java
phaser.arriveAndAwaitAdvance();  // like barrier.await()
```

### Phase Number

Starts at 0 and increments each time all registered parties arrive. `getPhase()` returns the current phase. After `Integer.MAX_VALUE` phases the phase number wraps to 0. A negative return from `getPhase()` or `awaitAdvance()` indicates the phaser has terminated.

```java
System.out.println("Current phase: " + phaser.getPhase());
System.out.println("Registered parties: " + phaser.getRegisteredParties());
System.out.println("Arrived parties: " + phaser.getArrivedParties());
```

### Termination

A phaser terminates when its `onAdvance()` method returns `true`. Once terminated, `getPhase()` returns a negative number, and all `awaitAdvance()` calls return immediately with a negative value. `isTerminated()` returns `true`. Terminated phasers release all waiters.

### onAdvance(int phase, int registeredParties)

Override this method in a subclass to run a barrier action per phase and control termination. It is called by the last arriving thread before releasing all waiters. Return `true` to terminate the phaser after this phase, `false` to continue. The default implementation terminates when `registeredParties` reaches 0.

```java
Phaser phaser = new Phaser(4) {
    @Override
    protected boolean onAdvance(int phase, int registeredParties) {
        System.out.println("Phase " + phase + " complete");
        return phase >= 2;  // terminate after phase 2 (3 phases: 0, 1, 2)
    }
};
```

### Comparison Table

| Feature | CountDownLatch | CyclicBarrier | Phaser |
|---|---|---|---|
| Reusable | No | Yes | Yes |
| Dynamic parties | No | No | Yes |
| Barrier action | No | Yes | Yes (onAdvance) |
| Deregistration | No | No | Yes |
| Independent arrive/wait | No | No | Yes (arrive + awaitAdvance) |
| Termination control | Count=0 | N/A | onAdvance() |

### Tree Structure

For very high party counts (thousands of threads), multiple `Phaser` instances can be arranged in a tree. Each leaf phaser reports to a parent phaser. When all parties in a leaf arrive, the leaf phaser automatically arrives at the parent. This reduces contention on a single shared phaser object by distributing the synchronization across multiple nodes.

```
          Root Phaser
         /            \
  Leaf Phaser A    Leaf Phaser B
  (threads 1-500)  (threads 501-1000)
```

## Gotchas

A `Phaser` terminates when all parties deregister and `onAdvance()` returns `true` (the default behavior when `registeredParties` reaches 0). If workers call `arriveAndDeregister()` without tracking phase numbers carefully, the phaser may terminate before the intended number of phases completes. Always verify the expected party count at each phase and override `onAdvance()` to control termination explicitly rather than relying on the default.

`getPhase()` returning a negative number indicates termination. Code that checks `getPhase() == expectedPhase` will never match after termination because the phase number is negative. Always check `phaser.isTerminated()` or check that `getPhase() >= 0` before comparing phase numbers in loops or conditional logic.

`arrive()` does not wait. A thread that calls `arrive()` and then immediately reads shared data written by other parties during the current phase may see stale values. The happens-before guarantee is established only when all parties have arrived and the phase has advanced. Call `awaitAdvance(phase)` before reading results that depend on other parties having completed their phase work.

Registering more parties than actually arrive in a phase causes all `awaitAdvance()` calls to block forever. Every `register()` or `bulkRegister()` call must be matched by a corresponding `arrive()`, `arriveAndAwaitAdvance()`, or `arriveAndDeregister()` call. Exceptions in a worker thread must be caught and followed by `arriveAndDeregister()` in a `finally` block so the phaser is not left waiting for a party that will never arrive.

`bulkRegister(n)` is not atomic with a phase check. If the phase advances between a `getPhase()` check and the `bulkRegister()` call, the newly registered parties are counted against the new phase rather than the intended one. If your code conditionally registers parties based on the current phase, use the arrival index returned by `arrive()` or `arriveAndAwaitAdvance()` to confirm which phase the registration took effect in.

Nesting `Phaser` instances in a tree is an optimization for very high party counts. The tree reduces contention by having each leaf phaser count its local parties and only signal the parent when all local parties have arrived. This is not needed for typical use cases with tens or hundreds of threads. Introduce tree structure only after profiling confirms that contention on a single `Phaser` is a measurable bottleneck.
