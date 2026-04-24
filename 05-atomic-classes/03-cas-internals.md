# 03 — CAS Internals

## Overview

Compare-And-Swap (CAS) is a hardware-level instruction that atomically performs the following three-step operation: read the current value at a memory address, compare it to an expected value, and if they match, write a new value to that address. The entire sequence happens as a single, indivisible operation from the perspective of every processor in the system. No other processor can observe the memory location in an intermediate state between the read and the write.

On x86 and x86-64 processors, CAS is implemented by the `CMPXCHG` instruction combined with the `LOCK` prefix, which asserts exclusive ownership of the cache line containing the target address for the duration of the instruction. On ARM-based architectures (used in most mobile devices and Apple Silicon), CAS is implemented using a load-exclusive / store-exclusive (LDXR/STXR) instruction pair, which achieves similar semantics through a reservation mechanism. The Java Virtual Machine emits whichever instruction sequence is appropriate for the current platform.

In Java, CAS was historically exposed to library authors through `sun.misc.Unsafe`, which is internal API never intended for direct use by application code. Java 9 introduced `java.lang.invoke.VarHandle` as the official, public replacement. `VarHandle` provides access to the same hardware CAS operations with proper memory ordering semantics expressed through Java's memory model. The `java.util.concurrent.atomic` classes are built on top of `VarHandle` (or `Unsafe` in older JDK versions) so application code rarely needs to interact with either directly.

## Key Concepts

### The cmpxchg Instruction

The x86 `LOCK CMPXCHG` instruction operates as follows: the processor places the expected value in the `EAX`/`RAX` register and the new value in a second register. The instruction atomically compares `EAX` with the memory operand. If they are equal, the memory operand is replaced with the new value and the zero flag (ZF) is set. If they are not equal, the current memory value is loaded into `EAX` and ZF is cleared.

```
; Pseudocode for LOCK CMPXCHG [mem], new
lock:
    if [mem] == EAX:
        [mem] = new
        ZF = 1
    else:
        EAX = [mem]
        ZF = 0
```

The `LOCK` prefix forces the processor to acquire exclusive ownership of the cache line. On modern x86 chips, if the target address is already in the L1 cache of the executing core, this does not always result in a bus lock — the CPU handles it through cache coherence protocols (MESI). Under high contention where multiple cores are contending for the same cache line, the `LOCK` prefix causes significant cache invalidation traffic.

### CAS Loop (Spin Loop)

A single CAS call can fail if another thread modified the value between the read and the CAS. The solution is to retry in a loop until the CAS succeeds. This pattern is called a CAS loop or spin loop, and it is the foundation of all lock-free algorithms.

```java
// Manual CAS loop equivalent to AtomicInteger.incrementAndGet()
AtomicInteger ai = new AtomicInteger(0);

int newValue;
int current;
do {
    current  = ai.get();          // 1. Read current value
    newValue = current + 1;       // 2. Compute desired new value
} while (!ai.compareAndSet(current, newValue)); // 3. CAS; retry if it fails
// newValue is now the successfully written value
```

The loop structure is: read, compute, attempt CAS, retry on failure. Each iteration reads a fresh value from the atomic variable, so retries always work with current data. The loop terminates when no other thread interferes during a particular iteration.

The ASCII diagram below shows the flow for two threads competing:

```
Thread A                        Thread B
--------                        --------
read: current = 5
compute: new = 6
                                read: current = 5
                                compute: new = 6
                                CAS(5, 6) => SUCCESS => value is 6
CAS(5, 6) => FAIL (value is 6, not 5)
read: current = 6
compute: new = 7
CAS(6, 7) => SUCCESS => value is 7
```

### Why CAS Can Fail

A CAS fails when the value at the target address does not equal the expected value at the moment the instruction executes. This happens because another thread successfully modified the variable between the time the first thread read it and the time the first thread attempted the CAS. The failing thread has not lost any data — it simply needs to re-read and retry.

Under low contention, each thread rarely retries more than once. Under high contention — many threads targeting the same variable — threads can spin for many iterations. This burns CPU cycles without doing useful work and can lead to thread starvation in extreme cases. `LongAdder` addresses this by distributing updates across multiple cells (see `04-longadder-vs-atomiclong.md`).

### ABA Problem in Depth

The ABA problem arises in pointer-based data structures where a CAS succeeds even though the state represented by the value has changed and reverted. The canonical example is a lock-free stack.

Initial state:

```
head -> [A] -> [B] -> [C] -> null
```

Thread 1 prepares to pop A: it reads `head = A` and computes `newHead = A.next = B`.

Thread 1 is preempted. Thread 2 runs:

