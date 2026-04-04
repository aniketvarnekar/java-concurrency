# 07 — Concurrent Collections

The Java platform ships a rich set of thread-safe collection classes in `java.util.concurrent` that go far beyond the coarse-grained locking of `Collections.synchronizedList` or `Hashtable`. Each class is tuned for a specific concurrency pattern, offering fine-grained locking, lock-free algorithms, or copy-on-write semantics depending on the workload. Understanding which collection to reach for — and why — is one of the most practical skills in concurrent Java programming.

## Contents — Notes

| File | Topic |
|---|---|
| [01-concurrenthashmap.md](01-concurrenthashmap.md) | Node-level locking, atomic compute/merge operations, and iteration semantics |
| [02-copyonwrite-collections.md](02-copyonwrite-collections.md) | Copy-on-write read/write trade-offs and snapshot-safe iteration |
| [03-blocking-queues.md](03-blocking-queues.md) | All BlockingQueue implementations, their queue discipline, and producer-consumer use |
| [04-concurrentlinkedqueue.md](04-concurrentlinkedqueue.md) | CAS-based non-blocking FIFO queue and the Michael-Scott algorithm |
| [05-synchronousqueue.md](05-synchronousqueue.md) | Zero-capacity direct-handoff channel and its role in cached thread pools |

## Contents — Examples

| File | Description |
|---|---|
| [examples/ConcurrentHashMapDemo.java](examples/ConcurrentHashMapDemo.java) | Thread-safe word frequency counting using compute, merge, and computeIfAbsent |
| [examples/BlockingQueueProducerConsumer.java](examples/BlockingQueueProducerConsumer.java) | Multi-producer multi-consumer pipeline with ArrayBlockingQueue and poison-pill shutdown |
| [examples/CopyOnWriteDemo.java](examples/CopyOnWriteDemo.java) | Iteration safety during concurrent modification with CopyOnWriteArrayList |
