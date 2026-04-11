# Active Object Pattern

## Overview

The Active Object pattern decouples method invocation from method execution by giving an object its own private thread of control. When a caller invokes a method on an Active Object proxy, the call is not executed in the caller's thread. Instead, the invocation is packaged as a method request object — carrying the call's arguments and a promise for the result — and placed into the Active Object's internal activation queue. The caller receives a `CompletableFuture` immediately and continues without blocking.

The Active Object's private scheduler thread runs independently, dequeuing method requests one at a time and executing them in sequence. Because all method execution happens on a single private thread, the Active Object's internal state is never accessed concurrently. This eliminates data races within the object without requiring any explicit synchronization on its fields. From the caller's perspective, method invocation looks nearly synchronous: submit a call, receive a future, retrieve the result when ready.

This pattern is well suited to I/O managers, logging services, stateful protocol handlers, and any component that must serialize access to shared state while presenting an asynchronous API to its clients. It is a higher-level abstraction than raw executor submission: the proxy enforces type-safe method dispatch, the scheduler enforces ordering, and the future propagates results and exceptions back to callers without any shared state between caller and object.

## Key Concepts

### Components

The Active Object pattern is composed of five collaborating parts.

The **Proxy** is the caller-facing interface. It accepts method calls, wraps each call's arguments into a method request object, places the request on the activation queue, and returns a `CompletableFuture` to the caller. The proxy is the only part the caller interacts with directly.

The **Activation Queue** is a thread-safe queue of pending method requests. It decouples the rate at which callers submit requests from the rate at which the scheduler can process them. In Java, this is typically a `BlockingQueue` or the internal task queue of an `ExecutorService`.

The **Scheduler** is the Active Object's private thread. It dequeues method requests and dispatches them for execution. Using a single-threaded executor as the scheduler preserves FIFO order and eliminates intra-object data races.

The **Method Request** encapsulates everything needed to execute one method call: the call's arguments and a `CompletableFuture` that will hold the result. It is typically expressed as a `Runnable` or `Callable` submitted to the executor.

The **Result Handle** is the `CompletableFuture<T>` returned to the caller at invocation time. The scheduler completes it (normally or exceptionally) when the request finishes executing.

```
Caller Thread          Active Object
     |                  +----------+
     |  invoke(args)    | Proxy    |
     |----------------->| enqueue  |-----> [Request Queue]
     |<- Future<Result> +----------+              |
     |                                     [Private Thread]
     |                                      execute request
     |                                      complete Future
     |  future.get()
     |<----- Result
```

### Why Use Active Object

Active Object makes asynchronous invocation look like synchronous calls from the caller's perspective. The object encapsulates its own thread and dispatch ordering; callers do not manage threads, submit to executors, or coordinate completion manually. The pattern also centralizes execution ordering: because all requests pass through a single queue and are processed by a single thread, the Active Object's internal state is always accessed by exactly one thread, making synchronization within the object unnecessary.

### Single-Threaded Scheduler

Using a single-threaded executor (`Executors.newSingleThreadExecutor()`) as the scheduler preserves FIFO order of method requests. All methods execute on the same thread, which eliminates data races within the Active Object itself — no `volatile`, no `synchronized`, and no `AtomicReference` on the object's internal fields. The trade-off is that execution is fully serialized: if one method request takes a long time, all others wait.

### Bounded Queue

An unbounded internal queue — the default when using `Executors.newSingleThreadExecutor()` — can accumulate requests indefinitely under overload. If the caller submits requests faster than the scheduler can process them, memory grows without bound until the JVM runs out of heap. For production use, construct the backing `ThreadPoolExecutor` with a bounded `ArrayBlockingQueue` and a rejection policy such as `CallerRunsPolicy` (which blocks the caller's thread when the queue is full, providing natural backpressure) or `AbortPolicy` (which throws `RejectedExecutionException` that the proxy converts into a failed future).

### Shutdown

The proxy must expose a `shutdown()` method that shuts down the underlying executor. Calling `executor.shutdown()` stops accepting new requests while allowing already-queued requests to drain and complete. The proxy should reject new calls after shutdown by completing returned futures exceptionally with a meaningful exception. Callers that have outstanding futures will still receive their results as pending requests are processed.

## Gotchas

**Unbounded activation queue causes heap exhaustion under overload.** The activation queue is unbounded by default when using `Executors.newSingleThreadExecutor()`. Under sustained overload, requests accumulate in memory without bound. Replace the default executor with a custom `ThreadPoolExecutor` backed by a bounded `ArrayBlockingQueue` and a `CallerRunsPolicy` or `AbortPolicy` rejection handler.

**Unhandled exceptions in method requests leave futures permanently incomplete.** Exceptions thrown during method execution must be propagated to the returned `CompletableFuture` via `completeExceptionally()`. If a method request catches an exception and does not call `completeExceptionally()`, the caller's `future.get()` blocks indefinitely with no timeout, and the thread holding that call is effectively leaked.

**The single-threaded scheduler is a serialization bottleneck.** All method calls are processed in sequence by one thread. If any single request is slow — due to I/O, a long computation, or a sleep — every subsequent request waits. For higher throughput, the scheduler can be replaced with a multi-threaded executor, but this removes FIFO ordering guarantees and reintroduces the need for synchronization on the Active Object's internal state.

**Calling `future.get()` from the scheduler thread causes a deadlock.** If code running on the Active Object's own scheduler thread (for example, a method request that calls another method on the same Active Object and then blocks on the returned future) calls `future.get()`, the scheduler is blocked waiting for a result that it cannot produce because it is itself the only thread that processes requests. The queue fills, and both threads hang permanently.

**Forgetting to shut down the executor prevents JVM exit.** A single-threaded executor created with `Executors.newSingleThreadExecutor()` spawns a non-daemon thread by default. If the Active Object is never shut down, the scheduler thread stays alive indefinitely and prevents the JVM from exiting after `main` returns. Always call `shutdown()` or `shutdownNow()` in a `finally` block or via a lifecycle hook.

**The proxy does not protect shared state accessed directly outside the queue.** The Active Object's internal state is safe only because all writes go through the scheduler thread via the queue. If any code on the caller's side reads or writes the Active Object's fields directly — bypassing the queue — those accesses are not protected. The encapsulation boundary must be enforced: no field of the Active Object should be readable or writable from outside the scheduler thread.