```
Thread 2: pop A  => head -> [B] -> [C] -> null
Thread 2: pop B  => head -> [C] -> null
Thread 2: push A => head -> [A] -> [C] -> null  (A.next is set to C, but
                                                  from Thread 1's perspective
                                                  it still cached A.next = B)
```

Thread 1 resumes and executes `CAS(head, A, newHead)`. The current head is A (it was pushed back), so the CAS succeeds. Thread 1 sets `head = B`. But B is no longer in the stack — it was popped and the memory may have been recycled. The stack is now corrupted:

```
head -> [B] -> (stale)
```

The fix — using `AtomicStampedReference` — makes the CAS check both the pointer and a version counter. Thread 1 read `(A, stamp=0)`. Thread 2's push sets `stamp=3`. Thread 1's CAS expects `stamp=0` but finds `stamp=3`, so it fails and retries, observing the correct current state.

### VarHandle (Java 9+)

`VarHandle` is the modern, officially supported API for low-level atomic operations in Java. It replaces `sun.misc.Unsafe` for CAS on fields and array elements.

```java
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

class Counter {
    volatile int value;

    private static final VarHandle VALUE;
    static {
        try {
            VALUE = MethodHandles.lookup()
                    .findVarHandle(Counter.class, "value", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    int incrementAndGet() {
        int current, next;
        do {
            current = (int) VALUE.getVolatile(this);
            next    = current + 1;
        } while (!VALUE.compareAndSet(this, current, next));
        return next;
    }
}
```

`VarHandle` supports the following access modes relevant to concurrency:

| Mode | Meaning |
|---|---|
| `get()` / `set()` | Plain read/write (no fence) |
| `getVolatile()` / `setVolatile()` | Full sequential consistency |
| `getAcquire()` / `setRelease()` | Acquire/release ordering (weaker than volatile, no StoreLoad fence) |
| `getOpaque()` / `setOpaque()` | Atomicity without ordering (single variable coherence) |
| `compareAndSet(obj, expected, new)` | Full CAS with volatile semantics |
| `compareAndExchange(obj, expected, new)` | CAS returning the witness value |
| `getAndAdd(obj, delta)` | Atomic add |
| `getAndSet(obj, new)` | Atomic swap |

## Gotchas

### High contention causes CAS loops to become spin loops that waste CPU

Under heavy contention, many threads compete for the same cache line. Most will fail their CAS and loop. Each iteration reads from a cache line that is being invalidated by other cores, which triggers cache coherence traffic. CPU utilization climbs while useful throughput falls. If you observe high CPU with low actual work throughput, CAS contention may be the cause. Use `LongAdder` for counters or redesign to reduce contention.

### A successful CAS does not mean no other thread ran between your read and your CAS

CAS tells you that the value you read is still the value in memory at the moment of the CAS. It tells you nothing about what happened to other shared state during the interval. If your algorithm assumes that no concurrent activity occurred — rather than just that the specific CAS variable was not modified — a successful CAS provides no such guarantee.

### The ABA problem is not theoretical

The ABA problem is real in any algorithm that makes decisions based on identity (reference or primitive equality) rather than version or causal ordering. Any lock-free data structure that involves pointer manipulation — stacks, queues, linked lists — must consider ABA. Adding `AtomicStampedReference` is the standard fix. Alternatively, hazard pointers or epoch-based reclamation can prevent ABA by ensuring that nodes are not reused while any thread holds a reference to them, but these techniques require significant extra bookkeeping.

### VarHandle access modes must match your memory ordering requirements

Choosing the wrong access mode silently weakens your memory guarantees. Using `get()` (plain read) instead of `getVolatile()` on a field that must be visible across threads is a correctness bug on non-x86 architectures. The acquire/release modes are correct for paired producer-consumer patterns but insufficient when you need a total order across all threads. Using `setRelease` where `setVolatile` is required is a bug that may only appear on ARM hardware.

### Unsafe.compareAndSwapInt requires an exact field offset

Code that uses `sun.misc.Unsafe` must compute the field offset at class load time using `Unsafe.objectFieldOffset`. If the field is renamed or moved, the offset computation at runtime silently targets the wrong memory location. This is one reason `VarHandle` was introduced: it identifies fields by name and type at lookup time, catching mismatches at initialization rather than at runtime.

### Retry loops are not fair

A CAS loop has no fairness guarantee. Under sustained high contention, one thread may succeed on every iteration while another retries indefinitely. Java's `synchronized` and `ReentrantLock` (in fair mode) provide ordering guarantees that a raw CAS loop does not. If fairness matters — for example, in a work queue where task ordering must be respected — a lock with fair ordering is more appropriate than a CAS loop.
