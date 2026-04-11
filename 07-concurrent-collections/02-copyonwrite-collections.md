# Copy-On-Write Collections

## Overview

`CopyOnWriteArrayList` and `CopyOnWriteArraySet` are thread-safe collection classes that achieve read safety through a simple but powerful invariant: the underlying array is never mutated in place. Every write operation — `add`, `set`, `remove` — acquires a lock, copies the entire backing array, applies the modification to the copy, and then atomically publishes the new array via a `volatile` reference. After publication, all future readers immediately see the new array; all existing readers continue using the old array for the duration of their current operation.

This design means reads require no synchronization whatsoever. A reader simply loads the current `volatile` array reference and accesses it like any plain array. Because that array will never be changed, there are no torn reads, no visibility issues, and no risk of `ConcurrentModificationException`. Readers and writers never block each other.

The trade-off is write performance. Every mutation copies O(n) elements, even if only one element changed. For large collections or frequent writes, this becomes prohibitively expensive both in CPU time and GC pressure (the discarded old array becomes garbage). The copy-on-write classes are purpose-built for workloads that read far more often than they write: event listener lists, configuration snapshots, and infrequently updated lookup tables are canonical examples.

`CopyOnWriteArraySet` is backed by a `CopyOnWriteArrayList`. It enforces set semantics by scanning the list for duplicates on every add, which is O(n). This differs from `HashSet`'s O(1) add and means large `CopyOnWriteArraySet` instances become slow to populate. For small, read-dominant sets it is fine; for large sets or frequent inserts, it is not appropriate.

## Key Concepts

### Copy-On-Write Semantics

Every mutating method is synchronized on the list object. Inside the lock, the current backing array is copied into a new array of the appropriate size, the modification is applied to the new array, and the `volatile array` field is set to point to the new array. The lock is then released. The old array is not zeroed or poisoned — any reader currently iterating it continues unaffected.

```
Before add("D"):
  volatile ref --> [ A, B, C ]
                       ^-- readers see this

Writer thread acquires lock, copies:
  new array      --> [ A, B, C, D ]

Writer sets volatile ref:
  volatile ref --> [ A, B, C, D ]
                       ^-- new readers see this

Old readers still hold their reference to [ A, B, C ] until they finish.
```

### Lock-Free Reads

A read operation (get, contains, size, iteration) first loads the `volatile` array reference into a local variable. That local variable cannot change — the `volatile` write during a mutation does not retroactively change a reader's local copy of the old reference. All subsequent reads of elements use the local snapshot. This is the Java Memory Model guarantee: a `volatile` write happens-before all subsequent `volatile` reads of the same field, so any reader that loads the new reference is guaranteed to see all elements written to the new array.

### Iteration Safety

An iterator obtained from a `CopyOnWriteArrayList` captures the current array reference at the time `iterator()` is called. The iterator then traverses that fixed array. Any additions or removals that occur after `iterator()` is called are invisible to that iterator. The iterator will never throw `ConcurrentModificationException` because it never checks for modification — there is nothing to check. The snapshot it holds is immutable.

```java
// This loop is safe even if another thread calls list.add() concurrently.
for (String element : copyOnWriteList) {
    process(element); // no ConcurrentModificationException risk
}
```

Note that `remove()` on the iterator is not supported and throws `UnsupportedOperationException`.

### Performance Trade-offs

| Operation | CopyOnWriteArrayList | ArrayList + synchronizedList |
|---|---|---|
| Read (get, contains) | O(1), no lock | O(1), acquires lock |
| Iteration | O(n), no lock, snapshot | O(n), caller must lock externally |
| Write (add, remove) | O(n) — full array copy + lock | O(n) amortized, acquires lock |
| ConcurrentModificationException risk | Never | If external lock is missed |
| Memory during write | 2x array size | 1x array size |
| Best for | Many readers, rare writes | Balanced or write-heavy |

### When to Use

The copy-on-write classes shine when three conditions hold simultaneously: the collection is iterated far more often than it is mutated, mutation frequency is low enough that the O(n) copy cost is acceptable, and iteration must proceed concurrently without any external locking. Event listener lists are the canonical example: they are set up once or changed rarely, but the dispatch loop runs on every event. Observable/observer patterns, read-only configuration snapshots reloaded occasionally, and plugin registries all fit this pattern well.

When writes are frequent or the collection is large, the copy overhead dominates and a `ConcurrentHashMap`-backed set or a queue-based structure is more appropriate.

## Gotchas

### Writes Are O(n) — Cost Compounds With Size

Every `add`, `set`, or `remove` copies the entire backing array. For a list of 10,000 elements, each write allocates a new 10,000-element array. If writes happen frequently — say, every few milliseconds under load — GC pressure from discarded arrays accumulates. Profile allocation rate before choosing this class for anything with more than a few hundred elements or more than occasional writes.

### Iterators Show a Snapshot, Not the Live List

An iterator captures the array at the time `iterator()` is called. Writes that happen during iteration are not visible to that iterator. This is by design, but it can surprise developers who expect to see newly added elements in the same pass. If you need to process all elements including those added concurrently, iteration over a copy-on-write collection is not the right tool — consider a `ConcurrentLinkedQueue` or `ConcurrentHashMap` depending on the shape of the data.

### Removing Elements During Iteration Is Unsupported

Unlike `ArrayList`, the `Iterator` returned by `CopyOnWriteArrayList` does not support `remove()`. Calling it throws `UnsupportedOperationException`. To remove elements based on a predicate, use `removeIf(predicate)` on the list directly, which is atomic per-call and safe under concurrency.

### CopyOnWriteArraySet Has O(n) Add Cost

`CopyOnWriteArraySet.add` must scan the entire backing list to check for duplicates before inserting. This makes add O(n) — doubly expensive: O(n) to check and O(n) to copy the array. A set with 1,000 elements can tolerate this for rare inserts. A set that grows incrementally to 100,000 elements while under load will not.

### The Class Does Not Provide Atomic Compound Operations

There is no `addIfAbsent` that is also atomic with respect to a custom predicate beyond equality. `CopyOnWriteArrayList` does provide `addIfAbsent(e)` (checks for element equality), but complex conditional add-or-update logic still requires external synchronization. If you need map-style atomic compound semantics, `ConcurrentHashMap` is the right structure.

### Memory Doubles Transiently During Every Write

During the execution of a write, both the old array and the new copy exist simultaneously in the heap. For a large list, this can cause unexpected memory pressure spikes. In memory-constrained environments or with very large lists, this transient doubling can trigger GC pauses at exactly the moment a write occurs.
