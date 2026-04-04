# Read-Write Lock Pattern

## Overview

The read-write lock pattern is an optimization for shared data that is read far more often than it is written. A plain `synchronized` block or `ReentrantLock` allows only one thread to access the protected resource at a time, regardless of whether that access is a read or a write. This is unnecessarily restrictive: concurrent reads are safe because they do not modify state.

`ReentrantReadWriteLock` provides two locks backed by a single implementation: a read lock that multiple threads can hold simultaneously, and a write lock that only one thread can hold, and only when no read locks are held. This allows reads to proceed concurrently in the common case and serializes writes for correctness.

The pattern is most effective when reads are frequent and fast, writes are rare and possibly slow, and the critical section is coarse enough that the locking overhead does not dominate. A cache is the canonical application: many threads read cached values simultaneously, while occasional refresh operations update the cache exclusively.

If writes are frequent — approaching the same rate as reads — the write lock's exclusivity means readers are often blocked, and the concurrency benefit diminishes. In extreme cases, a high-write workload under `ReentrantReadWriteLock` performs worse than a plain mutex because the read-write lock has higher overhead per acquisition.

## Key Concepts

### Cache with Read-Write Lock

A cache stores computed or fetched results so they can be returned quickly without re-computation. The cache has two operations: `get()` returns a cached value if present, and `refresh()` updates the cache with a new value (typically fetched from a database or computed from scratch).

Multiple threads can safely call `get()` concurrently because reading from a `ConcurrentHashMap` or a `HashMap` under a read lock does not modify state. The read lock allows these concurrent reads.

`refresh()` modifies the cache — it must be exclusive. It acquires the write lock, which blocks all readers and other writers until the refresh completes.

```java
private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
private final Lock readLock  = rwl.readLock();
private final Lock writeLock = rwl.writeLock();
private final Map<String, String> cache = new HashMap<>();

public String get(String key) {
    readLock.lock();
    try {
        return cache.get(key);
    } finally {
        readLock.unlock();
    }
}

public void refresh(String key, String value) {
    writeLock.lock();
    try {
        cache.put(key, value);
    } finally {
        writeLock.unlock();
    }
}
```

### Cache Invalidation

When the backing data source changes, the cached value becomes stale and must be invalidated. Invalidation acquires the write lock, removes or replaces the entry, then releases the lock. Readers blocked during invalidation see the updated state immediately after the write lock is released.

The critical window is between invalidation and re-population: any reader that acquires the read lock after invalidation but before re-population will see a cache miss. The system must handle this case — either by fetching on miss (lazy loading) or by refreshing the entry before releasing the write lock (eager loading).

### Double-Check Pattern

A cache miss in `get()` means the value is absent. To populate it, the read lock must be released and the write lock acquired. However, between releasing the read lock and acquiring the write lock, another thread may have already populated the entry. The double-check pattern accounts for this:

```java
public String getOrLoad(String key) {
    // First check under read lock
    readLock.lock();
    try {
        String value = cache.get(key);
        if (value != null) return value;
    } finally {
        readLock.unlock();
    }

    // Miss: acquire write lock and check again
    writeLock.lock();
    try {
        // Second check: another thread may have populated between the two locks
        String value = cache.get(key);
        if (value != null) return value;

        // Still missing: compute and store
        String computed = expensiveLoad(key);
        cache.put(key, computed);
        return computed;
    } finally {
        writeLock.unlock();
    }
}
```

Without the second check inside the write lock, multiple threads that all saw a miss under the read lock would each call `expensiveLoad()`, causing redundant work (cache stampede).

### Lock Downgrading in Cache

Lock downgrading means acquiring the write lock, doing a write, then acquiring the read lock while still holding the write lock, and then releasing the write lock. This ensures no other writer can intervene between the update and the subsequent read.

`ReentrantReadWriteLock` supports downgrading (write → read) but not upgrading (read → write). An attempt to acquire the write lock while holding the read lock deadlocks.

```java
writeLock.lock();
try {
    cache.put(key, newValue);
    readLock.lock();   // acquire read lock before releasing write lock
} finally {
    writeLock.unlock(); // downgrade: now holding only read lock
}
try {
    return cache.get(key); // read under read lock, no writer can intervene
} finally {
    readLock.unlock();
}
```

Downgrading is useful when you need to atomically update and then return the new value to the caller while guaranteeing no other writer modifies the entry between the update and the return.

### StampedLock Alternative

`StampedLock` (Java 8+) offers optimistic reads: a read that does not acquire any lock. The reader takes a stamp, reads the data, and then validates the stamp. If validation succeeds, no writer modified the data during the read. If validation fails, the reader retries under a full read lock.

