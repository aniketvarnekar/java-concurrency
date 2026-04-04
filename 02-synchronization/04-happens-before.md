# Happens-Before

## Overview

The Java Memory Model (JMM), specified in chapter 17 of the Java Language Specification, defines when one thread's writes are guaranteed to be visible to another thread's reads. The central concept is the happens-before relation. If action A happens-before action B, then all effects of A — every write to every variable that A performed — are guaranteed to be visible to B when B executes. The happens-before relation provides the formal vocabulary for reasoning about visibility in concurrent Java programs.

Happens-before is not a statement about time. A happens-before B does not necessarily mean A executes before B in wall-clock time. It means that if B observes any of A's writes, then B is also guaranteed to observe all of A's writes and all writes that happened-before A. The relation is about visibility guarantees, not scheduling order. Two actions with no happens-before relationship between them are said to be concurrent with respect to the memory model, and a read of a field written by a concurrent write may observe any value that was written to that field — including a stale value from before the program started.

The JMM defines eight specific rules that establish happens-before edges between actions. These rules compose via transitivity: if A happens-before B and B happens-before C, then A happens-before C. Real programs build chains of happens-before edges through a combination of these rules, and reasoning about visibility in a concurrent program is the exercise of tracing those chains. If you can find a chain of happens-before edges from a write to a read, the read is guaranteed to see the write. If no such chain exists, the read may or may not see the write — the JMM makes no promise either way.

## Key Concepts

### Program Order Rule

Within a single thread, every action happens-before every subsequent action in program order. This is the most fundamental rule: a thread's own operations are always seen in order by that thread.

```java
// Within a single thread:
int x = 5;      // (A)
int y = x + 1;  // (B)
// A happens-before B: B is guaranteed to see x == 5.
// This is trivially true and is what programmers already expect.
```

This rule does not constrain what other threads see. It only says that within the executing thread, the operations appear to execute in program order. The JIT can reorder them internally, but the net effect observed by the executing thread must be consistent with program order.

### Monitor Lock Rule

An unlock on a monitor happens-before every subsequent lock on that same monitor. "Subsequent" means a lock that is acquired after the unlock — in the sense that the lock operation observes the unlock having occurred.

```java
class SharedData {
    private final Object lock = new Object();
    private int value = 0;

    public void writer() {
        synchronized (lock) {
            value = 42;
        } // UNLOCK: all writes before this unlock (value = 42) happen-before the next lock on `lock`
    }

    public void reader() {
        synchronized (lock) { // LOCK: happens-after the most recent unlock on `lock`
            System.out.println(value); // guaranteed to see 42 if writer ran first
        }
    }
}
```

This is why `synchronized` provides a complete memory visibility guarantee. The unlock at the end of the synchronized block establishes a happens-before edge to any subsequent lock on the same monitor. Any thread that acquires the lock after it is released is guaranteed to see all writes made before the release.

### Volatile Variable Rule

A write to a volatile variable happens-before every subsequent read of that same variable. This is the rule that gives `volatile` its visibility guarantee.

```java
private int data = 0;
private volatile boolean ready = false;

// Thread A:
data = 99;       // (1)
ready = true;    // (2) volatile write

// Thread B:
while (!ready);  // (3) volatile read -- spins until it sees true
use(data);       // (4)

// (1) hb (2) by program order rule
// (2) hb (3) by volatile variable rule (the read that sees the write)
// (3) hb (4) by program order rule
// Therefore (1) hb (4): B sees data == 99 when it exits the loop
```

The volatile write at (2) acts as a publication barrier. Everything written before the volatile write is guaranteed to be visible to any thread that subsequently reads the volatile variable and observes the written value.

### Thread Start Rule

`Thread.start()` happens-before any action in the started thread. All writes performed by the starting thread before calling `start()` are guaranteed to be visible to the new thread when it begins executing.

```java
int[] sharedData = new int[10];

// Main thread sets up data before starting the worker:
for (int i = 0; i < sharedData.length; i++) {
    sharedData[i] = i * i; // These writes happen-before Thread.start()
}

Thread worker = new Thread(() -> {
    // Thread.start() happens-before this code runs.
    // Therefore: worker is guaranteed to see all values written to sharedData above.
    System.out.println(sharedData[3]); // guaranteed to print 9
}, "data-worker");

worker.start(); // hb: all above writes happen-before anything in worker
```

