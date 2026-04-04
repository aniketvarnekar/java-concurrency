# Lock vs synchronized

## Overview

synchronized is a language keyword that ties locking to lexical scope. A synchronized block or method is entered and the lock is released automatically when the block exits, whether by normal return or by exception. This makes it impossible to forget to release the lock within the scope. The simplicity and automatic release make synchronized the right default for straightforward mutual exclusion.

The java.util.concurrent.locks.Lock interface provides the same mutual exclusion and memory visibility guarantees as synchronized but with explicit acquire and release, giving the programmer more control at the cost of more responsibility. The caller must call lock() before the critical section and unlock() in a finally block to guarantee release. In exchange, the caller gains the ability to attempt a non-blocking lock acquisition, a timed lock attempt, an interruptible wait, fairness ordering, multiple Conditions per lock, and access to read-write split semantics.

Choosing between the two comes down to the specific requirements of the code. For most simple cases, synchronized is sufficient, easier to read, and benefits more from JVM optimizations. For code that needs non-blocking tryLock, cancellable lockInterruptibly, multiple Conditions, or that runs on virtual threads and may block while holding a lock, ReentrantLock is the better choice.

## Key Concepts

### Feature Comparison

| Feature | synchronized | ReentrantLock |
|---|---|---|
| Interruptible wait | No | Yes (lockInterruptibly) |
| Timed lock attempt | No | Yes (tryLock(timeout)) |
| Non-blocking try | No | Yes (tryLock()) |
| Multiple Conditions | No (one wait set) | Yes (newCondition()) |
| Fairness control | No | Yes (new ReentrantLock(true)) |
| Read-write splitting | No | Use ReadWriteLock |
| Scope | Lexical (auto-release) | Explicit (must unlock in finally) |
| Forgetting to unlock | Impossible | Bug risk |
| Virtual thread pinning | Yes (pins carrier) | No |
| JVM optimization (biased, elision) | Yes | Less |
| Code verbosity | Low | Higher |

### When to Use synchronized

synchronized is appropriate for straightforward mutual exclusion where readability and compactness matter. It is the right choice when timed or interruptible lock attempts are not needed, when one wait set per monitor is sufficient, and when the code will not run on virtual threads that may block on IO while holding the lock. The automatic release on exception eliminates an entire class of lock-leak bugs.

```java
class Counter {
    private int count;

    synchronized void increment() {
        count++;
    }

    synchronized int get() {
        return count;
    }
}
```

### When to Use Lock

ReentrantLock is appropriate when tryLock() is needed to prevent deadlock by backing off, when lockInterruptibly() is needed to allow cancellation of waiting threads, when multiple Conditions are needed on one lock, when fairness ordering is required to prevent starvation, or when ReadWriteLock or StampedLock semantics are needed. The mandatory try/finally pattern must be followed without exception.

```java
lock.lock();
try {
    // critical section
} finally {
    lock.unlock();  // guaranteed release even on exception
}
```

### Performance

Historically, explicit Lock implementations outperformed synchronized under high contention because they offered more flexible queuing strategies. Modern JVMs apply biased locking, lock elision through escape analysis, and adaptive spinning to synchronized, making the difference negligible for most workloads. Choosing between them on performance grounds without measurement is premature optimization.

### Virtual Threads (Java 21+)

A virtual thread that blocks inside a synchronized block is pinned to its carrier OS thread. While pinned, the carrier thread cannot run other virtual threads, tying up a physical thread and reducing the scalability benefit of virtual threads. This occurs because the JVM cannot unmount a virtual thread that is suspended within a synchronized block — the monitor ownership is stored in the OS thread's native frame.

ReentrantLock does not pin because its blocking is implemented through LockSupport.park(), which the virtual thread scheduler understands. A virtual thread waiting on a ReentrantLock can be unmounted from its carrier, allowing the carrier to run other virtual threads while the first one waits.

```
Virtual Thread Pinning:

  synchronized block         ReentrantLock
  ┌──────────────────┐       ┌──────────────┐
  │ VThread blocked  │       │ VThread parks│
  │ Carrier PINNED   │       │ Carrier FREE │
  │ No other VThread │       │ Runs others  │
  │ can run here     │       │ while parked │
  └──────────────────┘       └──────────────┘
```