This is faster than `ReentrantReadWriteLock` under low contention because optimistic reads have no lock acquisition overhead. The tradeoff is more complex code and the requirement that the protected data can be read in a state that may be inconsistent (the code must handle the retry path correctly).

For most cache implementations, `ReentrantReadWriteLock` is the right choice. Reach for `StampedLock` only when profiling shows the lock is a bottleneck.

## Code Snippet

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class ReadWriteCacheExample {

    static class Cache {
        private final Map<String, String> store = new HashMap<>();
        private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
        private final Lock readLock  = rwl.readLock();
        private final Lock writeLock = rwl.writeLock();

        /** Returns cached value, or null on miss. */
        public String get(String key) {
            readLock.lock();
            try {
                return store.get(key);
            } finally {
                readLock.unlock();
            }
        }

        /**
         * Returns cached value, loading from backing store on miss.
         * Uses double-check to avoid redundant loads.
         */
        public String getOrLoad(String key) {
            readLock.lock();
            try {
                String v = store.get(key);
                if (v != null) return v;
            } finally {
                readLock.unlock();
            }

            writeLock.lock();
            try {
                // Double-check after acquiring write lock
                String v = store.get(key);
                if (v != null) return v;

                String loaded = "value-for-" + key; // simulate DB fetch
                store.put(key, loaded);
                System.out.printf("[%s] loaded key=%s%n",
                    Thread.currentThread().getName(), key);
                return loaded;
            } finally {
                writeLock.unlock();
            }
        }

        /** Invalidates and refreshes an entry under write lock. */
        public void refresh(String key, String newValue) {
            writeLock.lock();
            try {
                store.put(key, newValue);
                System.out.printf("[%s] refreshed key=%s -> %s%n",
                    Thread.currentThread().getName(), key, newValue);
            } finally {
                writeLock.unlock();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Cache cache = new Cache();
        ExecutorService executor = Executors.newFixedThreadPool(6);

        // Seed one key
        cache.refresh("config", "v1");

        // Many readers
        for (int i = 0; i < 10; i++) {
            final int id = i;
            executor.submit(() -> {
                String val = cache.getOrLoad("config");
                System.out.printf("[reader-%d] got: %s%n", id, val);
            });
        }

        // Occasional writer
        executor.submit(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
            cache.refresh("config", "v2");
        });

        // More readers after the refresh
        for (int i = 10; i < 16; i++) {
            final int id = i;
            executor.submit(() -> {
                String val = cache.get("config");
                System.out.printf("[reader-%d] got: %s%n", id, val);
            });
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
```

## Gotchas

### Attempting to Upgrade from Read Lock to Write Lock

`ReentrantReadWriteLock` does not support lock upgrading. If a thread holding the read lock tries to acquire the write lock, it will deadlock: the write lock waits for all readers to finish, but the current thread is one of those readers and will never finish because it is waiting. Always release the read lock before acquiring the write lock.

### Skipping the Double-Check Inside the Write Lock

After a cache miss under the read lock, if you acquire the write lock and immediately compute the value without re-checking the cache, multiple threads that all saw the miss will each compute the value. Only one computation's result will survive, but the extra work is wasted. The second check inside the write lock is not optional — it is a correctness optimization that prevents the cache stampede problem.

### Read Starvation When Writes Are Frequent

`ReentrantReadWriteLock` gives priority to writers by default on some JVM implementations, or uses a fair mode that processes in FIFO order. In the non-fair (default) mode under heavy write load, readers may find that a write lock is acquired immediately each time they release it, preventing readers from making progress. If write frequency approaches read frequency, a plain `ReentrantLock` may provide better and more predictable throughput.

### Not Using finally for Lock Release

Failing to call `unlock()` in a `finally` block means any exception thrown in the protected block leaves the lock permanently held. All subsequent threads attempting to acquire the lock will block forever. This applies to both the read and write lock. Every `lock()` call must be paired with an `unlock()` in a `finally` block.

### Cache Stampede on Cold Start

When many threads attempt to load the same key simultaneously before it is populated, the double-check pattern ensures only one thread computes the value. However, if there are many distinct keys that all miss simultaneously, many write lock acquisitions will occur in sequence, each blocking all readers. For very high concurrency with many distinct keys, consider using `ConcurrentHashMap.computeIfAbsent()` which provides per-key locking without a global write lock.

### Lock Downgrade Is One-Way

Downgrading from write to read lock is supported. The reverse — holding a read lock and acquiring a write lock (upgrading) — is not supported and causes a deadlock. If you see code that calls `readLock.lock()` and then later calls `writeLock.lock()` without an intervening `readLock.unlock()`, it will deadlock whenever more than one thread is in that path simultaneously.
