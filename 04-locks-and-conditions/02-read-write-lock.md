# ReadWriteLock

## Overview

`ReadWriteLock` is an interface in `java.util.concurrent.locks` that defines a pair of locks — one for read operations and one for write operations — governing access to a single shared resource. The defining characteristic is that the read lock is shared: multiple threads can hold it simultaneously, as long as no thread holds the write lock. The write lock is exclusive: while it is held, no other thread can hold either the read lock or the write lock.

This separation is valuable for data structures that are read far more often than they are written. With a plain mutual exclusion lock, all readers block each other even though concurrent reads are perfectly safe. With a `ReadWriteLock`, reads that would otherwise serialize can run in parallel, increasing throughput proportionally to the number of concurrent readers. Writers still get exclusive access, ensuring consistency.

The standard implementation is `ReentrantReadWriteLock`, which supports reentrancy for both the read lock and the write lock, and provides a fairness option analogous to `ReentrantLock`. The two locks are obtained from the same `ReentrantReadWriteLock` instance and share their state, so the read/write exclusion is enforced correctly across all threads.

The primary trade-off is complexity. A plain lock has one acquisition/release cycle. A read-write lock has two, and the choice between them must be made correctly at every access point. A read operation that acquires the write lock adds unnecessary serialization. A write operation that acquires only the read lock allows concurrent writers and causes data corruption. Getting the choice wrong at even one location defeats the entire benefit of the pattern.

## Key Concepts

### Read Lock

The read lock (`rwLock.readLock()`) is a shared lock. Any number of threads can hold it simultaneously, provided no thread holds the write lock. A thread trying to acquire the read lock is blocked only if a writer currently holds the write lock, or (in fair mode) if a writer is waiting.

```java
private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
private final Lock readLock  = rwLock.readLock();
private Map<String, String> data = new HashMap<>();

public String get(String key) {
    readLock.lock();
    try {
        return data.get(key);    // multiple threads can execute this concurrently
    } finally {
        readLock.unlock();
    }
}
```

Read locks do not provide mutual exclusion against other readers, only against writers. If any state is modified inside a read lock, the result is undefined.

### Write Lock

The write lock (`rwLock.writeLock()`) is an exclusive lock. While it is held, no other thread can hold the read lock or the write lock. A thread trying to acquire the write lock is blocked until all readers and all other potential writers have released their locks.

```java
private final Lock writeLock = rwLock.writeLock();

public void put(String key, String value) {
    writeLock.lock();
    try {
        data.put(key, value);    // exclusive access — no readers or writers concurrent
    } finally {
        writeLock.unlock();
    }
}
```

The write lock is reentrant: a thread already holding the write lock can acquire it again. The same thread can also acquire the read lock while holding the write lock (lock downgrading — see below).

### ReentrantReadWriteLock

`ReentrantReadWriteLock` is the concrete implementation. Key properties:

- Both read and write locks support reentrancy.
- Write-lock reentrancy: a thread holding the write lock can call `writeLock().lock()` again.
- Read-lock reentrancy: a thread holding the read lock can call `readLock().lock()` again (but see the gotcha about read-lock hold count).
- The write lock supports `Condition` objects via `writeLock().newCondition()`. The read lock does not support conditions.
- `rwLock.getReadLockCount()`, `rwLock.isWriteLocked()`, and similar introspection methods are available for monitoring and debugging.

```java
ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true); // fair mode

Lock readLock  = rwLock.readLock();
Lock writeLock = rwLock.writeLock();

// Introspection
System.out.println("Readers: " + rwLock.getReadLockCount());
System.out.println("Write locked: " + rwLock.isWriteLocked());
```

### Lock Downgrading

Lock downgrading is the process of transitioning from a write lock to a read lock without releasing the write lock first. The sequence is: acquire the write lock, perform the write, acquire the read lock, release the write lock, read the updated data, then release the read lock. By acquiring the read lock before releasing the write lock, the thread ensures that no other writer can modify the data between the write and the subsequent read.

```java
public String updateAndGet(String key, String newValue) {
    writeLock.lock();
    try {
        data.put(key, newValue);   // perform write

        readLock.lock();           // acquire read lock while holding write lock
    } finally {
        writeLock.unlock();        // release write lock — now only read lock is held
    }

    try {
        return data.get(key);      // read — protected, no writers can intervene
    } finally {
        readLock.unlock();
    }
}
```

Lock upgrading — acquiring the write lock while holding the read lock — is not supported by `ReentrantReadWriteLock` and will deadlock. If you need to upgrade, release the read lock first, then acquire the write lock.

### Use Cases

`ReadWriteLock` is appropriate when reads are frequent and writes are infrequent, and when reads are not trivially fast (so the overhead of locking is worth the parallelism gain). Suitable patterns include:

- In-memory caches with occasional refreshes.
- Configuration objects read on every request, updated rarely.
- Data structures shared across many threads that are built once and queried repeatedly.

It is not appropriate when writes are as frequent as reads — the write exclusion serializes threads just as a plain lock would — or when the read operation is so fast that the overhead of the read lock itself dominates.

### Allowed Concurrent Access

| Situation | Read Lock Held | Write Lock Held |
|---|---|---|
| Reader requests read lock | Granted (shared) | Blocked |
| Writer requests write lock | Blocked | Blocked |
| Write lock holder requests read lock | Granted (downgrade) | N/A (self) |
| Read lock holder requests write lock | Deadlock | N/A |

