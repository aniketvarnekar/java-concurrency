# Advanced Topics

This section covers advanced and modern Java concurrency topics: the Fork/Join framework for recursive parallelism, parallel streams, virtual threads (Project Loom), structured concurrency, lock-free algorithms, and the LMAX Disruptor pattern. These topics are relevant for performance-critical systems and for understanding the direction of Java concurrency post-Java 21.

Prerequisites: familiarity with the Java memory model, `java.util.concurrent` primitives, and the synchronization patterns covered in earlier sections.

## Contents

### Guides

| File | Description |
|---|---|
| [01-fork-join-framework.md](./01-fork-join-framework.md) | Work-stealing algorithm, RecursiveTask, ForkJoinPool |
| [02-parallel-streams.md](./02-parallel-streams.md) | Parallel stream pitfalls: shared state, stateful ops, ordering |
| [03-virtual-threads-loom.md](./03-virtual-threads-loom.md) | Virtual thread mount/unmount, carrier threads, pinning |
| [04-structured-concurrency.md](./04-structured-concurrency.md) | StructuredTaskScope, ShutdownOnFailure, ShutdownOnSuccess |
| [05-lock-free-algorithms.md](./05-lock-free-algorithms.md) | Progress guarantees, CAS loops, Treiber stack |
| [06-disruptor-pattern.md](./06-disruptor-pattern.md) | Ring buffer, sequence barriers, false sharing and padding |

### Examples

| Folder | Description |
|---|---|
| [examples/forkjoindemo/](examples/forkjoindemo/) | Parallel array sum using RecursiveTask, comparing custom pool vs commonPool |
| [examples/virtualthreadsdemo/](examples/virtualthreadsdemo/) | Virtual thread scale, executor usage, and pinning vs ReentrantLock |
| [examples/structuredconcurrencydemo/](examples/structuredconcurrencydemo/) | ShutdownOnFailure and ShutdownOnSuccess with StructuredTaskScope |
