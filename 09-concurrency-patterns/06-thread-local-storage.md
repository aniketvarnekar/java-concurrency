# Thread-Local Storage

## Overview

`ThreadLocal<T>` provides a separate instance of a variable for each thread that accesses it. Each thread reads and writes only its own copy; no synchronization is required because threads never share `ThreadLocal` values with one another. Unlike shared concurrency primitives — which coordinate threads that must access a common resource — `ThreadLocal` is fundamentally anti-sharing: it isolates state so that each thread operates on its own independent copy, eliminating the need for coordination entirely.

The canonical use cases are per-thread formatting objects, per-request context propagation in web frameworks, and per-thread database connections in legacy patterns. `SimpleDateFormat`, for example, is not thread-safe and is expensive to construct. Rather than paying construction cost on every call or synchronizing a shared instance, each thread maintains its own `ThreadLocal<SimpleDateFormat>`. Web frameworks store the current user identity, transaction ID, and locale in a `ThreadLocal` at the start of each request so that any code in the call stack can retrieve this context without passing it through every method parameter.

The critical operational concern with `ThreadLocal` is memory leaks in thread pools. Pool threads live for the lifetime of the pool — potentially the entire JVM lifetime. A `ThreadLocal` value set during request processing and not removed persists in the thread's internal map across all future requests. If the value references a large object, a classloader, or any other long-lived structure, it is never garbage-collected while the thread lives. The fix is unconditional: always call `ThreadLocal.remove()` in a `finally` block after request processing completes.

## Key Concepts

### `ThreadLocal<T>` API

The core API is minimal. `get()` returns the calling thread's value for this `ThreadLocal`, initializing it via `initialValue()` if this thread has never set a value. `set(T value)` sets the calling thread's value. `remove()` removes the calling thread's entry from the underlying `ThreadLocalMap`, allowing the value to be garbage-collected. `ThreadLocal.withInitial(Supplier<T>)` is the preferred factory method for constructing a `ThreadLocal` with a lazy initializer, replacing the older pattern of subclassing and overriding `initialValue()`.

```java
// Standard declaration pattern: static final
static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
    ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
```

### `initialValue()`

`initialValue()` is called the first time `get()` is called on a thread that has not yet called `set()`. It returns the initial value for that thread's copy. The default implementation returns `null`. If `initialValue()` is not overridden and `withInitial()` is not used, calling `get()` without a prior `set()` returns `null`, which is often a source of `NullPointerException` bugs.

### Use Cases

Per-thread formatting is the clearest case: `SimpleDateFormat`, `NumberFormat`, and similar objects from `java.text` are not thread-safe. A `ThreadLocal` gives each thread its own instance at the cost of one instance per active thread rather than one instance per request.

Per-request context in web frameworks is the most common production use. At the entry point of each HTTP request (a servlet filter or framework interceptor), the framework stores the current user identity, request ID, and locale in `ThreadLocal` fields. Every layer of the call stack — service, repository, logging — retrieves this context without receiving it as a parameter. At the end of the request, the framework clears all `ThreadLocal` values before returning the thread to the pool.

### `InheritableThreadLocal`

When a new thread is created, it inherits copies of all `InheritableThreadLocal` values from its parent thread (a shallow copy made at the time `new Thread()` is called). This is useful for propagating request context into worker threads that are explicitly spawned for a single request. The limitation is significant: `ForkJoinPool` and `Executors.newVirtualThreadPerTaskExecutor()` do not propagate `InheritableThreadLocal` through task submission — tasks submitted to these pools do not inherit the submitting thread's values, making `InheritableThreadLocal` unreliable in modern async frameworks.

### Memory Leak in Thread Pools

Pool threads live for the lifetime of the pool. `ThreadLocal` entries are stored in a `ThreadLocalMap` embedded in each `Thread` object. If a request handler sets a `ThreadLocal` and does not call `remove()` before the thread returns to the pool, the entry persists indefinitely. A subsequent request on the same thread will call `get()` and receive the stale value from the previous request if it does not call `set()` first — this is both a data leak (one request's context is visible to another) and a memory leak (the value is never collected).

```
Thread Pool Thread Lifecycle:
  Request 1 → set("user-A") → [process] → remove()  ✓ clean
  Request 2 → get() → null (or initial)              ✓ correct

Without remove():
  Request 1 → set("user-A") → [process]              ✗ no remove
  Request 2 → get() → "user-A"                       ✗ stale data leak
```

### WeakReference Mechanism

`ThreadLocalMap` uses `WeakReference<ThreadLocal<?>>` as its key. When a `ThreadLocal` object itself is garbage-collected (because no strong references remain, e.g., it was a non-static local variable), its key in the map becomes `null`. However, the **value** in the map entry is a strong reference and is not reclaimed until a subsequent `ThreadLocal` operation on that thread triggers stale-entry cleanup. This means that even a `ThreadLocal` that is itself collected can retain its value in memory until the next `get()`, `set()`, or `remove()` call. Declaring `ThreadLocal` fields as `static final` prevents the key from being collected prematurely, but does nothing to prevent value leaks — active `remove()` calls remain mandatory.

## Gotchas

**Not calling `remove()` in a `finally` block causes stale values to leak between requests.** When `ThreadLocal` is used in a thread pool, the thread is reused across requests. A value set during request N persists in the thread's `ThreadLocalMap` and is returned by `get()` on request N+1 if `set()` is not called first. This causes one request's context (user identity, transaction ID, locale) to be silently visible to a subsequent unrelated request on the same thread.

**`InheritableThreadLocal` does not propagate through `ForkJoinPool` task submission or virtual thread scheduling.** Tasks submitted to `ForkJoinPool.commonPool()` or `Executors.newVirtualThreadPerTaskExecutor()` do not inherit the submitting thread's values, even with `InheritableThreadLocal`. The inheritance happens only at `Thread` construction time via `new Thread(...)`. Frameworks that rely on `InheritableThreadLocal` for context propagation silently lose context when tasks cross pool boundaries.

**Large objects in `ThreadLocal` fields of web application threads cause classloader leaks on redeployment.** The server's thread pool threads survive application redeployment. If a `ThreadLocal` in the old application's classloader holds a reference to an object loaded by that classloader, the old classloader is never garbage-collected. This results in `OutOfMemoryError: Metaspace` after repeated deployments and is a known issue in application servers with Hibernate, log4j, and similar libraries.

**A `ThreadLocal<T>` declared as `static` protects only the reference, not the referenced object's contents.** If `T` is a mutable collection (e.g., `ThreadLocal<List<Foo>>`), each thread has its own independent `List` reference — but if that list is shared with other code by passing it as a method argument or storing it in a shared field, the `ThreadLocal` no longer protects its contents. The isolation guarantee applies only to the local reference stored in the `ThreadLocalMap`, not to any objects reachable through it.

**`ThreadLocal` values set in unit tests that share a thread pool contaminate subsequent tests.** JUnit test runners may reuse threads across tests. If a test sets a `ThreadLocal` value and does not remove it (in a `@AfterEach` method), the next test running on the same thread will observe the stale value. This can produce non-deterministic, order-dependent test failures that are difficult to diagnose.

**`ThreadLocal` values are not transferred across `CompletableFuture` async stages.** A value set in the main thread is not visible in stages executing on `ForkJoinPool.commonPool()` via `thenApplyAsync()` or `supplyAsync()`. Each stage may run on a different pool thread with no relationship to the submitting thread's `ThreadLocal` state. Context that must be available in async stages must be explicitly captured as a local variable and passed into the lambda closure.
