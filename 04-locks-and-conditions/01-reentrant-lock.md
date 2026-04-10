# ReentrantLock

## Overview

`ReentrantLock` is the most commonly used explicit lock in Java. It lives in `java.util.concurrent.locks` and implements the `Lock` interface. Every capability that `synchronized` provides — mutual exclusion, visibility guarantees, reentrancy — is also provided by `ReentrantLock`, plus several that `synchronized` lacks: timed lock acquisition, non-blocking lock attempts, interruptible waiting, and configurable fairness. The price of this flexibility is that you must release the lock manually, which `synchronized` handles automatically.

The name "reentrant" means that a thread that already holds the lock can acquire it again without blocking. This mirrors the behavior of `synchronized`, where a thread that has entered a synchronized block can re-enter another synchronized block on the same object. The lock maintains a hold count: each successful `lock()` call increments the count, and each `unlock()` call decrements it. The lock is fully released only when the count returns to zero.

The canonical pattern for using `ReentrantLock` always pairs `lock()` with `unlock()` in a `try/finally` block. This ensures the lock is released even if an exception is thrown inside the critical section. Omitting the `finally` block is the single most common misuse of explicit locks, and it causes the lock to remain held permanently, eventually deadlocking every thread that tries to acquire it.

Every feature of `ReentrantLock` is a tool with a specific purpose. `tryLock` is for deadlock avoidance when you cannot guarantee a consistent lock ordering. `tryLock(timeout, unit)` is for operations that must complete within a time bound. `lockInterruptibly` is for threads that should be cancellable while waiting. Fairness mode is for workloads where thread starvation is unacceptable. Understanding when each is appropriate is more important than memorizing the API.

## Key Concepts

### Basic Usage

The lock/unlock cycle is the foundation of every `ReentrantLock` use. The lock must be acquired before entering the critical section and released in a `finally` block to guarantee release even on exception.

```java
private final ReentrantLock lock = new ReentrantLock();
private int balance;

public void deposit(int amount) {
    lock.lock();
    try {
        balance += amount;          // critical section
    } finally {
        lock.unlock();              // always released
    }
}
```

Forgetting the `try/finally` structure is the primary source of bugs with explicit locks. If the critical section throws an unchecked exception and there is no `finally`, the lock is never released.

### Reentrancy

A thread already holding the lock can call `lock()` again on the same `ReentrantLock` instance. The hold count increments and the thread is not blocked. Each `lock()` must be matched by exactly one `unlock()`.

```java
public void methodA() {
    lock.lock();
    try {
        methodB(); // calls lock.lock() again — hold count becomes 2
    } finally {
        lock.unlock(); // hold count back to 1
    }
}

public void methodB() {
    lock.lock(); // same thread — not blocked, hold count becomes 2
    try {
        // work
    } finally {
        lock.unlock(); // hold count back to 1
    }
}
```

If a thread calls `unlock()` more times than `lock()`, an `IllegalMonitorStateException` is thrown. If it calls `lock()` more times than `unlock()`, the lock remains held after the method returns, blocking all other threads.

### Fairness

By default, `ReentrantLock` uses a non-fair (barging) policy: when the lock is released and multiple threads are waiting, any one of them — including a thread that just arrived and has not yet entered the wait queue — may acquire it. This maximizes throughput because it avoids the overhead of queue management.

A fair lock is constructed as `new ReentrantLock(true)`. It uses a FIFO queue: threads acquire the lock in the order they requested it. This eliminates starvation — no thread waits indefinitely — but at the cost of lower throughput and higher latency, because every lock release requires a context switch to the next thread in the queue rather than allowing the current thread to immediately re-acquire.

```java
// Non-fair (default): higher throughput, possible starvation
private final ReentrantLock unfairLock = new ReentrantLock();

// Fair: FIFO order, no starvation, lower throughput
private final ReentrantLock fairLock = new ReentrantLock(true);
```

Use fair locking only when you have demonstrated a starvation problem. Empirical benchmarks typically show fair locks running 10x-100x slower than non-fair locks under high contention.

### tryLock

