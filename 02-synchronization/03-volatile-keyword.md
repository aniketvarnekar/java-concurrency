# The volatile Keyword

## Overview

The `volatile` keyword is a field modifier that makes a single field directly visible across threads without using `synchronized`. When a field is declared `volatile`, the Java Memory Model (JMM) guarantees that every write to that field is immediately flushed to main memory, and every read of that field fetches the current value from main memory rather than from a CPU cache or register. This eliminates the visibility hazard for that field: a thread that writes a `volatile` field and a thread that subsequently reads it will agree on the value.

Volatile addresses a narrower problem than `synchronized`. The `synchronized` keyword provides both mutual exclusion and visibility for an entire guarded region. Volatile provides only visibility, and only for a single field. It does not provide atomicity, it does not group multiple fields into a single atomic update, and it does not prevent two threads from interleaving their reads and writes on that field. A `volatile int` subject to `counter++` from two threads is still vulnerable to lost updates because `counter++` is a three-step read-modify-write sequence that volatile does nothing to make indivisible.

The JMM formalizes volatile through happens-before edges. A write to a volatile variable happens-before every subsequent read of that same variable by any thread. This means that all writes performed by a thread before it writes a volatile field are guaranteed to be visible to any thread that reads that volatile field afterward. This property makes volatile useful for safe publication of objects, for status flags, and for one-time state transitions — cases where the requirement is visibility of a value rather than atomicity of a compound operation.

## Key Concepts

### Visibility Guarantee

Without `volatile`, the JVM is permitted to cache a field's value in a CPU register or per-core cache. A thread might execute a tight loop reading a field without ever going to main memory, and a write by another thread to main memory would never be seen. This is not a hypothetical optimization: the JIT compiler routinely hoists field reads out of loops when it can prove that the executing thread never writes the field:

```java
// WITHOUT volatile: JIT may compile the loop as:
//   if (!running) return;
//   while (true) { doWork(); }  // field is read once and cached in a register
private boolean running = true;

// WITH volatile: every iteration reads from main memory
private volatile boolean running = true;

public void run() {
    while (running) {
        doWork();
    }
}
```

Marking `running` as `volatile` ensures that each iteration of the loop fetches the current value from main memory. When another thread writes `running = false`, that write is visible to the looping thread on the very next read.

### No Atomicity

Volatile guarantees visibility but not atomicity. A volatile field can still be subject to race conditions whenever the operation on it is a compound read-modify-write:

```java
private volatile int counter = 0;

// Still a race condition: READ, then MODIFY, then WRITE are three separate operations
counter++;

// Also a race on volatile long/double in theory, though modern 64-bit JVMs
// make long and double accesses atomic on 64-bit hardware.
private volatile long timestamp = 0L;
```

The `counter++` on a `volatile int` performs three operations: a volatile read of the current value, an addition of 1, and a volatile write of the result. Between the read and the write, another thread can perform its own volatile read-modify-write, and the final result will reflect only one of the two increments. For atomic compound operations on a single variable, use `java.util.concurrent.atomic.AtomicInteger`, `AtomicLong`, and friends, which provide `incrementAndGet()` and other compound operations as single indivisible steps.

### Happens-Before via Volatile

The JMM's volatile rule states: a write to a volatile variable W happens-before every subsequent read of W. "Subsequent" means the read observes the write — the read returns the value written by W or a later write. This rule creates a happens-before edge not just for the volatile field itself but for all writes that happened-before the volatile write:

```java
private int data = 0;
private volatile boolean ready = false;

// Thread A:
data = 42;         // (1) plain write
ready = true;      // (2) volatile write -- flushes everything before it

// Thread B:
if (ready) {       // (3) volatile read -- if this sees true...
    use(data);     // (4) guaranteed to see data == 42 due to hb chain (1)hb(2)hb(3)hb(4)
}
```

Thread A's write to `data` at step (1) happens-before its write to `ready` at step (2) by the program order rule. The volatile write at (2) happens-before the volatile read at (3) by the volatile variable rule. The volatile read at (3) happens-before the use at (4) by program order. By transitivity, (1) happens-before (4), so thread B is guaranteed to see `data == 42` if it reads `ready == true`. This chain is the foundation of safe publication via volatile.

### Double-Checked Locking

Double-checked locking is an optimization that attempts to reduce the overhead of synchronization in lazy initialization. The broken version predates Java 5 and demonstrates exactly what can go wrong without the volatile guarantee:

```java
// BROKEN: do not use this pattern with Java < 5 or without volatile
public class Singleton {
    private static Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {           // first check, outside lock
            synchronized (Singleton.class) {
                if (instance == null) {   // second check, inside lock
                    instance = new Singleton(); // NOT atomic!
                }
            }
        }
        return instance;
    }
}
```

The problem is that `instance = new Singleton()` is not atomic at the JVM level. It involves allocating memory, writing the default field values, executing the constructor body, and then writing the reference to `instance`. The JVM and CPU are permitted to reorder these steps. A partially constructed `Singleton` — memory allocated, reference written to `instance`, but constructor not yet complete — can be observed by another thread that passes the first null check and returns the broken object.

The correct version uses `volatile` on the `instance` field:

