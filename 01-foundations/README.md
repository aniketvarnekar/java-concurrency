# 01 — Foundations

This section builds the conceptual foundation for Java concurrency. It covers what threads are, how they differ from processes, how the JVM models thread state, and the mechanics of creating and managing threads. Mastering this material is prerequisite to every topic that follows.

## Notes

| File | Topic |
|------|-------|
| [01-processes-vs-threads.md](01-processes-vs-threads.md) | Process vs thread memory model, isolation, and creation cost |
| [02-thread-lifecycle.md](02-thread-lifecycle.md) | All six Thread.State values, transition diagram, and transition table |
| [03-creating-threads.md](03-creating-threads.md) | Thread subclass vs Runnable vs Callable, key Thread methods |
| [04-daemon-threads.md](04-daemon-threads.md) | Daemon thread semantics, JVM shutdown behavior, status inheritance |

## Examples

| File | Description |
|------|-------------|
| [examples/ThreadLifecycleDemo.java](examples/ThreadLifecycleDemo.java) | Demonstrates all six Thread.State values with live state printing |
| [examples/CreatingThreadsDemo.java](examples/CreatingThreadsDemo.java) | Demonstrates Thread subclass, Runnable, and Callable + FutureTask |
