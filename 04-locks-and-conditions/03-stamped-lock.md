# StampedLock

## Overview

StampedLock, introduced in Java 8, is a non-reentrant lock that provides three modes of access control: exclusive writing, shared reading, and optimistic reading. Unlike ReentrantReadWriteLock, it is not reentrant and does not implement the Lock or ReadWriteLock interfaces. Instead, it uses a long-valued stamp as the token for every lock acquisition and release, and the caller is responsible for passing the correct stamp to the matching unlock method.

The central innovation of StampedLock is the optimistic read mode. An optimistic read does not acquire any lock — it simply records the current lock state as a stamp. The reader then reads the shared data and calls validate(stamp) to check whether any writer acquired the lock between the initial stamp and the validation call. If validation succeeds, the data is safe to use. If it fails, the reader must fall back to a full pessimistic read lock to re-read consistent data. This pattern avoids read-write contention entirely in the common case of infrequent writes.

Lock conversion methods allow a thread holding one mode to attempt an upgrade or downgrade to another mode. tryConvertToWriteLock(stamp) promotes a read lock to a write lock if no other reader is active. tryConvertToOptimisticRead(stamp) downgrades a write lock to an optimistic observation. All conversion methods return a new valid stamp on success or zero on failure, and returning zero invalidates the original stamp.

## Key Concepts

### Pessimistic Read Lock

readLock() acquires a shared read lock, blocking if a writer currently holds the write lock. It returns a stamp that must be passed to unlockRead(stamp) to release the lock. Multiple threads may hold the read lock simultaneously, making it suitable for concurrent reads of shared data.

```java
long stamp = lock.readLock();
try {
    return data;
} finally {
    lock.unlockRead(stamp);
}
```

### Write Lock

writeLock() acquires an exclusive write lock, blocking all readers and other writers. The returned stamp must be passed to unlockWrite(stamp). Only one thread may hold the write lock at a time.

```java
long stamp = lock.writeLock();
try {
    data = newValue;
} finally {
    lock.unlockWrite(stamp);
}
```

### Optimistic Read

tryOptimisticRead() returns a stamp without blocking and without acquiring any lock. If a write lock is currently held, it returns 0. After reading shared state, the caller must call validate(stamp). A true result means no write lock was acquired during the read, and the data is consistent. A false result means a writer intervened and the data may be inconsistent — fall back to readLock().

```
tryOptimisticRead() → stamp
  read shared data
  validate(stamp)?
    yes → data is good
    no  → readLock() → re-read → unlockRead(stamp2)
```

```java
long stamp = lock.tryOptimisticRead();
double x = this.x;
double y = this.y;
if (!lock.validate(stamp)) {
    stamp = lock.readLock();
    try {
        x = this.x;
        y = this.y;
    } finally {
        lock.unlockRead(stamp);
    }
}
return Math.sqrt(x * x + y * y);
```

### Lock Conversion

Conversion methods attempt to atomically switch from one lock mode to another. Each returns a new stamp on success or 0 on failure. On failure, the original stamp remains valid only for its original mode — the caller must handle the 0 case explicitly before proceeding.

| Method | From | To | Condition for success |
|---|---|---|---|
| tryConvertToWriteLock(stamp) | Read or Optimistic | Write | No other readers |
| tryConvertToReadLock(stamp) | Write or Optimistic | Read | Always succeeds from write |
| tryConvertToOptimisticRead(stamp) | Write or Read | Optimistic | Always succeeds |

```java
long stamp = lock.readLock();
try {
    // decide we need to write
    long writeStamp = lock.tryConvertToWriteLock(stamp);
    if (writeStamp != 0L) {
        stamp = writeStamp;
        data = newValue;
    } else {
        // conversion failed: other readers exist
        lock.unlockRead(stamp);
        stamp = lock.writeLock();
        data = newValue;
    }
} finally {
    lock.unlock(stamp);
}
```

### Non-Reentrancy

StampedLock is NOT reentrant. A thread that holds a write lock and calls writeLock() again will deadlock, blocking forever waiting for itself to release the lock it already holds. This is unlike ReentrantLock and synchronized, which allow the same thread to re-enter. Any code that calls helper methods while holding a StampedLock must be carefully audited for reentrant call chains.

### Stamp Handling

Each acquisition returns a distinct stamp value. Stamps must be stored in local variables and passed precisely to the matching unlock method. Stamps are not reference-counted — calling unlock with a wrong or stale stamp throws IllegalMonitorStateException. The same stamp cannot be used twice; after unlock the stamp is consumed.

## Gotchas

**StampedLock is not reentrant and calling writeLock() while already holding writeLock() deadlocks.** Unlike ReentrantLock or synchronized, there is no reentrancy counter that tracks how many times the same thread has acquired the lock. Any attempt by the lock-holding thread to acquire the same lock again will block forever waiting for a release that can never happen.

**The stamp returned by tryOptimisticRead() is not a lock — it does not prevent writers from acquiring the write lock.** validate(stamp) must be called before using any data read under the optimistic stamp. Skipping the validate call and proceeding with the read data is a silent correctness bug that can produce torn reads under concurrent writes.

**StampedLock does not implement the Lock interface and has no Condition support.** Code that accepts a Lock parameter cannot accept a StampedLock, and there is no way to create a Condition object from it. If Condition semantics are needed, ReentrantLock is the appropriate choice.

**readLockInterruptibly() and writeLockInterruptibly() exist but are less commonly used than the plain blocking forms.** Code that expects ReentrantLock-style interrupt behavior may be surprised to find the plain readLock() and writeLock() methods ignore interrupts while blocked.

**Conversion methods return 0 on failure, and a 0 stamp indicates both that the conversion failed and that the returned value is not a valid stamp.** Passing 0 to any unlock method or using 0 as a stamp for a subsequent operation causes incorrect behavior. Always check the return value with an explicit comparison before proceeding, and handle the 0-return path with a fallback.

**Forgetting to handle the 0-return from tryConvertToWriteLock() while still holding a read lock causes a resource leak or deadlock.** If the conversion fails and the caller proceeds as if they hold a write lock without first releasing the read lock, subsequent writers will block indefinitely. The original read stamp is still valid and must be explicitly unlocked in the failure path.
