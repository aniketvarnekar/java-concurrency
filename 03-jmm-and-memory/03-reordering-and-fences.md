# Reordering and Memory Fences

## Overview

Both compilers and CPUs reorder instructions freely as an optimization strategy. Within a single thread this reordering is invisible because the runtime guarantees that the thread's own observable behavior matches what the original source code describes — a property called intra-thread consistency. Across threads, however, reordering becomes visible because one thread can observe memory writes from another thread in an order that does not match the source code of the writing thread.

Compiler reordering occurs at compile time: the Java compiler (javac) or, more significantly, the JIT compiler inside the JVM may move a load or store to a different position in the instruction sequence if it determines that the single-threaded result is unchanged. CPU reordering occurs at runtime: modern processors execute instructions out of order (out-of-order execution), speculate past branches, and use store buffers that delay the propagation of writes to cache or main memory. These two layers of reordering act independently, meaning that even if the JIT does not reorder code, the CPU may still do so at the instruction level.

Memory barriers (also called memory fences) are the mechanism by which reordering is controlled. A barrier is a CPU instruction that restricts which loads and stores can be moved across it. The JVM inserts barrier instructions automatically when the Java program uses `volatile` or `synchronized`, so Java developers do not write barrier instructions directly. But understanding what barriers do and where the JVM places them explains why `volatile` and `synchronized` work, and why removing them breaks programs in ways that are hard to reproduce.

The double-checked locking idiom is the canonical example of a reordering bug in Java: a pattern that looks correct, appeared to work in testing, and was widely used before JSR-133 clarified exactly why it was broken and how to fix it. Understanding reordering makes the fix obvious.

## Key Concepts

### Compiler Reordering

The JIT compiler treats each method as a unit of optimization. If two statements in the same method do not appear to have a dependency between them — neither uses the result of the other, and neither involves I/O or synchronization — the compiler may execute them in any order. For example:

```java
// Original order
a = 1;
b = 2;
x = a + b;
```

The compiler might reorder the writes to `a` and `b` if it concludes they are independent. In a single-threaded context this is harmless because `x = a + b` still reads the correct values. In a multi-threaded context, a second thread that reads `a` and `b` expecting to see them updated together might see `b = 2` before `a = 1`, or might see `a = 1` with the old value of `b`, depending on when it reads relative to the reordering.

### CPU Reordering (Store Buffer, Load Buffer)

Modern CPUs do not write to main memory directly on each store instruction. Instead, they use a store buffer: a small queue of pending writes that drains asynchronously to the cache hierarchy. This allows the CPU to proceed with the next instruction without waiting for the write to propagate. A subsequent load by the same CPU can bypass the store buffer (forwarding), but a different CPU reading the same address will not see the buffered write until the buffer drains.

```
CPU 0                               CPU 1
  |                                   |
  | store flag=1                      | load flag
  |---> [Store Buffer]                |---> reads from cache: flag=0
  |         |                         |     (store buffer not yet drained)
  |         v (eventually)            |
  |     [Cache / Main Memory]         |
  |         flag=1                    |
```

Load reordering also occurs. A CPU may speculatively execute a load ahead of an earlier store if it predicts the store does not alias the load address. This means a thread can appear to read a value before a preceding write has completed, from the perspective of another CPU.

### Memory Barriers

A memory barrier is a CPU instruction that enforces ordering constraints on the loads and stores that surround it. There are four fundamental types, based on which direction of reordering they prevent:

| Barrier Type | Prevents |
|---|---|
| LoadLoad | Later loads from appearing before earlier loads |
| StoreStore | Later stores from appearing before earlier stores |
| LoadStore | Later stores from appearing before earlier loads |
| StoreLoad | Later loads from appearing before earlier stores |

StoreLoad is the most expensive and the most general — it prevents all reordering across the barrier point. On x86, the `MFENCE` instruction or a `LOCK`-prefixed instruction acts as a full StoreLoad barrier. On ARM, `DMB ISH` is used. The JVM selects the appropriate instruction for the target architecture when compiling bytecode to native code.

### volatile and Barriers

The JMM specifies where barriers are inserted for `volatile` accesses. The exact rule from JSR-133 is:

- Before a volatile write: insert a StoreStore barrier (prevents earlier stores from being reordered after the volatile write) and a LoadStore barrier (prevents earlier loads from being reordered after the volatile write).
- After a volatile write: insert a StoreLoad barrier (prevents the volatile write from being reordered after subsequent loads — ensures the write is visible before any subsequent read by any thread).
- Before a volatile read: the previous StoreLoad after the write is sufficient; no extra barrier before the read is required on most architectures.
- After a volatile read: insert a LoadLoad barrier and a LoadStore barrier (prevents subsequent loads and stores from being reordered before the volatile read).

In practice, a volatile write is as expensive as a full memory fence because the StoreLoad barrier it requires is expensive. A volatile read is cheaper on strong-memory-model architectures like x86.

```java
// JVM inserts barriers around volatile accesses:

// [StoreStore + LoadStore barrier]
volatileField = value;  // volatile write
// [StoreLoad barrier]

// [LoadLoad + LoadStore barrier after the read]
int v = volatileField;  // volatile read
```

### synchronized and Barriers

`monitorenter` (the start of a synchronized block) acts as a LoadLoad and LoadStore barrier: it ensures that all subsequent loads and stores within the block see the latest values, by establishing a happens-before edge with the preceding `monitorexit` on the same monitor. `monitorexit` (the end of a synchronized block) acts as a StoreStore and StoreLoad barrier: it ensures all stores inside the block are flushed before the monitor is released, making them visible to the next thread that acquires the same monitor.

