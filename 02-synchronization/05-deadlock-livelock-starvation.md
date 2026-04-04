# Deadlock, Livelock, and Starvation

## Overview

Deadlock, livelock, and starvation are three distinct failure modes in which threads fail to make useful progress. They share a common theme — threads that should be running are unable to do so — but their causes and remedies differ. Deadlock is the most severe: threads block permanently, waiting for resources they will never obtain. Livelock is more subtle: threads remain active and consume CPU time, but the actions they take prevent any actual progress. Starvation is the most gradual: threads are runnable but consistently denied access to a resource, starving while other threads proceed.

These failures tend to be difficult to reproduce in testing because they depend on specific thread scheduling orders that may be rare under normal conditions but become frequent under load. A deadlock that never occurs during development may surface reliably in production under peak concurrency. Designing systems to be free of these conditions requires deliberate architectural choices made before the code is written, not after the failure is observed.

Understanding these failure modes is also essential for interpreting diagnostic output. A JVM thread dump — the primary tool for diagnosing stuck Java applications — directly exposes which threads are blocked, on which monitors, and which thread holds each monitor. Knowing what deadlock and starvation look like in a thread dump is a practical production skill.

## Key Concepts

### Deadlock

A deadlock occurs when two or more threads each hold a resource and are each waiting to acquire a resource held by another, forming a cycle of dependencies that cannot be broken. Four conditions, identified by Coffman et al., are jointly necessary for a deadlock to occur:

1. **Mutual exclusion**: at least one resource is held in a non-shareable mode — only one thread can use it at a time.
2. **Hold and wait**: a thread holding at least one resource is waiting to acquire additional resources held by other threads.
3. **No preemption**: resources cannot be taken away from a thread forcibly; they are released only voluntarily.
4. **Circular wait**: there exists a set of threads T1, T2, ..., Tn such that T1 is waiting for a resource held by T2, T2 is waiting for a resource held by T3, ..., and Tn is waiting for a resource held by T1.

```
Thread Alpha holds Lock-A, waiting for Lock-B
Thread Beta  holds Lock-B, waiting for Lock-A

    Alpha ----holds----> Lock-A
    Alpha ----wants----> Lock-B
    Beta  ----holds----> Lock-B
    Beta  ----wants----> Lock-A

Circular wait:
    Alpha --> Lock-B --> Beta --> Lock-A --> Alpha
```

The standard two-thread, two-lock deadlock:

```java
Object lockA = new Object();
Object lockB = new Object();

// Thread Alpha acquires A then B
Thread alpha = new Thread(() -> {
    synchronized (lockA) {
        pause(50); // hold lockA long enough for Beta to grab lockB
        synchronized (lockB) { // BLOCKS: Beta holds lockB
            System.out.println("Alpha: got both locks");
        }
    }
}, "Thread-Alpha");

// Thread Beta acquires B then A -- opposite order
Thread beta = new Thread(() -> {
    synchronized (lockB) {
        pause(50);
        synchronized (lockA) { // BLOCKS: Alpha holds lockA
            System.out.println("Beta: got both locks");
        }
    }
}, "Thread-Beta");
```

Neither thread ever prints its message. Both are blocked forever.

### Lock Ordering

Eliminating the circular wait condition prevents deadlock. If all threads always acquire locks in the same global order, a cycle is impossible: the thread that acquired the last lock in the ordering cannot be waiting for a lock that a thread earlier in the ordering holds.

```java
// Define a global order: always acquire lockA before lockB
Object lockA = new Object();
Object lockB = new Object();

// Both threads acquire in the same order: A then B
Runnable orderedTask = () -> {
    synchronized (lockA) {        // same order in both threads
        synchronized (lockB) {
            System.out.println(Thread.currentThread().getName() + ": both locks acquired");
        }
    }
};
```

With this ordering, when Thread Alpha holds lockA and tries to acquire lockB, Thread Beta is attempting to acquire lockA first. Since Alpha holds lockA, Beta blocks. Alpha proceeds to acquire lockB, does its work, releases lockB, then releases lockA. Beta then acquires lockA, then lockB. No deadlock.