```java
// CORRECT: volatile prevents reordering of the constructor and the reference write
public class Singleton {
    private static volatile Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

The `volatile` write to `instance` happens-before any subsequent volatile read of `instance`. The JMM's volatile semantics include a prohibition on reordering stores across a volatile write, so the constructor is guaranteed to complete before the reference becomes visible. The lazy initialization holder pattern (a static inner class) is often preferred because it achieves the same goal without volatile and without synchronization on the hot path.

### When to Use Volatile

Volatile is the right tool in a narrow set of circumstances. A status flag that one thread writes and others poll — such as a shutdown flag or a "data ready" flag — is the canonical use case. The flag is written once (or infrequently) and read many times, with no need for atomicity of the write because only one thread writes it.

Volatile is also correct for one-time safe publication of an immutable object. Write the fully constructed object to a volatile field and all readers are guaranteed to see a fully initialized object. For variables that undergo compound updates from multiple threads, `AtomicInteger` or `synchronized` is required instead. Volatile is not a general-purpose replacement for `synchronized`.

## Code Snippet

```java
// Demonstrates volatile for a running-flag shutdown pattern and
// correct double-checked locking for a singleton.
//
// Run: javac VolatileDemo.java && java VolatileDemo

public class VolatileDemo {

    // --- Part 1: volatile status flag for clean shutdown ---

    static class Worker implements Runnable {
        private volatile boolean running = true;
        private int workCount = 0;

        @Override
        public void run() {
            System.out.printf("[%s] starting%n", Thread.currentThread().getName());
            while (running) {
                doWork();
            }
            System.out.printf("[%s] stopped cleanly after %d units of work%n",
                    Thread.currentThread().getName(), workCount);
        }

        private void doWork() {
            workCount++;
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Called from a different thread to request shutdown
        public void stop() {
            running = false; // volatile write -- immediately visible to the worker thread
        }
    }

    // --- Part 2: correct double-checked locking with volatile ---

    static class HeavyResource {
        private static volatile HeavyResource instance; // volatile is required

        private final String data;

        private HeavyResource() {
            // Simulate expensive initialization
            data = "initialized by " + Thread.currentThread().getName();
            System.out.printf("[%s] HeavyResource constructed%n", Thread.currentThread().getName());
        }

        public static HeavyResource getInstance() {
            if (instance == null) {                      // first check -- no lock
                synchronized (HeavyResource.class) {
                    if (instance == null) {              // second check -- under lock
                        instance = new HeavyResource();  // volatile write: ctor completes before ref is visible
                    }
                }
            }
            return instance;
        }

        public String getData() { return data; }
    }

    public static void main(String[] args) throws InterruptedException {
        // Part 1: volatile flag shutdown
        System.out.println("=== Volatile flag shutdown ===");
        Worker worker = new Worker();
        Thread workerThread = new Thread(worker, "worker-thread");
        workerThread.start();

        Thread.sleep(50); // let the worker do some work
        System.out.println("[main] requesting shutdown");
        worker.stop(); // volatile write
        workerThread.join();

        // Part 2: double-checked locking
        System.out.println("\n=== Double-checked locking singleton ===");
        Runnable getter = () -> {
            HeavyResource r = HeavyResource.getInstance();
            System.out.printf("[%s] got instance: %s%n",
                    Thread.currentThread().getName(), r.getData());
        };

        Thread g1 = new Thread(getter, "getter-1");
        Thread g2 = new Thread(getter, "getter-2");
        Thread g3 = new Thread(getter, "getter-3");

        g1.start(); g2.start(); g3.start();
        g1.join();  g2.join();  g3.join();

        System.out.println("All threads got the same instance: " +
                (HeavyResource.getInstance() == HeavyResource.getInstance()));
    }
}
```

## Gotchas

**`volatile` on a reference does not make the referenced object's fields volatile.** Declaring `private volatile MyObject obj` ensures that the reference itself is visible: when one thread writes `obj = new MyObject()`, another thread reading `obj` will see the new reference. But the fields inside `MyObject` are not volatile. If multiple threads read and write `obj.field`, that access is subject to all the usual visibility and atomicity hazards unless `field` itself is volatile or accessed under a lock.

**`volatile` does not solve check-then-act races.** Even though a volatile read always fetches the latest value, the value can change between the read and the act. Reading `if (volatileFlag)` and then doing something based on that flag is still a race if another thread can write the flag in between. Volatile fixes visibility; it does not make multi-step sequences atomic.

**Marking every field `volatile` as a defensive measure causes unnecessary performance overhead.** Volatile reads and writes are more expensive than plain reads and writes because they prevent certain CPU and compiler optimizations. Cache coherence traffic increases on multi-socket systems. Apply volatile only where visibility is the specific problem and atomicity is not required.

**`volatile` does not prevent reordering of non-volatile operations relative to each other.** The JMM guarantees that the volatile write is not reordered with respect to operations before it (in program order) and the volatile read is not reordered with respect to operations after it. But non-volatile reads and writes can still be reordered relative to each other as long as that reordering does not cross a volatile access barrier in the wrong direction. Code that relies on a specific ordering of two plain fields guarded only by a volatile of a third field must reason carefully about which reorderings the JMM does and does not prevent.

**The broken double-checked locking pattern works "by accident" on some JVM implementations.** On some 32-bit hotspot JVM versions with certain GC configurations, the broken pattern never actually fails because the object allocation and constructor are not reordered. Developers sometimes test the pattern, see it working, and conclude it is safe. It is not safe by specification, and it can fail on any JVM that conforms to the pre-Java-5 memory model or on future JVM versions.

**Using `volatile` for mutable state shared between more than two threads requires careful analysis.** Volatile is well understood for a single-writer, multiple-reader pattern. When multiple threads write a volatile field, each write is individually visible, but there is no ordering guarantee between the writes themselves. Two writes from two different threads can appear to readers in different orders depending on the reader's position in the happens-before graph. If the order of writes matters, `synchronized` or explicit locks are needed.