## Code Snippet

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class LockVsSynchronized {

    // --- Version 1: synchronized ---
    static class SynchronizedCounter {
        private int count;

        synchronized void increment() {
            count++;
        }

        synchronized int get() {
            return count;
        }
    }

    // --- Version 2: ReentrantLock with try/finally ---
    static class LockedCounter {
        private int count;
        private final ReentrantLock lock = new ReentrantLock();

        void increment() {
            lock.lock();
            try {
                count++;
            } finally {
                lock.unlock();
            }
        }

        int get() {
            lock.lock();
            try {
                return count;
            } finally {
                lock.unlock();
            }
        }
    }

    // --- Version 3: tryLock to avoid deadlock ---
    static class BankAccount {
        private double balance;
        final ReentrantLock lock = new ReentrantLock();
        private final String name;

        BankAccount(String name, double initialBalance) {
            this.name = name;
            this.balance = initialBalance;
        }

        String getName() { return name; }

        // Transfers from this account to target, using tryLock on both to avoid deadlock.
        boolean transfer(BankAccount target, double amount) throws InterruptedException {
            while (true) {
                if (this.lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                    try {
                        if (target.lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                            try {
                                if (this.balance < amount) {
                                    System.out.println(Thread.currentThread().getName()
                                            + " insufficient funds in " + name);
                                    return false;
                                }
                                this.balance -= amount;
                                target.balance += amount;
                                System.out.printf("%s transferred %.2f from %s to %s%n",
                                        Thread.currentThread().getName(), amount, this.name, target.name);
                                return true;
                            } finally {
                                target.lock.unlock();
                            }
                        }
                    } finally {
                        this.lock.unlock();
                    }
                }
                System.out.println(Thread.currentThread().getName()
                        + " could not acquire both locks, retrying...");
                Thread.sleep(50); // back off before retry
            }
        }

        double getBalance() {
            lock.lock();
            try { return balance; } finally { lock.unlock(); }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Demo 1: synchronized counter ===");
        SynchronizedCounter syncCounter = new SynchronizedCounter();
        runCounterThreads(syncCounter::increment, syncCounter::get, "sync");

        System.out.println("\n=== Demo 2: ReentrantLock counter ===");
        LockedCounter lockedCounter = new LockedCounter();
        runCounterThreads(lockedCounter::increment, lockedCounter::get, "lock");

        System.out.println("\n=== Demo 3: tryLock deadlock prevention ===");
        BankAccount accountA = new BankAccount("Account-A", 1000.0);
        BankAccount accountB = new BankAccount("Account-B", 1000.0);

        Thread t1 = new Thread(() -> {
            try {
                accountA.transfer(accountB, 100.0);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "transfer-thread-1");

        Thread t2 = new Thread(() -> {
            try {
                accountB.transfer(accountA, 200.0);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "transfer-thread-2");

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.printf("Final balances: %s=%.2f, %s=%.2f%n",
                accountA.getName(), accountA.getBalance(),
                accountB.getName(), accountB.getBalance());
    }

    static void runCounterThreads(Runnable increment, java.util.function.IntSupplier get,
                                   String label) throws InterruptedException {
        final int INCREMENTS = 1000;
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < INCREMENTS; i++) increment.run();
        }, label + "-thread-1");

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < INCREMENTS; i++) increment.run();
        }, label + "-thread-2");

        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.println("Final count (expected " + (INCREMENTS * 2) + "): " + get.getAsInt());
    }
}
```

## Gotchas

**Forgetting to call unlock() in a finally block with ReentrantLock means the lock is never released if the critical section throws.** The lock remains held indefinitely, causing all threads that subsequently try to acquire it to block forever. Every ReentrantLock usage must follow the pattern: lock.lock(); try { ... } finally { lock.unlock(); }. There is no automatic release.

**A virtual thread that blocks on IO inside a synchronized block pins the carrier OS thread, preventing other virtual threads from being scheduled on that carrier.** If an application uses virtual threads for high-concurrency IO (database calls, network requests) and those operations are inside synchronized blocks, the carrier thread pool becomes saturated and throughput collapses. Replace synchronized with ReentrantLock in these code paths.

**The fairness parameter of ReentrantLock(true) prevents starvation but introduces significant throughput overhead due to strict FIFO ordering.** In a fair lock, every lock acquisition and release requires communication with the wait queue, preventing the common fast-path where a thread releases and immediately re-acquires. Use fairness only when starvation has been observed and measured to be a problem.

**lockInterruptibly() does not help if the thread is never interrupted.** A thread waiting on lock.lock() cannot be woken by Thread.interrupt() — only lockInterruptibly() responds to interruption. Switching to lockInterruptibly() requires that whoever calls interrupt() on the waiting thread actually does so; otherwise the interruptibility is theoretical and unused.

**tryLock() without arguments returns immediately even under high contention, and returning false does not mean the lock is permanently unavailable.** Code that calls tryLock() once and gives up is not using it correctly for deadlock prevention. The correct pattern for deadlock prevention is to retry with a short backoff: try to acquire, if failed, release any locks already held, sleep briefly, and retry the entire acquisition sequence.

**Code using synchronized is automatically reentrant; switching critical sections to a non-reentrant lock such as StampedLock requires a careful audit of all call chains.** If a method holding a StampedLock write stamp calls another method that also tries to acquire the write lock, the calling thread deadlocks. ReentrantLock is also reentrant by default, making it a safer substitution than StampedLock when reentrancy was previously relied upon implicitly.
