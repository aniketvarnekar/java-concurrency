# Java Memory Model

## Overview

Modern computers do not execute programs the way their source code reads. CPUs reorder instructions to maximize pipeline utilization, compilers move code around to eliminate redundant operations, and each processor core has multiple levels of cache that may hold stale copies of memory. In a single-threaded program these optimizations are invisible because the CPU and compiler maintain the illusion of sequential execution for the thread doing the work. In a multi-threaded program, however, each thread observes the memory system from its own perspective, and without a formal contract governing what is and is not guaranteed, the behavior of any concurrent program would be implementation-defined.

The Java Memory Model (JMM), specified in Chapter 17 of the Java Language Specification, is that contract. It defines the conditions under which a write to a variable by one thread is guaranteed to be visible to a read of that same variable by another thread. Crucially, the JMM is not a description of hardware — it is a programming model. It tells developers what they can rely on when they follow certain rules (using synchronization primitives correctly), and it gives JVM implementors and CPU manufacturers freedom to optimize in any way that preserves those guarantees.

The JMM was significantly revised with Java 5 (JSR-133) to close holes in the original specification that made programs with data races behave in deeply surprising ways, including the broken double-checked locking idiom. The current model is based on a happens-before relationship: if one action happens-before another, the first is guaranteed to be visible to and ordered before the second. Happens-before rules are covered in detail in `02-synchronization/04-happens-before.md`.

Understanding the JMM is not about memorizing every rule. It is about building a mental model of what the hardware can do in the absence of synchronization, and recognizing which language constructs establish the guarantees you need. Most bugs are not subtle JMM violations — they are simply missing synchronization. The JMM tells you precisely why missing synchronization is dangerous.

## Key Concepts

### Main Memory vs Working Memory

The JMM defines an abstract memory architecture in which each thread has its own working memory separate from main memory. Main memory holds the master copy of all variables (instance fields, static fields, and array elements). Working memory is a thread-local abstraction that covers CPU registers, store buffers, and cache lines — the actual mechanisms vary by hardware and JVM implementation.

When a thread reads a variable, it may read from its working memory rather than from main memory. When it writes, the write may go to working memory first and be flushed to main memory later — or never, if the JVM determines it is not required. This means one thread's write is not automatically visible to another thread's read of the same variable.

```
Thread 1                        Thread 2
+-----------+                   +-----------+
| Working   |                   | Working   |
| Memory    |                   | Memory    |
| [flag=0]  |                   | [flag=0]  |
+-----+-----+                   +-----+-----+
      |   flush/load                  |
      v                               v
+---------------------------------------------+
|              Main Memory                    |
|              [flag=1]                       |
+---------------------------------------------+
```

In the diagram above, Thread 1 has written `flag=1` to main memory, but Thread 2 still has `flag=0` in its working memory and reads that stale value. Without synchronization, the JMM does not require Thread 2 to see the update.

### The JMM Contract

The JMM is a two-sided contract. The developer's side: if you use synchronization primitives correctly (volatile, synchronized, locks from `java.util.concurrent.locks`, actions like thread start and join), you get specific ordering and visibility guarantees. The JVM and CPU side: as long as those guarantees are honored, the runtime may apply any optimization — reorder instructions, cache values in registers, speculate, elide locks — that does not violate the contract from the perspective of any thread following the rules.

A data race occurs when two threads access the same variable without synchronization, and at least one of the accesses is a write. The JMM says programs with data races have "unspecified semantics" — not just "probably wrong" but formally undefined. This is the core reason to use synchronization: to eliminate data races and step back into the well-defined world the JMM describes.

### Atomicity, Visibility, Ordering

The JMM addresses three distinct properties of memory operations, and it is important to treat them as separate concerns.

Atomicity means that an operation completes as a single, indivisible step with respect to other threads. No thread can observe an intermediate state. In Java, reads and writes of most primitive types (boolean, byte, short, int, char, float, and object references on 64-bit JVMs) are atomic by specification. Reads and writes of long and double are not atomic on 32-bit JVMs without volatile.

