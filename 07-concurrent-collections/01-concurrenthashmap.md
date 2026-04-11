# ConcurrentHashMap

## Overview

`ConcurrentHashMap` is a thread-safe implementation of `Map` designed for high-concurrency workloads. Unlike `Hashtable`, which serializes every operation through a single lock on the entire map, `ConcurrentHashMap` achieves concurrent access by locking only the individual hash bucket being modified. Readers are never blocked — most read operations proceed without acquiring any lock at all. This design allows many threads to read and write simultaneously, as long as they operate on different buckets.

The implementation changed significantly in Java 8. The earlier segment-based approach (where the map was divided into 16 fixed segments, each with its own `ReentrantLock`) was replaced with a finer-grained model using an array of `Node` objects. Structural modifications on a single bucket are protected by `synchronized` on that bucket's head node. Reads use `volatile` fields to observe the latest writes without locking. Tree bins (red-black trees) replace linked lists when a bucket's chain grows long, preserving O(log n) worst-case lookup.

One of the most useful aspects of `ConcurrentHashMap` is its set of atomic compound operations: `computeIfAbsent`, `compute`, `merge`, and `putIfAbsent`. These operations perform a read-then-write atomically at the key level, eliminating the need for external synchronization when aggregating or lazily initializing values. Without these methods, even a thread-safe map cannot protect you from a race in a multi-step check-then-act sequence performed by the caller.

`ConcurrentHashMap` is the go-to map for shared mutable state across threads. It does not, however, provide snapshot consistency across the entire map: iteration is weakly consistent, `size()` is approximate, and operations that span multiple keys are not atomic unless you design for that explicitly.

## Key Concepts

### Java 8+ Implementation

The backing store is an array of `Node<K,V>` entries. Each array slot is one bucket. When a thread writes to a bucket, it performs `synchronized (bucketHead)` to lock only that slot. Other buckets remain fully accessible. Reads of individual nodes use `volatile` reads on the `val` and `next` fields, so they are always seen fresh without a lock. When a bucket's chain exceeds a threshold (default 8 entries), the chain is converted to a `TreeBin` (a red-black tree) to keep lookup time bounded.

```
Array index:  0       1       2       3   ...
              |       |       |       |
             Node    null    Node    Node
              |               |       \
             Node            Node    TreeBin
```

Only the head node of each bucket is the monitor object for that bucket's write lock. Threads writing to bucket 0 and bucket 3 proceed in parallel.

### Null Prohibition

Neither keys nor values may be `null`. Attempting to insert a `null` key or value throws `NullPointerException`. This is a deliberate design choice: in a concurrent context, a `get` returning `null` is ambiguous — it could mean the key is absent, or that a `null` value was stored. By banning `null`, the map guarantees that `null` from `get` always means absence. `HashMap` allows `null` because single-threaded callers can call `containsKey` to disambiguate, but that two-step sequence is not safe in a concurrent map.

### Atomic Compound Operations

The following methods are guaranteed atomic at the key level. You do not need external synchronization when using them.

`putIfAbsent(key, value)` — inserts the value only if the key is currently absent. Returns the existing value if present, or `null` if the insert occurred.

`computeIfAbsent(key, mappingFunction)` — if the key is absent, calls the function to compute a value and inserts it atomically. The function is called at most once per key per invocation. Useful for lazy initialization of per-key data structures.

`compute(key, remappingFunction)` — atomically computes a new value for the key, regardless of whether it currently exists. The function receives the current value (or `null`) and returns the new value. Returning `null` removes the key.

`merge(key, value, remappingFunction)` — if the key is absent or its value is `null`, inserts the given value. Otherwise, calls the function with the existing value and the given value to produce the new value. Returning `null` from the function removes the key. Excellent for aggregating counts or accumulating collections per key.

```java
// Counting words atomically — no external synchronization needed
map.merge(word, 1, Integer::sum);

// Lazy initialization of a per-key list
map.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
```

### size() Approximation

`size()` sums internal counters maintained per CPU stripe (a `CounterCell[]` array, inspired by `LongAdder`). Because other threads may be modifying the map during this summation, the returned value is a snapshot estimate. It is suitable for monitoring and logging but should not be used to make correctness decisions, such as checking `if (map.size() == 0)` before taking an action that requires the map to be empty.

### Iteration and Weak Consistency

Iterators, `keySet()`, `values()`, `entrySet()`, `forEach`, and bulk operations are weakly consistent: they reflect some state of the map at or after the iterator was created. They will not throw `ConcurrentModificationException`. They may or may not reflect puts and removes that occurred after iteration began. The `forEach(parallelismThreshold, action)` method can use the common ForkJoinPool for bulk processing, but it does not lock the entire map, so the action must tolerate concurrent updates.

### Map Implementation Comparison

| Feature | HashMap | Hashtable | synchronizedMap | ConcurrentHashMap |
|---|---|---|---|---|
| Thread-safe | No | Yes | Yes | Yes |
| Lock granularity | N/A | Whole map | Whole map | Per bucket |
| Null keys/values | Yes | No | Depends on delegate | No |
| Iterator safe under concurrency | No (CME) | No (CME) | No (CME) | Yes (weakly consistent) |
| Atomic compound ops | No | No | No | Yes |
| Performance under contention | N/A | Poor | Poor | High |
| size() accuracy | Exact | Exact | Exact | Approximate |

## Gotchas

### Compound Check-Then-Act Is Not Atomic

Calling `containsKey` followed by `put` is a race condition even with `ConcurrentHashMap`. Another thread can insert the same key between your two calls. Always use `putIfAbsent`, `computeIfAbsent`, or `compute` when you need a read-then-write to be atomic. The map's thread safety only applies to individual method calls, not to sequences of calls.

### size() Cannot Be Trusted for Control Flow

`size()` returns an approximate value. Code like `if (map.size() > 0) { process(map.firstEntry()); }` is not safe: the map may have been emptied between the `size()` call and `firstEntry()`. Use `isEmpty()` for a quick empty check (it is also approximate but often sufficient), or restructure logic to avoid needing an exact size at a specific moment.

### Null Keys and Values Will Throw

Any attempt to call `put(null, value)`, `put(key, null)`, `get(null)`, or `computeIfAbsent(null, ...)` throws `NullPointerException`. If you are migrating code from `HashMap` that stored `null` values as sentinels, you must replace those sentinels with an explicit marker object before switching to `ConcurrentHashMap`.

### Iterators Reflect a Weakly Consistent View

An iterator obtained from `keySet().iterator()` or `entrySet().iterator()` may or may not see entries inserted after the iterator was created. It will never throw `ConcurrentModificationException`, but this can lead to subtle bugs if code assumes a fresh view. For an exact snapshot, copy the keys or entries into a new collection under external locking, accepting the performance cost.

### forEach Does Not Lock the Whole Map

`map.forEach((k, v) -> ...)` iterates without holding any global lock. If the action itself reads or writes the same map, or accumulates results into shared state, you need to handle that state's thread safety separately. The action is called once per entry as a snapshot, but concurrent mutations can interleave freely.

### Mapping Functions Must Not Modify the Map

The functions passed to `compute`, `computeIfAbsent`, and `merge` must not attempt to update any other key in the same `ConcurrentHashMap` during their execution. Doing so risks deadlock, because the map may hold an internal lock on the current bucket while calling your function, and a second update to a different key may try to acquire a lock that creates a cycle. Keep mapping functions pure and side-effect-free.