### tryLock Prevention

When lock ordering cannot be enforced — for example when the locks are determined at runtime or come from third-party code — `ReentrantLock.tryLock(timeout, unit)` provides a way to detect and back off from potential deadlocks:

```java
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

ReentrantLock lockA = new ReentrantLock();
ReentrantLock lockB = new ReentrantLock();

boolean acquireBoth() throws InterruptedException {
    while (true) {
        if (lockA.tryLock(50, TimeUnit.MILLISECONDS)) {
            try {
                if (lockB.tryLock(50, TimeUnit.MILLISECONDS)) {
                    try {
                        doWork(); // hold both locks here
                        return true;
                    } finally {
                        lockB.unlock();
                    }
                }
                // Could not get lockB -- release lockA and retry
            } finally {
                lockA.unlock();
            }
        }
        // Back off before retrying to reduce contention
        Thread.sleep(10 + (long)(Math.random() * 20));
    }
}
```

If a thread cannot acquire lockB within the timeout, it releases lockA and retries. Because the thread is no longer holding lockA, the hold-and-wait condition is broken. The randomized backoff in the retry prevents two threads from repeatedly interfering with each other in lockstep.

### Identity-Based Ordering

When locks are domain objects (such as bank accounts or user records) and lock ordering must be determined at runtime, `System.identityHashCode()` provides a stable total order over all objects:

```java
void transfer(Account from, Account to, int amount) {
    // Determine lock order by identity hash code
    int fromHash = System.identityHashCode(from);
    int toHash   = System.identityHashCode(to);

    Object first  = (fromHash <= toHash) ? from : to;
    Object second = (fromHash <= toHash) ? to   : from;

    synchronized (first) {
        synchronized (second) {
            from.debit(amount);
            to.credit(amount);
        }
    }
}
```

If two accounts have the same identity hash code (rare but possible), a tiebreaker lock is required to avoid re-introducing the circular wait:

```java
// Tiebreaker when hash codes collide:
private static final Object TIE_LOCK = new Object();

void transfer(Account from, Account to, int amount) {
    int fromHash = System.identityHashCode(from);
    int toHash   = System.identityHashCode(to);

    if (fromHash < toHash) {
        synchronized (from) { synchronized (to) { doTransfer(from, to, amount); } }
    } else if (fromHash > toHash) {
        synchronized (to) { synchronized (from) { doTransfer(from, to, amount); } }
    } else {
        synchronized (TIE_LOCK) {
            synchronized (from) { synchronized (to) { doTransfer(from, to, amount); } }
        }
    }
}
```

### Livelock

Livelock occurs when threads are not blocked but are repeatedly responding to each other in a way that prevents either from making progress. The threads are active — they consume CPU time, they run through code — but the system as a whole does not advance toward its goal.

A common scenario: two threads each acquire one lock, detect a potential conflict, release their lock, and retry — but they retry in synchrony, so they always collide again:

```java
// Thread Polite-A and Thread Polite-B each try to give way to the other,
// but they yield at the same rate and never both proceed.
volatile boolean aMoving = false;
volatile boolean bMoving = false;

// Thread A:
while (true) {
    aMoving = true;
    Thread.sleep(10);
    if (bMoving) {        // B is also trying to move
        aMoving = false;  // politely step back
        continue;         // try again -- but B does the same thing
    }
    doAWork();
    break;
}

// Thread B: symmetric -- always yields when A is also active
```

The fix is randomized backoff: each thread waits a random duration before retrying, breaking the synchrony:

```java
Random rng = new Random();

while (true) {
    aMoving = true;
    Thread.sleep(10);
    if (bMoving) {
        aMoving = false;
        Thread.sleep(rng.nextInt(50)); // random delay -- breaks lockstep
        continue;
    }
    doAWork();
    break;
}
```

With different backoff durations, one thread will usually retry and succeed before the other has reset.