Visibility means that a write performed by one thread will be seen by reads in another thread. Even if a write is atomic, it may not be visible — the writing thread's store may sit in a store buffer and never be flushed. Visibility requires explicit synchronization actions.

Ordering means that the sequence in which memory operations appear to execute is constrained. In a single thread, operations appear to execute in program order. Across threads, without synchronization, there is no guaranteed ordering. Volatile and synchronized establish ordering constraints via happens-before.

### JMM vs Physical Hardware

The JMM is a programming model, not a hardware specification. Different CPU architectures have different memory models at the hardware level. x86 has a relatively strong memory model: it guarantees that stores are seen in order by all CPUs (Total Store Order, or TSO), which means many JMM requirements are satisfied "for free" on x86. ARM and PowerPC have weaker models: both stores and loads can be reordered, requiring the JVM to insert more explicit memory barrier instructions to implement JMM guarantees.

```
           CPU 0                      CPU 1
    +----------------+         +----------------+
    |   Registers    |         |   Registers    |
    +-------+--------+         +--------+-------+
            |                           |
    +-------+--------+         +--------+-------+
    |   L1 Cache     |         |   L1 Cache     |
    +-------+--------+         +--------+-------+
            |                           |
    +-------+--------+         +--------+-------+
    |   L2 Cache     |         |   L2 Cache     |
    +-------+---+----+         +----+---+-------+
                |                   |
         +------+-----------+-------+------+
         |         L3 Cache (shared)       |
         +------------------+--------------+
                            |
                 +----------+----------+
                 |      Main Memory    |
                 +---------------------+
```

This architecture means a write by CPU 0 travels: register → L1 → L2 → L3 → main memory. A read by CPU 1 travels the same chain in reverse. Without cache coherence protocols and memory barriers, CPU 1 might read a value that is still sitting in CPU 0's L1 or L2 cache. The JVM uses CPU-specific barrier instructions (MFENCE, LOCK prefix on x86; DMB on ARM) to force coherence when the JMM requires it.

## Gotchas

### The JMM Does Not Define Timing, Only Ordering

The JMM says nothing about when a write will become visible — only whether it is guaranteed to be visible at all. Without synchronization, a write might be visible immediately on one run and never visible on another. This makes visibility bugs intermittent and hard to reproduce in testing.

### Absence of Bugs in Testing Does Not Mean Correctness

A program with data races may appear to work correctly for years, particularly on x86 hardware which has a strong memory model and tends to "paper over" races that would crash on ARM. Deploying the same JVM code on an ARM server or a newer JVM with aggressive JIT optimization can expose races that testing never caught.

### The JMM Applies to Fields, Not Local Variables

Local variables (primitives and references stored on the stack frame) are not shared between threads and are always thread-safe. The JMM's visibility rules apply to heap-resident state: instance fields, static fields, and array elements. Confusion often arises when developers assume a local variable is thread-safe but it actually aliases a shared field.

### Working Memory Is an Abstraction

The JMM's "working memory" does not correspond to any single hardware component. On a real machine it covers whatever combination of registers, store buffers, and cache lines the CPU uses at a given moment. The abstraction is pedagogically useful but should not be taken as a literal description of memory hardware.

### Happens-Before Is Not Causality

Happens-before in the JMM is a formal relation between program actions, not a statement about real time. Action A can happen-before action B even if B completes before A in wall-clock time, as long as the JMM ordering rules are respected. This subtlety matters when reasoning about programs that use non-blocking synchronization.

### Volatile Does Not Make Compound Operations Safe

A common mistake is to assume that `volatile` on a variable makes all operations on it thread-safe. It gives visibility and prevents reordering around the variable, but it does not make compound operations such as `i++` (read-modify-write) atomic. For compound operations, use `java.util.concurrent.atomic` classes.
