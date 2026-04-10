# Race Conditions

## Overview

A race condition is a defect in which the correctness of a program depends on the relative timing or interleaving of operations in two or more threads. The program produces different results depending on which thread executes first, how far each thread advances before being preempted, and what state it reads when it resumes. Because thread scheduling is determined by the operating system and JVM, that timing is non-deterministic from the programmer's perspective. A race condition may produce correct output nine thousand times and then silently corrupt data on the ten-thousandth run, making it one of the hardest categories of bug to reproduce and diagnose.

The underlying cause is always the same: two or more threads access shared mutable state, and at least one of those accesses is a write. If every access were a read, the threads could not interfere with each other. If the state were not shared — each thread had its own private copy — there would be no conflict. If the state were immutable — no writes ever occurred after construction — reads would always see a consistent snapshot. The hazard arises precisely when multiple threads can both read and write the same memory location without any coordination.

Java's memory model does not guarantee that a write by one thread is immediately visible to another. The JVM is permitted to cache variable values in registers or CPU-local caches, and the processor and compiler are permitted to reorder operations for efficiency. Without explicit synchronization, one thread's write may never propagate to another thread, or may propagate in a different order than the programmer intended. This visibility problem compounds the ordering problem: a thread can observe a partially constructed state, a stale value, or a value that no longer reflects the complete sequence of writes.

Two patterns account for nearly every race condition encountered in practice. The first is check-then-act, where a thread reads a condition and then acts on the assumption that the condition still holds, when in fact another thread may have changed it between the read and the act. The second is read-modify-write, where a thread reads a value, computes a new value from it, and writes the result back, but another thread may have interleaved its own read-modify-write in between. Both patterns share the same structure: a sequence of operations that must be executed as an indivisible unit is instead executed as separate steps that can be interrupted.

## Key Concepts

### Check-Then-Act

The check-then-act pattern occurs when a program reads a condition, decides to act based on what it read, and then acts — but another thread is allowed to run between the read and the act. The classic example is lazy initialization:

```java
// Unsafe lazy initialization -- race condition on instance field
public class Registry {
    private ExpensiveResource resource = null;

    public ExpensiveResource getResource() {
        if (resource == null) {           // (1) Thread A checks: null, proceeds
            resource = new ExpensiveResource(); // (2) Thread B also checked null before A wrote
        }                                 //     Both threads now create a new instance
        return resource;
    }
}
```

Thread A evaluates `resource == null`, finds it true, and is preempted before executing the assignment. Thread B then evaluates `resource == null`, also finds it true, creates an instance, and returns. Thread A resumes, creates a second instance, overwrites thread B's value, and returns a different object. Any state stored in thread B's instance is lost. The race window is the span of instructions between the check and the act. Making the check and the act atomic eliminates the race.

### Read-Modify-Write

The read-modify-write pattern occurs when a thread reads a value, derives a new value from it, and writes the result back. The canonical example is incrementing a counter:

```java
// Each of these three steps is a separate machine-level operation
counter++;
// Equivalent to:
//   int temp = counter;   // READ
//   temp = temp + 1;      // MODIFY
//   counter = temp;       // WRITE
```

The Java Language Specification does not make `++` on an `int` field an atomic operation. The JVM may compile it to a load, an add, and a store — three distinct bytecode instructions. A context switch can occur between any two of them. If two threads both read the value 42, both compute 43, and both write 43, the net effect of two increments is a single increment. One update is lost.

### Lost Update

The lost update is what happens when the read-modify-write race manifests fully. Its anatomy is easiest to see in a timeline:

```
Time    Thread A                        Thread B
----    --------                        --------
  1     READ  counter -> 42
  2                                     READ  counter -> 42
  3     MODIFY: 42 + 1 = 43
  4                                     MODIFY: 42 + 1 = 43
  5     WRITE counter <- 43
  6                                     WRITE counter <- 43

Result: counter == 43, but two increments were performed.
Expected: counter == 44.
```

Thread B's write at step 6 overwrites thread A's write at step 5. Thread A's entire increment — a real operation that consumed CPU time — is silently discarded. In a counter scenario the loss is one increment. In a financial system the loss could be a credit or debit. The structure is identical regardless of the domain.

### Compound Actions

A compound action is a sequence of individual operations that must be executed atomically to be correct. The critical insight is that making each individual operation atomic does not make the compound action atomic.

Consider a map that is individually thread-safe (such as `ConcurrentHashMap`). Each individual operation — `get`, `put`, `containsKey` — is atomic. But a compound action like "put if absent" is not:

```java
// Still a race condition even with a thread-safe map
if (!map.containsKey(key)) {      // atomic check
    map.put(key, computeValue());  // atomic put
}
// Between these two atomic operations, another thread can insert the same key.
```

The check and the put are each atomic in isolation. The compound action of "check then conditionally put" is not. The correct solution is either to use `map.putIfAbsent(key, value)`, which is itself a single atomic operation, or to hold an external lock across both operations. `ConcurrentHashMap` provides `putIfAbsent`, `computeIfAbsent`, and `merge` precisely to eliminate this class of race condition.

## Gotchas

**Correct output on one machine does not mean the code is correct.** A program with a race condition may produce correct results every time on a single-core machine with a particular JVM and OS, and then fail regularly on a dual-core machine or a different JVM version. The absence of observed failures is not a proof of correctness. Race conditions must be reasoned about structurally, not tested away empirically.

**`volatile` does not fix read-modify-write races.** A common misconception is that marking a field `volatile` makes operations on it atomic. Volatile guarantees visibility — a write is immediately flushed to main memory — but it does not group the read, modify, and write into an atomic unit. A `volatile int counter` subject to `counter++` from two threads is still vulnerable to lost updates. The correct tool is `AtomicInteger.incrementAndGet()` or synchronization.

**Thread-safe collections eliminate collection-level races, not compound races.** Using `ConcurrentHashMap` or `Collections.synchronizedList` makes individual method calls safe. Any sequence of two or more calls that must be treated as a single unit still requires external synchronization or an atomic compound method provided by the collection. The "check then put" pattern on a `ConcurrentHashMap` is still a race.

**Races on `long` and `double` have an additional dimension.** On 32-bit JVMs, reads and writes of 64-bit primitives (`long` and `double`) are not guaranteed to be atomic. The JVM may perform them as two 32-bit operations. One thread could read the high 32 bits written by one thread and the low 32 bits written by another, producing a value that neither thread ever wrote. Declaring such fields `volatile` restores the atomicity guarantee for the read and write individually, though not for read-modify-write compound operations.

**The race window can be extremely small and still cause failures.** Developers sometimes dismiss a race condition as "too unlikely to matter in practice." In a tight loop executing millions of times per second, even a microsecond race window produces frequent collisions. At scale, rare events become routine. A race condition that manifests once per million executions becomes a daily production incident in a high-throughput system.

**Races can corrupt data in ways that are not immediately visible.** A counter that is 1 less than expected is easy to detect. Other races produce corrupted object graphs, partially initialized objects that pass null checks, or state that is internally inconsistent in ways that cause failures far removed in time and code from the race itself. Debugging such failures is extraordinarily difficult because by the time the symptom appears, the cause is gone.
