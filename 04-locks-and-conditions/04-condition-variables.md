# Condition Variables

## Overview

A Condition object is obtained from a Lock via lock.newCondition() and provides wait and signal semantics tied to that explicit lock. The thread must hold the associated lock before calling any Condition method, just as it must hold a monitor lock before calling Object.wait(). The key difference is that multiple Condition objects can be associated with a single lock, enabling precise signaling of specific subsets of waiting threads rather than waking all waiters indiscriminately.

With synchronized and Object.wait(), every object has exactly one wait set. If threads are waiting for different conditions on the same monitor, notifyAll() must be used to ensure that all potentially-satisfied waiters are awakened, at the cost of waking threads that cannot yet proceed. With Condition objects, a producer can signal only the notEmpty condition (waking a waiting consumer) and a consumer can signal only the notFull condition (waking a waiting producer), completely eliminating unnecessary wakeups.

The Condition interface also extends the API beyond what Object provides. await(long time, TimeUnit unit) allows timed waiting with a boolean return indicating whether the wait timed out or was signalled. awaitUninterruptibly() provides a wait that ignores thread interruption entirely. awaitUntil(Date deadline) allows specifying an absolute time rather than a relative duration.

## Key Concepts

### Creating a Condition

A Condition is created by calling newCondition() on a Lock instance. The Condition is permanently bound to that lock — it can only be used while the lock is held and will throw IllegalMonitorStateException otherwise. A single lock can produce any number of distinct Condition objects, each with its own independent wait set.

```java
ReentrantLock lock = new ReentrantLock();
Condition notFull  = lock.newCondition();
Condition notEmpty = lock.newCondition();
```

### await()

await() releases the currently held lock and suspends the calling thread on this Condition's wait set. When another thread calls signal() or signalAll() on the same Condition, the waiting thread wakes up, reacquires the lock, and returns from await(). The behavior is directly equivalent to Object.wait() but scoped to an explicit lock rather than a monitor.

```java
lock.lock();
try {
    while (!conditionMet) {
        condition.await();   // releases lock, suspends, reacquires on wake
    }
    // safe to proceed
} finally {
    lock.unlock();
}
```

### signal() and signalAll()

signal() wakes one arbitrary thread waiting on this Condition's wait set. signalAll() wakes all threads waiting on this Condition. Because Conditions have independent wait sets, calling signal() on notFull only affects threads waiting on notFull — threads waiting on notEmpty are not disturbed.

```java
lock.lock();
try {
    buffer[putIndex] = item;
    notEmpty.signal();   // wake one consumer
} finally {
    lock.unlock();
}
```

### Spurious Wakeups

await() can return without a signal due to OS-level artifacts. This is called a spurious wakeup and is permitted by the Java specification. The condition predicate must always be checked in a while loop, never an if statement, so that a spurious wakeup causes another check and another wait if the condition is still not met.

```java
// correct
while (count == capacity) notFull.await();

// incorrect — spurious wakeup passes the check even when buffer is full
if (count == capacity) notFull.await();
```

### Multiple Conditions

The most compelling use case for Condition objects is a bounded buffer that needs to wake producers separately from consumers. Using one Condition per logical state eliminates the need for notifyAll() and avoids the CPU cost of context-switching threads that immediately find they cannot proceed.

```
One lock, two Conditions:

  notFull  wait set:  [producer1, producer2]  ← consumers call notFull.signal()
  notEmpty wait set:  [consumer1]             ← producers call notEmpty.signal()
```

### Timed Await

await(long time, TimeUnit unit) returns true if the condition was signalled within the time limit and false if the wait timed out. awaitUntil(Date deadline) is the absolute-time variant. awaitUninterruptibly() waits indefinitely without responding to thread interruption, which can cause threads to become stuck if the signal never arrives.

```java
boolean signalled = condition.await(500, TimeUnit.MILLISECONDS);
if (!signalled) {
    // timed out without a signal — handle accordingly
}
```

### Comparison: Object.wait/notify vs Condition.await/signal

| Feature | Object.wait/notify | Condition.await/signal |
|---|---|---|
| Lock type | synchronized (monitor) | Any Lock implementation |
| Multiple wait sets per lock | No | Yes |
| Interruptible | Yes (always) | Yes (await), No (awaitUninterruptibly) |
| Timed wait | wait(timeout) | await(time, unit) |
| Spurious wakeup | Yes | Yes |
| Source | java.lang.Object | java.util.concurrent.locks.Condition |

## Gotchas

**Calling await(), signal(), or signalAll() without holding the associated lock throws IllegalMonitorStateException.** The lock must be acquired before entering any Condition method, and the standard try/finally pattern must wrap all Condition usage to guarantee unlock even on exception.

**Calling signal() when no thread is waiting on that Condition is not an error, but it is a lost signal if the predicate subsequently changes.** If a producer calls notEmpty.signal() when no consumer is waiting, and a consumer later calls notEmpty.await(), it will wait indefinitely because the signal was discarded. The predicate check in the while loop handles this by verifying the actual state rather than relying solely on the signal.

**Using if instead of while to guard the predicate is a correctness bug that survives spurious wakeups and missed signals.** The pattern if (!ready) condition.await() can resume from await() when ready is still false due to a spurious wakeup, causing the thread to proceed with an invalid assumption. The guard must always be a while loop.

**A Condition is permanently bound to the Lock that created it.** Using a Condition from one lock instance while holding a different lock instance causes IllegalMonitorStateException. This is easy to get wrong when Condition references are stored in fields and the lock is not the same object across all callers.

**signalAll() on notFull wakes all waiting producers, but only one can proceed because they all compete to reacquire the lock.** The remaining producers check the predicate, find the buffer still has space or not, and either proceed or wait again. The extra wakeups and re-acquisitions waste CPU compared to using signal() when only one slot has opened and only one producer can be served.

**awaitUninterruptibly() blocks the thread's interrupt flag — if the thread is interrupted while blocked in awaitUninterruptibly(), it will not wake up from the Condition until signalled.** This can cause threads that are part of a thread pool to become permanently stuck when the pool calls shutdown and interrupts its threads, preventing clean pool termination. Use awaitUninterruptibly() only when it is explicitly known that interruption should be suppressed.