### Starvation

Starvation occurs when a thread is perpetually denied access to a resource it needs to make progress, even though the resource is periodically available. The other threads monopolize it. A thread experiencing starvation is runnable — it is not blocked indefinitely — but it is never selected by the scheduler.

Common causes of starvation include threads that hold locks for long periods leaving other threads waiting, priority-based scheduling that always favors high-priority threads, and non-fair locks that allow threads already past the lock to re-acquire it before a waiting thread gets a chance.

The `ReentrantLock` constructor accepts a fairness parameter. A fair lock grants the lock to the thread that has been waiting the longest:

```java
import java.util.concurrent.locks.ReentrantLock;

// Non-fair lock (default): high throughput but allows starvation
ReentrantLock unfairLock = new ReentrantLock();

// Fair lock: lower throughput but prevents starvation
ReentrantLock fairLock = new ReentrantLock(true /* fair */);
```

Fair locks carry a performance cost — acquiring a fair lock requires examining and maintaining a queue of waiting threads — but they guarantee that no thread waits indefinitely. For most applications the default non-fair lock is appropriate; reserve fair locks for cases where starvation has been identified as an actual problem.

### ThreadMXBean Detection

The `java.lang.management.ThreadMXBean` interface provides `findDeadlockedThreads()`, which returns the thread IDs of threads involved in a deadlock (cycles through both object monitors and `java.util.concurrent` locks):

```java
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.management.ThreadInfo;

ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

long[] deadlockedIds = tmx.findDeadlockedThreads();
if (deadlockedIds != null) {
    ThreadInfo[] infos = tmx.getThreadInfo(deadlockedIds, true, true);
    for (ThreadInfo info : infos) {
        System.out.println("Deadlocked thread: " + info.getThreadName());
        System.out.println("  State: " + info.getThreadState());
        System.out.println("  Waiting to lock: " + info.getLockName());
        System.out.println("  Lock held by: " + info.getLockOwnerName());
        System.out.println("  Stack trace:");
        for (StackTraceElement e : info.getStackTrace()) {
            System.out.println("    at " + e);
        }
    }
}
```

This can be run periodically on a background thread to detect deadlocks at runtime and emit an alert or thread dump before the application becomes completely unresponsive.

## Code Snippet

```java
// Demonstrates deadlock with two threads acquiring locks in opposite order,
// detects it using ThreadMXBean, and shows the fixed version with consistent
// lock ordering.
//
// Run: javac DeadlockAndFixDemo.java && java DeadlockAndFixDemo

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

public class DeadlockAndFixDemo {

    static final Object LOCK_A = new Object();
    static final Object LOCK_B = new Object();

    // --- Part 1: Deadlock ---
    static void startDeadlock() {
        Thread alpha = new Thread(() -> {
            synchronized (LOCK_A) {
                System.out.println("[Thread-Alpha] acquired LOCK_A");
                safeSleep(100);
                System.out.println("[Thread-Alpha] waiting for LOCK_B...");
                synchronized (LOCK_B) {
                    System.out.println("[Thread-Alpha] acquired LOCK_B -- unreachable in deadlock");
                }
            }
        }, "Thread-Alpha");

        Thread beta = new Thread(() -> {
            synchronized (LOCK_B) {
                System.out.println("[Thread-Beta] acquired LOCK_B");
                safeSleep(100);
                System.out.println("[Thread-Beta] waiting for LOCK_A...");
                synchronized (LOCK_A) {
                    System.out.println("[Thread-Beta] acquired LOCK_A -- unreachable in deadlock");
                }
            }
        }, "Thread-Beta");

        alpha.setDaemon(true);
        beta.setDaemon(true);
        alpha.start();
        beta.start();
    }

    static void detectDeadlock() throws InterruptedException {
        Thread.sleep(500); // give deadlock time to form
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = tmx.findDeadlockedThreads();
        if (deadlocked == null) {
            System.out.println("No deadlock detected.");
            return;
        }
        System.out.println("\n*** DEADLOCK DETECTED involving " + deadlocked.length + " thread(s) ***");
        ThreadInfo[] infos = tmx.getThreadInfo(deadlocked, true, true);
        for (ThreadInfo info : infos) {
            System.out.printf("  Thread '%s' [state=%s]%n",
                    info.getThreadName(), info.getThreadState());
            System.out.printf("    waiting to lock : %s%n", info.getLockName());
            System.out.printf("    lock is held by : %s%n", info.getLockOwnerName());
        }
    }

    // --- Part 2: Fixed with consistent lock ordering ---
    static void startFixed() throws InterruptedException {
        System.out.println("\n=== Fixed version: consistent lock ordering (always A then B) ===");

        Runnable orderedTask = () -> {
            synchronized (LOCK_A) {        // ALWAYS acquire A first
                System.out.printf("[%s] acquired LOCK_A%n", Thread.currentThread().getName());
                safeSleep(50);
                synchronized (LOCK_B) {    // ALWAYS acquire B second
                    System.out.printf("[%s] acquired LOCK_B -- doing work%n",
                            Thread.currentThread().getName());
                }
            }
            System.out.printf("[%s] released both locks%n", Thread.currentThread().getName());
        };

        Thread t1 = new Thread(orderedTask, "Fixed-Alpha");
        Thread t2 = new Thread(orderedTask, "Fixed-Beta");
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        System.out.println("Fixed version completed without deadlock.");
    }

    static void safeSleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Part 1: Inducing deadlock ===");
        startDeadlock();
        detectDeadlock();

        startFixed();
    }
}
```