```java
synchronized (lock) {   // monitorenter → LoadLoad + LoadStore barrier
    // all reads here see latest values
    // all writes here are not reordered before the entry
    sharedState = newValue;
}                       // monitorexit → StoreStore + StoreLoad barrier
                        // sharedState is flushed to main memory
```

### Double-Checked Locking Revisited

The pre-JSR-133 broken version of double-checked locking is the most cited example of a reordering bug:

```java
// BROKEN — do not use
private static Helper instance;

public static Helper getInstance() {
    if (instance == null) {                    // (1) first check
        synchronized (Helper.class) {
            if (instance == null) {            // (2) second check
                instance = new Helper();       // (3) construction + publication
            }
        }
    }
    return instance;
}
```

Line (3) involves three conceptual steps: allocate memory, call the constructor to initialize fields, and assign the reference to `instance`. The JIT or CPU is allowed to reorder steps 2 and 3: it may write the reference to `instance` before the constructor has fully executed. A second thread performing check (1) outside the synchronized block can then see a non-null `instance` that points to a partially constructed object.

The fix is to declare `instance` as `volatile`. The StoreLoad barrier inserted after the volatile write to `instance` prevents the reordering: the constructor writes are guaranteed to complete before the reference is published.

```java
// Correct — volatile prevents the construction/publication reordering
private static volatile Helper instance;
```

Alternatively, the initialization-on-demand holder idiom uses class loading guarantees to avoid the need for `volatile` entirely:

```java
// Correct — class initialization is guaranteed to be thread-safe by the JVM
private static class Holder {
    static final Helper INSTANCE = new Helper();
}
public static Helper getInstance() { return Holder.INSTANCE; }
```

## Code Snippet

This is the classic two-thread reordering demonstration. Without barriers, both threads can observe that the other thread's flag is still 0 even after setting their own — because the store of their own flag and the load of the other's flag can be reordered.

```java
public class ReorderingDemo {

    // Without volatile, the CPU may reorder the store and the load
    // such that both threads see x=0 and y=0 simultaneously.
    // With volatile on both, ordering is enforced.
    static volatile int x = 0;
    static volatile int y = 0;

    static int readX, readY;

    public static void main(String[] args) throws InterruptedException {
        int reorderedCount = 0;
        int iterations = 100_000;

        for (int i = 0; i < iterations; i++) {
            x = 0;
            y = 0;
            readX = -1;
            readY = -1;

            Thread t1 = new Thread(() -> {
                x = 1;          // write x
                readY = y;      // read y
            }, "writer-x");

            Thread t2 = new Thread(() -> {
                y = 1;          // write y
                readX = x;      // read x
            }, "writer-y");

            t1.start();
            t2.start();
            t1.join();
            t2.join();

            // If reordering occurred: both threads read 0 before the other's write
            // was visible. This can only happen if the store of x/y was delayed
            // past the load of y/x.
            if (readX == 0 && readY == 0) {
                reorderedCount++;
            }
        }

        System.out.println("Iterations: " + iterations);
        System.out.println("Apparent reorderings observed: " + reorderedCount);
        System.out.println("With volatile on x and y, this number should be 0.");
        System.out.println("Remove volatile from x and y to observe reorderings.");
    }
}
```

With `volatile` on both `x` and `y`, the count of reorderings should be zero. Remove `volatile` and the count may rise, especially under high parallelism or on weakly-ordered hardware.

## Gotchas

### Reordering Is Not a Bug in the CPU or JVM

Reordering is an intended, specified behavior. The JMM explicitly permits it in the absence of synchronization. A program that relies on a particular execution order without using synchronization is the bug, not the reordering. Blaming the JIT or the CPU for a data race is a category error.

### The Broken Double-Checked Locking Pattern Is Still in Production Code

Despite being documented as broken since the early 2000s, the non-volatile version of double-checked locking appears frequently in legacy codebases. It appears to work because most testing occurs on x86, where the strong memory model makes the reordering unlikely to manifest. The correct fix is a single `volatile` keyword on the instance field, or switching to the holder idiom.

### StoreLoad Is the Expensive Barrier

On most hardware, preventing a store from being reordered after a load (StoreLoad) requires draining the store buffer, which is a pipeline stall. This is why volatile writes are significantly slower than ordinary writes: the StoreLoad barrier inserted after the write prevents the CPU from continuing until the store buffer is drained. Benchmarks that compare volatile to non-volatile reads often find that the write is where the cost is paid.

### Synchronized Does Not Prevent All Reordering Within the Block

Barriers inserted by `monitorenter` and `monitorexit` enforce ordering at the boundaries of the synchronized block, but instructions within the block can still be reordered by the JIT relative to each other, as long as the single-threaded result is preserved. This is safe because only one thread executes the block at a time, and the barrier at `monitorexit` ensures all writes are visible before the monitor is released.

### Volatility Is Not Transitive

If a `volatile` field holds a reference to an object, only accesses to the reference itself are subject to the volatile guarantees. The fields of the referenced object are not automatically volatile. Reading a `volatile` reference guarantees you see the most recent reference assignment, but the fields of the object it points to require their own synchronization if they are modified concurrently.

### Compiler Reordering and CPU Reordering Are Independent

It is tempting to think that preventing one type of reordering is sufficient. It is not: a memory barrier instruction prevents CPU reordering at runtime, but the barrier must also actually appear in the emitted machine code. If the JIT eliminates the barrier instruction through optimization, CPU barriers are irrelevant. The JMM requires both the compiler and the CPU to respect ordering constraints established by synchronization — and the JVM handles inserting both the JIT-level barriers and the machine-level barrier instructions.
