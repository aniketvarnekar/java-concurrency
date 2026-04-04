# 09 - Concurrency Patterns

This section covers established patterns for structuring concurrent Java programs. Each pattern solves a recurring coordination problem — decoupling producers from consumers, managing thread reuse, protecting shared data, or building asynchronous APIs — and understanding them as patterns makes it easier to recognize where they apply in real code.

The patterns here build on the primitives covered in earlier sections (locks, atomics, blocking queues) and show how those primitives compose into larger designs.

## Contents — Notes

| File | Topic |
|------|-------|
| [01-producer-consumer.md](01-producer-consumer.md) | Canonical producer-consumer pattern using BlockingQueue with poison pill shutdown |
| [02-thread-pool-pattern.md](02-thread-pool-pattern.md) | Thread pool sizing heuristics for CPU-bound vs IO-bound workloads |
| [03-read-write-lock-pattern.md](03-read-write-lock-pattern.md) | Read-write lock pattern with cache invalidation and double-check |
| [04-active-object.md](04-active-object.md) | Active Object pattern: async method invocation with private thread and Future results |
| [05-reactor-pattern.md](05-reactor-pattern.md) | Reactor pattern: single-threaded event loop with NIO Selector and non-blocking handlers |
| [06-thread-local-storage.md](06-thread-local-storage.md) | ThreadLocal use cases, InheritableThreadLocal, and memory leak prevention in thread pools |

## Contents — Examples

| File | Description |
|------|-------------|
| [examples/ProducerConsumerDemo.java](examples/ProducerConsumerDemo.java) | Two producers, three consumers, ArrayBlockingQueue, poison pill shutdown with timestamps |
| [examples/ThreadLocalDemo.java](examples/ThreadLocalDemo.java) | Per-thread transaction ID isolation and remove() cleanup in simulated thread pool |
