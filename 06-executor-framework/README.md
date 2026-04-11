# 06 — Executor Framework

The Executor framework, introduced in Java 5, separates the concern of defining a task from the concern of running it. Rather than creating and managing `Thread` objects directly, you submit work to an `ExecutorService` and let the framework handle thread lifecycle, pooling, and scheduling. This separation produces code that is easier to reason about, test, and tune.

This section covers the full breadth of the framework: the `Executor` and `ExecutorService` interfaces, every built-in thread pool type, the internal parameters of `ThreadPoolExecutor`, task-result handling with `Future` and `Callable`, the non-blocking composition API of `CompletableFuture`, and time-based scheduling with `ScheduledExecutorService`.

## Contents — Notes

| File | Topic |
|---|---|
| [01-executor-and-executorservice.md](01-executor-and-executorservice.md) | Executor and ExecutorService interfaces, lifecycle (shutdown, awaitTermination) |
| [02-thread-pool-types.md](02-thread-pool-types.md) | All Executors factory methods, their internal configuration, and risks |
| [03-threadpoolexecutor-internals.md](03-threadpoolexecutor-internals.md) | ThreadPoolExecutor constructor parameters: queue, thread limits, rejection handlers |
| [04-future-and-callable.md](04-future-and-callable.md) | Callable, Future.get() blocking, cancellation, ExecutionException |
| [05-completablefuture.md](05-completablefuture.md) | CompletableFuture: thenApply, thenCompose, thenCombine, exceptionally, handle |
| [06-scheduledexecutorservice.md](06-scheduledexecutorservice.md) | scheduleAtFixedRate vs scheduleWithFixedDelay timing semantics |

## Contents — Examples

| Folder | Description |
|---|---|
| [examples/threadpooldemo/](examples/threadpooldemo/) | Custom ThreadPoolExecutor with bounded queue, CallerRunsPolicy, and named ThreadFactory |
| [examples/futuredemo/](examples/futuredemo/) | Callable submission, Future.get() with timeout, cancellation, and invokeAll |
| [examples/completablefuturedemo/](examples/completablefuturedemo/) | Async pipeline using supplyAsync, thenCompose, thenCombine, exceptionally, and whenComplete |