## Code Snippet

A shared in-memory cache with concurrent readers and an exclusive writer, demonstrating that multiple read threads run in parallel while the writer gets sole access.

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SharedCache {
    private final Map<String, String> store = new HashMap<>();
    private final ReentrantReadWriteLock rwLock    = new ReentrantReadWriteLock();
    private final Lock readLock  = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    // Multiple threads can run get() concurrently
    public String get(String key) {
        readLock.lock();
        try {
            String value = store.get(key);
            System.out.println(Thread.currentThread().getName()
                + " [R] read key=" + key + " -> " + value
                + " | concurrent readers: " + rwLock.getReadLockCount());
            simulateReadWork(); // reads take some time
            return value;
        } finally {
            readLock.unlock();
        }
    }

    // Only one thread can run put() at a time; all readers are blocked during a write
    public void put(String key, String value) {
        writeLock.lock();
        try {
            System.out.println(Thread.currentThread().getName()
                + " [W] writing key=" + key + " value=" + value
                + " | write lock held exclusively");
            store.put(key, value);
            simulateWriteWork(); // writes also take some time
        } finally {
            writeLock.unlock();
        }
    }

    // Demonstrates lock downgrading: update a value and read it back
    // without any window for another writer to intervene
    public String putAndGet(String key, String value) {
        writeLock.lock();
        try {
            store.put(key, value);
            System.out.println(Thread.currentThread().getName()
                + " [W->R downgrade] wrote " + key + "=" + value);

            readLock.lock(); // acquire read lock while still holding write lock
        } finally {
            writeLock.unlock(); // release write lock — read lock still held
        }

        try {
            String result = store.get(key);
            System.out.println(Thread.currentThread().getName()
                + " [W->R downgrade] read back: " + key + "=" + result);
            return result;
        } finally {
            readLock.unlock();
        }
    }

    private void simulateReadWork() {
        try { Thread.sleep(30); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void simulateWriteWork() {
        try { Thread.sleep(50); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        SharedCache cache = new SharedCache();

        // Seed some data
        cache.put("host", "localhost");
        cache.put("port", "8080");

        System.out.println("\n--- Launching 4 concurrent readers + 1 writer ---\n");

        Thread writer = new Thread(() -> {
            cache.put("host", "production.example.com");
        }, "writer");

        Thread[] readers = new Thread[4];
        for (int i = 0; i < 4; i++) {
            readers[i] = new Thread(() -> {
                cache.get("host");
                cache.get("port");
            }, "reader-" + i);
        }

        // Start readers first, then the writer a moment later
        for (Thread r : readers) r.start();
        Thread.sleep(10);
        writer.start();

        for (Thread r : readers) r.join();
        writer.join();

        System.out.println("\n--- Demonstrating lock downgrade ---\n");
        Thread downgrader = new Thread(() -> {
            cache.putAndGet("timeout", "30s");
        }, "downgrader");
        downgrader.start();
        downgrader.join();

        System.out.println("\nFinal cache state:");
        System.out.println("  host    = " + cache.get("host"));
        System.out.println("  port    = " + cache.get("port"));
        System.out.println("  timeout = " + cache.get("timeout"));
    }
}
```

## Gotchas

### Reader Starvation When Writers Are Frequent

In non-fair mode, when writers keep arriving, the implementation may give priority to waiting writers over new readers to prevent writer starvation. However, in an extremely read-heavy workload where readers never all release the lock simultaneously, a writer may wait indefinitely. In fair mode, the FIFO queue prevents starvation but reduces throughput. There is no configuration that eliminates all starvation in all workloads.

### Lock Upgrading (Read to Write) Deadlocks

`ReentrantReadWriteLock` does not support lock upgrading. If thread A holds the read lock and tries to acquire the write lock, it will block waiting for all readers to release. But thread A itself is a reader, so it will wait for itself indefinitely — a single-thread deadlock. The correct pattern is to release the read lock, acquire the write lock, verify the state still requires modification (it may have changed), and then write.

### Forgetting to Unlock the Read Lock Before a Write

A method that acquires the read lock and then inside the `try` block determines it needs to write must not simply acquire the write lock — that would attempt a lock upgrade and deadlock. The read lock must be released in a `finally` block before trying to acquire the write lock. This requires restructuring the method so the read lock and write lock are in separate `try/finally` blocks.

### Read Lock Does Not Support Condition Variables

Only the write lock of a `ReentrantReadWriteLock` can create `Condition` objects. Calling `readLock().newCondition()` throws `UnsupportedOperationException`. This means the `ReadWriteLock` pattern is not directly suitable for producer-consumer coordination where readers need to wait for data to be available. For such patterns, use `ReentrantLock` with `Condition` objects instead.

### Read-Heavy Workloads Must Actually Be Read-Heavy

The performance benefit of `ReadWriteLock` appears only when reads genuinely dominate and when the protected operation takes non-trivial time (so that parallelism pays off). If the protected operation is a single field access lasting nanoseconds, the overhead of `ReentrantReadWriteLock` (which is more expensive than a plain `ReentrantLock`) will make performance worse, not better. Profile before adopting this pattern.

### Write Lock Acquisition Blocks All Incoming Readers

Once a write lock acquisition is pending, new read lock requests from other threads are blocked (in fair mode, and often in non-fair mode too, depending on implementation). If a writer takes a long time, a flood of readers will queue up and be delayed even after the writer completes. Long-held write locks negate the concurrency advantage entirely.