`tryLock()` attempts to acquire the lock without waiting. It returns `true` if the lock was acquired and `false` if another thread holds it. This is the primary tool for avoiding deadlock when you cannot guarantee that all threads acquire locks in the same order.

`tryLock(long timeout, TimeUnit unit)` waits up to the specified duration, returning `true` if acquired within the timeout or `false` if the timeout expires. It also throws `InterruptedException` if the waiting thread is interrupted.

```java
public boolean transfer(Account from, Account to, int amount) {
    boolean fromLocked = false;
    boolean toLocked   = false;
    try {
        fromLocked = from.lock.tryLock(50, TimeUnit.MILLISECONDS);
        toLocked   = to.lock.tryLock(50, TimeUnit.MILLISECONDS);

        if (fromLocked && toLocked) {
            from.balance -= amount;
            to.balance   += amount;
            return true;
        }
        return false; // could not acquire both — caller should retry
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    } finally {
        if (fromLocked) from.lock.unlock();
        if toLocked   to.lock.unlock();
    }
}
```

When `tryLock()` returns `false`, the thread must not proceed with the operation and must not call `unlock()` on the lock it did not acquire.

### lockInterruptibly

`lockInterruptibly()` acquires the lock but allows the waiting thread to be interrupted. If the thread is interrupted while waiting, it throws `InterruptedException` and does not acquire the lock. This is different from `lock()`, which ignores interruption while waiting and sets the thread's interrupt status instead of throwing.

```java
public void interruptibleOp() throws InterruptedException {
    lock.lockInterruptibly(); // throws if interrupted while waiting
    try {
        // critical section
    } finally {
        lock.unlock();
    }
}
```

Use `lockInterruptibly` when the thread is part of a task that may be cancelled, such as a thread pool worker, or when long lock waits should be treated as errors. `synchronized` offers no equivalent — a thread blocked waiting for a monitor cannot be interrupted.

## Gotchas

### Forgetting to Call unlock() in a Finally Block

Every `lock()` call must be matched by exactly one `unlock()` call, and that `unlock()` must be in a `finally` block. If the critical section can throw any exception — even an unchecked one like `NullPointerException` — and `unlock()` is not in `finally`, the lock is permanently held. All subsequent threads that try to acquire it will block forever, with no timeout or interrupt able to help them.

### Calling unlock() from a Thread That Did Not Call lock()

Only the thread that acquired the lock should release it. Calling `lock.unlock()` from a different thread throws `IllegalMonitorStateException`. This is a common mistake in thread-pool scenarios where a task acquires the lock in one `Runnable` but cleanup code in a different thread or callback tries to release it.

### tryLock() Returning False Without Taking Action

`tryLock()` returning `false` means the lock was not acquired. The calling code must not enter the protected section and must not call `unlock()`. A common bug is to ignore the return value and proceed as if the lock was acquired, accessing shared state without synchronization.

### Fair Lock Overhead Is Significant

Setting `new ReentrantLock(true)` for fairness solves starvation but carries a large throughput penalty under contention. Every lock release requires waking the next thread in the FIFO queue, which involves a context switch. Unfair locks allow the releasing thread to immediately re-acquire or allow any runnable thread to grab it, which is much faster in throughput-sensitive scenarios.

### lockInterruptibly Requires Careful Exception Handling

When a thread is interrupted while waiting in `lockInterruptibly()`, the `InterruptedException` is thrown and the lock is NOT acquired. The caller must not proceed to the protected section and must not call `unlock()`. The caller must also decide whether to restore the interrupt status (`Thread.currentThread().interrupt()`) or propagate the exception to its caller. Swallowing the exception without restoring the interrupt flag corrupts the thread's interruption state.

### Reentrant Hold Count Leaks

If a thread calls `lock()` five times and `unlock()` only four times before returning, the lock remains held with a hold count of one. The next call to `lock()` by this thread will succeed (reentrancy), but no other thread will ever be able to acquire the lock. This leak typically occurs when an exception causes early return from a method that was supposed to call `unlock()` but had no `finally` block, or when conditional branches have different unlock call counts.
