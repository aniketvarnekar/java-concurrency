# Blocking Queues

## Overview

`BlockingQueue<E>` is an interface in `java.util.concurrent` that extends `Queue` with two additional blocking operations: `put(e)`, which blocks the calling thread until space is available to insert an element, and `take()`, which blocks until an element is available to remove. These two operations transform a plain queue into a coordination mechanism between producer and consumer threads, eliminating the need for manual `wait`/`notify` loops.

Before `BlockingQueue` existed, implementing a producer-consumer pipeline required the developer to write synchronized blocks, check a condition, call `wait`, handle spurious wakeups, call `notifyAll`, and manage the associated bookkeeping. All of that complexity is encapsulated inside the blocking queue implementations. The producer simply calls `put`, the consumer simply calls `take`, and the queue handles all waiting and signaling internally.

The `java.util.concurrent` package provides five `BlockingQueue` implementations: `LinkedBlockingQueue`, `ArrayBlockingQueue`, `PriorityBlockingQueue`, `DelayQueue`, and `LinkedTransferQueue`. Each has distinct memory characteristics, ordering semantics, and boundedness behavior that makes it more or less appropriate for a given situation. Choosing the wrong implementation — especially confusing a bounded queue with an unbounded one — is a common source of production memory exhaustion.

A thread blocked on `put` or `take` is interruptible: calling `interrupt()` on a blocked thread causes the operation to throw `InterruptedException`. This is essential for clean shutdown. The `offer` and `poll` variants with timeouts provide a middle ground: they block for at most a specified duration and return a status indicating success or failure.

## Key Concepts

### Core Methods

`put(e)` blocks the calling thread indefinitely (or until interrupted) until there is room in the queue, then inserts the element. This is appropriate when the producer must not drop items.

`take()` blocks the calling thread indefinitely until an element is available, then removes and returns it. This is appropriate when the consumer must process every item.

`offer(e, timeout, unit)` attempts to insert within the timeout. Returns `true` on success, `false` if the queue was still full after the timeout expired. Useful when the producer must cap how long it waits.

`poll(timeout, unit)` attempts to remove within the timeout. Returns the element on success, `null` if empty after the timeout. Useful when the consumer should not wait forever.

`remainingCapacity()` returns the number of additional elements the queue can accept before blocking puts. Returns `Integer.MAX_VALUE` for unbounded queues.

`drainTo(Collection<? super E> c)` atomically moves as many elements as currently available into the target collection. Efficient for batch consumption: a single lock acquisition removes many elements at once.

### LinkedBlockingQueue

`LinkedBlockingQueue` is an optionally-bounded queue backed by a singly-linked list. When constructed without a capacity argument (`new LinkedBlockingQueue<>()`), its capacity is `Integer.MAX_VALUE`, making it effectively unbounded. When bounded (`new LinkedBlockingQueue<>(capacity)`), it behaves like any bounded blocking queue.

It maintains two separate locks — one for the head (take/poll) and one for the tail (put/offer). This "two-lock" design allows a producer and a consumer to operate concurrently without contention, giving it higher throughput than `ArrayBlockingQueue` under high load. The trade-off is slightly higher memory overhead per element due to node objects.

### ArrayBlockingQueue

`ArrayBlockingQueue` is a strictly bounded queue backed by a circular array. Its capacity is set at construction and cannot change. It uses a single `ReentrantLock` shared by both producers and consumers, which means producers and consumers do contend with each other. However, the array backing gives it lower per-element memory overhead than the linked variant.

The constructor accepts an optional `fair` boolean: `new ArrayBlockingQueue<>(capacity, true)`. Fair mode uses a FIFO ordering of waiting threads, preventing starvation but reducing overall throughput. Unfair mode (the default) allows the thread scheduler to pick any waiting thread, which produces better throughput in aggregate.

### PriorityBlockingQueue

`PriorityBlockingQueue` is an unbounded priority queue. Elements must implement `Comparable`, or a `Comparator` must be supplied at construction. `take()` always removes the element with the highest priority (smallest according to the ordering). There is no blocking on `put` because the queue is unbounded — it will always accept new elements. `take()` blocks when empty, as with all blocking queues.