This rule eliminates the need to synchronize on setup data that is written before a thread is started and only read (not written) by that thread afterward.

### Thread Join Rule

All actions in a thread happen-before `Thread.join()` returns in the joining thread. When a thread calls `join()` on another thread and `join()` returns, the joining thread is guaranteed to see all writes that the joined thread performed.

```java
int[] result = new int[1];

Thread computer = new Thread(() -> {
    result[0] = expensiveComputation(); // happens-before join() returns
}, "computer-thread");

computer.start();
computer.join(); // blocks until computer-thread finishes
                 // join() returning hb after all of computer-thread's actions

System.out.println(result[0]); // guaranteed to see the computed value
```

The join rule is the mirror of the start rule. Start ensures the new thread sees setup data; join ensures the joining thread sees the new thread's results.

### Thread Interruption Rule

A call to `Thread.interrupt()` happens-before the interrupted thread detects the interrupt — whether by catching `InterruptedException`, checking `Thread.interrupted()`, or checking `isInterrupted()`.

```java
volatile int cancelReason = 0; // using volatile for illustration

Thread worker = new Thread(() -> {
    try {
        while (!Thread.interrupted()) {
            doWork();
        }
    } catch (InterruptedException e) {
        // The call to interrupt() happens-before reaching here.
        // Any writes performed before interrupt() are visible here.
        System.out.println("Interrupted, cancel reason: " + cancelReason);
    }
}, "interruptible-worker");

worker.start();

cancelReason = 42;      // write before interrupt
worker.interrupt();     // happens-before worker detects interrupt
                        // but cancelReason needs its own hb chain to be visible
                        // use volatile or synchronized for cancelReason too
```

This rule guarantees that a thread detecting its own interruption can rely on visibility of writes that happened before the interrupt was sent. However, communicating additional data along with the interrupt still requires a separate happens-before chain for that data — typically volatile or synchronized.

### Finalizer Rule

The completion of a constructor for an object happens-before the start of that object's finalizer. This ensures the finalizer always sees the fully constructed state of the object.

```java
class Resource {
    private final String name;

    Resource(String name) {
        this.name = name;
        // Constructor completes here: happens-before finalize()
    }

    @Override
    protected void finalize() throws Throwable {
        // Guaranteed to see name as set in the constructor.
        System.out.println("Finalizing resource: " + name);
    }
}
```

This rule is primarily relevant to authors of classes that rely on finalization for cleanup. The practical takeaway is that a finalizer can safely read fields set in the constructor.

### Transitivity

If A happens-before B, and B happens-before C, then A happens-before C. Transitivity is what makes chains of synchronization actions work. Individual rules establish happens-before edges between adjacent operations; transitivity propagates those edges across the chain.

```java
// A chain: program order + volatile + program order
int value = 0;
volatile boolean published = false;

// Thread A:
value = 100;       // (A1) -- hb (A2) by program order
published = true;  // (A2) -- hb (B1) by volatile variable rule (when B reads true)

// Thread B:
if (published) {   // (B1) -- hb (B2) by program order
    use(value);    // (B2) -- guaranteed to see value == 100 by transitivity:
                   //         A1 hb A2 hb B1 hb B2, therefore A1 hb B2
}
```

Transitivity is the engine that makes composite synchronization strategies correct. Every reasoning exercise in concurrent Java ultimately reduces to tracing a happens-before chain from the write to the read.

## Code Snippet