## Gotchas

**Lock ordering must be total and consistent across all code paths, not just the obvious ones.** A global lock ordering prevents deadlock only if every thread that acquires those locks respects the order — including code in superclasses, callbacks, event handlers, and third-party libraries. A single path that acquires locks in a different order can create a deadlock that is extremely difficult to diagnose because the offending code is not where the developer expects it.

**`tryLock` with a timeout prevents deadlock but introduces retry logic complexity.** A thread that fails to acquire a lock must release all locks it holds and retry from the beginning. If the critical section has side effects — partially modified state, messages sent, resources allocated — those side effects must be undone before retry. Ensuring clean rollback is often harder than establishing a lock ordering, and incomplete rollback creates data corruption that is even harder to diagnose than the original deadlock.

**Non-reentrant livelock is easy to miss because threads appear active.** A monitoring system that measures CPU usage or thread state may report all threads as RUNNABLE during a livelock. The only observable symptom is that no useful work is being completed. Metrics on actual throughput — tasks completed per second, records processed, requests served — are needed to distinguish livelock from legitimate high-CPU work.

**Fair locks prevent starvation but reduce throughput.** With a fair `ReentrantLock`, a thread that just released a lock must join the back of the queue before re-acquiring it, even if no other thread is waiting. This is correct behavior for fairness but adds overhead on every acquire. Benchmarks have shown fair locks to be 10x to 100x slower than non-fair locks under high contention. Apply fair locks only where starvation is a demonstrated problem.

**`findDeadlockedThreads()` does not detect all forms of resource exhaustion.** It detects cycles involving object monitors and `java.util.concurrent.Lock` instances. It does not detect threads that are stuck waiting on a blocked `Semaphore`, a full `BlockingQueue`, or an external resource such as a database connection. A comprehensive health check must also inspect thread states and stack traces for threads in WAITING or TIMED_WAITING states that have been waiting suspiciously long.

**Deadlocks involving database transactions are not detected by JVM tools.** When application threads hold a JVM-level lock and then execute a database transaction that waits for a row lock, and a database transaction holds that row lock while waiting for the JVM-level lock, the cycle spans the JVM and the database. `findDeadlockedThreads()` sees only the JVM side. The database may detect the cycle and roll back one transaction, but the application thread holding the JVM-level lock is still blocked waiting for the rollback to propagate. Detection requires examining both database lock wait graphs and JVM thread states simultaneously.