Because it is unbounded, a slow consumer can allow the queue to grow without limit. There is no back-pressure on producers. Memory exhaustion is a real risk if producers outpace consumers over time.

### DelayQueue

`DelayQueue` holds elements that implement the `Delayed` interface, which requires a `getDelay(TimeUnit unit)` method. `take()` blocks until the element at the head of the queue has a delay of zero or less (its scheduled time has arrived or passed). Elements are ordered by their delay. This makes `DelayQueue` a natural fit for task schedulers, retry queues with exponential backoff, and session expiration trackers.

### LinkedTransferQueue

`LinkedTransferQueue` is an unbounded queue that is a superset of `LinkedBlockingQueue`. Its distinguishing operation is `transfer(e)`: the producer blocks until a consumer has received the element, guaranteeing a direct handoff with no intermediate buffering. This is like a one-to-one synchronous delivery embedded inside a queue. `put` still adds to the queue without waiting for a consumer. `transfer` is the unique addition.

### Queue Comparison Table

| Queue | Bounded? | Fair mode? | Ordering | Primary use case |
|---|---|---|---|---|
| LinkedBlockingQueue | Optional | No | FIFO | General producer-consumer, high throughput |
| ArrayBlockingQueue | Yes (required) | Optional | FIFO | Bounded producer-consumer, lower memory overhead |
| PriorityBlockingQueue | No | No | Priority | Task scheduling with priorities |
| DelayQueue | No | No | Delay expiry | Scheduled tasks, TTL-based eviction |
| LinkedTransferQueue | No | No | FIFO + transfer | Direct handoff with queue fallback |
| SynchronousQueue | Zero capacity | Optional | N/A | Direct handoff, no buffering |

## Gotchas

### Unbounded LinkedBlockingQueue Can Exhaust Memory

Constructing `new LinkedBlockingQueue<>()` without a capacity creates a queue with a limit of `Integer.MAX_VALUE`. If producers are faster than consumers, the queue grows without bound. In production, this manifests as a gradual heap increase followed by `OutOfMemoryError`. Always provide an explicit capacity unless you have a specific reason to allow unbounded growth and have verified it will not happen in practice.

### PriorityBlockingQueue Has No Back-Pressure

Because `PriorityBlockingQueue` is unbounded, `put` never blocks. If your code relies on blocking producers to slow down when consumers are slow, `PriorityBlockingQueue` provides no such mechanism. You must implement back-pressure externally, or switch to a bounded queue with priority ordering implemented via a custom strategy.

### Null Elements Are Prohibited

All `BlockingQueue` implementations reject `null` elements. `poll` and `peek` return `null` as a sentinel meaning "no element available", so allowing stored `null` would make those return values ambiguous. If you need to represent an absence of a value as a queue item, use an `Optional` wrapper or a dedicated sentinel object like the poison pill pattern shown above.

### drainTo for Batch Processing

Calling `take` in a loop one element at a time is less efficient than calling `drainTo(collection, maxElements)` when a consumer can process multiple items together. `drainTo` acquires the lock once and removes all currently available elements atomically in a single operation. This is especially valuable when downstream processing benefits from batching (bulk database inserts, batch network sends).

### Fairness Mode Reduces Throughput

`ArrayBlockingQueue(capacity, true)` with fair mode can reduce throughput significantly under high contention because every thread waits in a strict FIFO queue for the lock. Unfair mode allows the OS scheduler more freedom and typically yields better aggregate throughput. Use fairness only when you have a demonstrated starvation problem, not as a default.

### Interrupted Threads Leave Items Unconsumed

If a consumer thread is interrupted while blocked on `take`, it exits without consuming the item it was about to receive. If that item was the last item and other consumers are also stopping, it may be lost. The poison pill pattern above handles this by re-enqueuing the pill, but for general items you must decide whether to re-enqueue them, log them, or accept the loss as part of graceful shutdown.
