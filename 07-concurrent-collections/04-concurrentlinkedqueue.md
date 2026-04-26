# ConcurrentLinkedQueue

## Overview

`ConcurrentLinkedQueue<E>` is a non-blocking, unbounded, thread-safe FIFO queue. Unlike the blocking queue implementations, it never suspends the calling thread. Producers always enqueue immediately; consumers poll and receive `null` if the queue is empty rather than waiting. This makes it appropriate for workloads where threads cannot afford to block and where an empty queue is a normal condition to be handled by the caller rather than waited on.

The implementation is based on the Michael-Scott non-blocking queue algorithm (Michael and Scott, 1996), which uses two atomic pointer variables — one pointing at the logical head and one at the logical tail — updated exclusively through compare-and-swap (CAS) operations. Because CAS is a hardware instruction on modern processors, the algorithm is genuinely lock-free: no thread ever holds a mutex, and a slow or failed thread cannot prevent other threads from making progress.

The absence of locks has an important implication for throughput: under high contention, lock-free structures avoid the convoy effect that can plague lock-based structures. When a lock holder is preempted by the OS, all waiting threads stall. In a CAS-based structure, a preempted thread causes other threads to retry their CAS operations, but they can still complete their own operations without waiting for the preempted thread to resume.

`ConcurrentLinkedQueue` is the right choice when you need a high-throughput, unordered work queue where producers and consumers run at their own pace, polling is acceptable, and memory is bounded by application logic rather than a queue capacity parameter.

## Key Concepts

### Michael-Scott Algorithm

The queue maintains two `volatile` node references: `head` and `tail`. Each node holds a value and a `volatile` next pointer. Initially, a dummy sentinel node serves as both head and tail.

```
Initial state:
  head --> [sentinel | next=null] <-- tail

After enqueue("A"):
  head --> [sentinel | next-->] --> [A | next=null] <-- tail

After enqueue("B"):
  head --> [sentinel | next-->] --> [A | next-->] --> [B | next=null] <-- tail

After dequeue() returns "A":
  head --> [A | next-->] --> [B | next=null] <-- tail
  (the old sentinel is GC'd; A's node becomes the new sentinel)
```

Enqueue (simplified): read the current tail node T. If T.next is null, CAS T.next from null to the new node. If successful, CAS tail from T to the new node. If T.next was already non-null (another thread is mid-enqueue), help advance tail first.

Dequeue (simplified): read the current head node H and its successor S. If S is null, the queue is empty. Otherwise, CAS head from H to S and return S's value.

Both operations retry in a loop if their CAS fails, which happens when another thread modified the pointer concurrently. Because at least one thread's CAS succeeds per round of retries, the algorithm is lock-free.

### Non-Blocking Property

"Non-blocking" means no thread is ever suspended by this data structure. If a CAS fails, the thread retries immediately. No thread holds a lock that another thread must wait for. The algorithm is specifically lock-free (a stronger guarantee than non-blocking): in every finite window of time, at least one thread makes progress, regardless of what other threads do.

This is distinct from wait-free, where every individual thread is guaranteed to complete its operation in a bounded number of steps. Lock-free structures may cause high-contention threads to retry many times, but they cannot deadlock or livelock in the same way lock-based structures can.

### size() Is O(n)

`size()` traverses the entire linked list by following next pointers from head to tail and counting nodes. This is O(n) and gives an approximate result because concurrent enqueues and dequeues can occur during traversal. Do not call `size()` in hot loops, in tight polling loops, or in any code path where O(n) traversal is a performance concern. To check for emptiness, use `isEmpty()`, which checks only whether `head.next == null` (also approximate, but O(1)).

### Null Prohibition

`null` elements are not permitted. `offer` and `add` throw `NullPointerException` for null elements. `poll` and `peek` return `null` as the signal for an empty queue, so allowing stored nulls would make those signals ambiguous. Use an explicit sentinel object if you need to enqueue a "no value" concept.

### Use Cases

`ConcurrentLinkedQueue` fits well as a task queue where worker threads poll for work, a message buffer between pipeline stages where stages run at independent rates, a collector of results from multiple producer threads before a single consumer aggregates them, and any scenario where you want FIFO ordering without the overhead of locking.

It is not appropriate when you need the consumer to block until work is available (use `LinkedBlockingQueue` instead), when you need bounded capacity to apply back-pressure (use `ArrayBlockingQueue`), or when you need priority ordering (use `PriorityBlockingQueue`).

## Gotchas

### size() Is O(n) — Never Call in a Hot Path

`size()` traverses the entire linked list. On a queue with 100,000 elements, it visits all 100,000 nodes. If multiple threads call `size()` concurrently, each pays the full O(n) traversal cost simultaneously. Use `isEmpty()` to check for emptiness, and track queue depth externally with an `AtomicInteger` if you need an approximate count efficiently.

### Bulk Operations Are Not Atomic

`addAll(collection)` calls `add` for each element sequentially. Another thread can interleave its own enqueues or dequeues between any two of these individual adds. The resulting queue may have your batch interleaved with other threads' items. If atomic batch insertion is required, use a locking strategy or restructure the design.

### No Blocking take() — Consumers Must Poll

`ConcurrentLinkedQueue` has no `take()` method. Consumers must poll and handle the `null` return. Naive busy-polling at 100% CPU is wasteful. Use `Thread.yield()` as a minimal backoff, or a short `LockSupport.parkNanos` for a less aggressive spin. If the consumer should block until work is available rather than polling at all, switch to a `BlockingQueue` such as `LinkedBlockingQueue` and call `take()`.

### Null Elements Cause NullPointerException

`offer(null)` throws `NullPointerException` immediately. `poll()` and `peek()` return `null` to signal an empty queue. These two uses of `null` are incompatible, which is why the class refuses to store null elements. Design your data types to use explicit sentinel objects or `Optional` wrappers when you need to convey absence as a value.

### isEmpty() and size() Are Not Consistent With Each Other

Because both `isEmpty()` and `size()` observe the queue at different moments in time and without a lock, calling both in sequence does not give you a consistent snapshot. `isEmpty()` may return `false` followed by a `size()` of 0 if an element was dequeued in between. Do not write logic that depends on the two being consistent.

### Memory Is Unbounded

There is no capacity limit. A producer that is faster than its consumer will grow the queue indefinitely until the JVM runs out of heap. If back-pressure is required — that is, if producers should slow down or block when consumers are behind — switch to a bounded `BlockingQueue`. `ConcurrentLinkedQueue` has no mechanism to apply that pressure.