```java
// Demonstrates safe publication via volatile and the happens-before chain that
// guarantees correct visibility from Thread A (publisher) to Thread B (subscriber).
//
// Run: javac HappensBeforeDemo.java && java HappensBeforeDemo

public class HappensBeforeDemo {

    // Configuration data set up by the main thread before start().
    static int[] config;                   // written before Thread.start() -- start rule

    // Computed result shared from worker to main via join().
    static int computedResult = 0;         // join rule ensures main sees this after join()

    // One-time publication via volatile.
    static volatile boolean dataReady = false;  // volatile rule
    static int payload = 0;                     // published safely via volatile flag

    public static void main(String[] args) throws InterruptedException {

        // --- Thread Start Rule ---
        System.out.println("=== Thread Start Rule ===");
        config = new int[]{1, 2, 3, 4, 5}; // written before start() -- visible to worker
        Thread startRuleWorker = new Thread(() -> {
            // Thread.start() hb this action: config is fully visible here.
            int sum = 0;
            for (int v : config) sum += v;
            System.out.printf("[%s] sum of config = %d (expected 15)%n",
                    Thread.currentThread().getName(), sum);
        }, "start-rule-worker");
        startRuleWorker.start();
        startRuleWorker.join();

        // --- Thread Join Rule ---
        System.out.println("\n=== Thread Join Rule ===");
        Thread joinRuleWorker = new Thread(() -> {
            computedResult = 42; // all actions in this thread hb join() returning
            System.out.printf("[%s] wrote computedResult = 42%n",
                    Thread.currentThread().getName());
        }, "join-rule-worker");
        joinRuleWorker.start();
        joinRuleWorker.join(); // all of joinRuleWorker's writes are visible after this
        System.out.printf("[main] computedResult = %d (expected 42)%n", computedResult);

        // --- Volatile Variable Rule (with transitivity) ---
        System.out.println("\n=== Volatile Variable Rule ===");

        Thread publisher = new Thread(() -> {
            payload = 999;           // (1) program order hb (2)
            dataReady = true;        // (2) volatile write hb next volatile read that sees true
            System.out.printf("[%s] published payload=%d via volatile flag%n",
                    Thread.currentThread().getName(), payload);
        }, "publisher");

        Thread subscriber = new Thread(() -> {
            while (!dataReady) {     // (3) volatile read -- spins until true
                Thread.yield();
            }
            // (3) hb (4) by program order; (2) hb (3) by volatile rule; (1) hb (2) by prog order
            // Transitivity: (1) hb (4) -- payload == 999 is guaranteed visible here.
            System.out.printf("[%s] read payload=%d (expected 999)%n",
                    Thread.currentThread().getName(), payload);  // (4)
        }, "subscriber");

        subscriber.start();
        publisher.start();
        publisher.join();
        subscriber.join();

        System.out.println("\nAll happens-before guarantees verified.");
    }
}
```

## Gotchas

**Happens-before is not the same as "executes first in time."** Thread A writing a value before thread B reads it in wall-clock time does not guarantee B sees A's write. The only guarantee comes from a happens-before chain. Code that relies on the assumption that a write "should" be visible because it happened earlier in time has a latent bug that may not manifest for years.

**The absence of a happens-before chain means the read may see any value, including a stale one.** When two actions are concurrent with respect to the JMM — no happens-before chain connects them — a read may observe the write, may observe an older value, or may observe an intermediate value for 64-bit types on 32-bit JVMs. The JMM does not require the read to see the write, but it does not forbid it either. Code that "seems to work" without synchronization is relying on this permissive behavior, which can change with any JVM update, hardware change, or change in thread scheduling.

**Transitivity requires a continuous chain.** If A happens-before B and C happens-before D, it does not follow that A happens-before D unless there is also a happens-before edge from B to C. Separate chains do not combine automatically. Reasoning about visibility requires tracing an unbroken chain of edges from the specific write to the specific read.

**The monitor lock rule requires the same monitor object.** An unlock on monitor M happens-before a lock on monitor M. An unlock on monitor M1 does not happen-before a lock on monitor M2, even if M1 and M2 are different locks protecting the same shared state. Two synchronized methods on the same object use the same monitor (the object's). Two synchronized methods on different objects use different monitors and establish no happens-before relationship with each other.

**The volatile variable rule requires reading the same variable.** A write to `volatile boolean flagA` happens-before a read of `flagA`. It does not happen-before a read of `volatile boolean flagB`, even if flagB is always written immediately after flagA. Happens-before chains must be traced through specific variable reads and writes, not through temporal proximity.

**Happens-before does not prevent all races; it prevents visibility races.** Even with a complete happens-before chain from every write to every read, two threads can still race if they both write the same variable without mutual exclusion. Happens-before guarantees that reads see a specific write; it does not guarantee that writes are atomic or non-overlapping. Full thread safety for mutable shared state requires both a happens-before chain (for visibility) and mutual exclusion (for atomicity of compound operations).
