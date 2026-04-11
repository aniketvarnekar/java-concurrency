# ThreadPoolExecutor Internals

## Overview

ThreadPoolExecutor is the concrete implementation backing all standard ExecutorService pools created by the Executors factory class. Its constructor exposes every parameter that controls thread creation, task queuing, idle thread expiration, rejection handling, and thread naming. Understanding these parameters is essential for building pools that behave predictably under production load rather than relying on factory defaults that embed assumptions about the workload.

The most important insight about ThreadPoolExecutor is that maximumPoolSize only becomes relevant when the work queue is full. This is counterintuitive: many developers expect that submitting task number core+1 will create a new thread, but it does not — it enqueues the task. A new thread beyond core count is only created when the queue rejects the enqueue attempt, which an unbounded queue never does. The choice of BlockingQueue implementation therefore defines the effective behavior of the pool far more than the maximumPoolSize value.

The rejection handler determines what happens when the pool is saturated: all threads are busy and the queue is full. The four built-in policies range from throwing an exception (AbortPolicy) to running the task on the caller's thread (CallerRunsPolicy), providing a spectrum of behaviors from fail-fast to implicit backpressure. Custom handlers can implement alternative strategies such as logging and dropping, persisting to a durable queue, or blocking the caller until space opens.

## Key Concepts

### Constructor Parameters

ThreadPoolExecutor provides a seven-parameter constructor that gives complete control over pool behavior. Every factory method in Executors calls this constructor with pre-chosen values.

```java
new ThreadPoolExecutor(
    int corePoolSize,
    int maximumPoolSize,
    long keepAliveTime,
    TimeUnit unit,
    BlockingQueue<Runnable> workQueue,
    ThreadFactory threadFactory,
    RejectedExecutionHandler handler
)
```

### corePoolSize

The number of threads kept alive even when idle, unless allowCoreThreadTimeOut(true) is set. When a task is submitted and the current thread count is below corePoolSize, a new thread is created even if other threads are idle. This eager thread creation up to the core count ensures that tasks get dedicated threads during ramp-up.

```java
executor.allowCoreThreadTimeOut(true); // core threads also expire when idle
executor.prestartAllCoreThreads();     // warm up all core threads immediately
```

### maximumPoolSize

The maximum number of threads that can exist concurrently. New threads beyond corePoolSize are created only when the work queue is full and the current thread count is below maximumPoolSize. With an unbounded queue, this condition is never reached and maximumPoolSize is effectively ignored.

### keepAliveTime and TimeUnit

How long a thread beyond corePoolSize may remain idle before being terminated. Reduces thread count during idle periods to save resources. With allowCoreThreadTimeOut(true), this timeout also applies to core threads, allowing the pool to shrink to zero when idle.

### BlockingQueue — the pivotal choice

The choice of queue determines the thread growth behavior more than any other parameter.

```
Queue Type          | When queue is "full"     | Effect on thread creation
--------------------|--------------------------|---------------------------
LinkedBlockingQueue | Never (unbounded)        | maxPoolSize never reached
SynchronousQueue    | Always (zero capacity)   | New thread created up to max immediately
ArrayBlockingQueue  | When n items queued      | Extra threads created up to max, then reject
```

```java
// Bounded pool with explicit rejection: queue fills → extra threads → then CallerRuns
new ThreadPoolExecutor(2, 4, 5, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(10),
        Executors.defaultThreadFactory(),
        new ThreadPoolExecutor.CallerRunsPolicy());
```

### Thread Growth Algorithm

When a task is submitted, ThreadPoolExecutor applies this decision sequence:

```
Submit task
     │
     ▼
threads < corePoolSize?
     │ yes → create new thread, run task
     │ no
     ▼
queue.offer(task) succeeds?
     │ yes → task enqueued, existing threads will pick it up
     │ no  (queue full)
     ▼
threads < maximumPoolSize?
     │ yes → create new thread, run task
     │ no
     ▼
invoke RejectedExecutionHandler
```

### RejectedExecutionHandler

Four built-in rejection policies cover the most common requirements.

| Policy | Behavior | Use when |
|---|---|---|
| AbortPolicy (default) | Throws RejectedExecutionException | Caller must handle overload explicitly |
| CallerRunsPolicy | Caller thread executes the task | Implicit backpressure is acceptable |
| DiscardPolicy | Silently drops the task | Task loss is tolerable |
| DiscardOldestPolicy | Drops oldest queued task, retries new one | Latest tasks are more valuable |

```java
// CallerRunsPolicy provides backpressure: when pool is full, the producer blocks
new ThreadPoolExecutor.CallerRunsPolicy()
```

### ThreadFactory

A custom ThreadFactory sets thread names, daemon status, priority, and UncaughtExceptionHandler. Descriptive thread names are invaluable in thread dumps for production debugging.

```java
ThreadFactory factory = new ThreadFactory() {
    private final AtomicInteger count = new AtomicInteger(1);
    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "pool-worker-" + count.getAndIncrement());
        t.setUncaughtExceptionHandler((thread, ex) ->
                System.err.println(thread.getName() + " threw: " + ex.getMessage()));
        return t;
    }
};
```

### prestartAllCoreThreads()

Creates all core threads before any tasks arrive. Useful to warm up a pool so that the first batch of tasks does not pay the cost of thread creation. Without prestarting, threads are created on demand as tasks arrive up to corePoolSize.

## Gotchas

**maximumPoolSize only comes into effect after the work queue is full.** With an unbounded LinkedBlockingQueue (used by newFixedThreadPool and newSingleThreadExecutor), the queue never fills and maximumPoolSize is never exceeded regardless of its value. Setting maximumPoolSize=100 on a pool with an unbounded queue provides no protection against bottlenecks — all tasks queue up behind the core threads.

**keepAliveTime applies to threads beyond corePoolSize only by default.** Extra threads that were created to handle a burst expire after the configured idle time, but core threads remain alive permanently. If the application has bursty traffic and the pool should shrink completely during quiet periods, call allowCoreThreadTimeOut(true) to apply the same timeout to core threads.

**CallerRunsPolicy causes the submitting thread to execute the rejected task inline, which can block the producer thread for the full task duration.** If the submitting thread is a request-handling thread in a web server, it will be tied up for hundreds of milliseconds executing a task that should have gone to the pool. This provides implicit backpressure but can stall the pipeline that feeds the pool. Use CallerRunsPolicy deliberately, not as a default catch-all.

**Providing a ThreadFactory without setting an UncaughtExceptionHandler means that runtime exceptions thrown by tasks submitted with execute() are printed to stderr and swallowed.** Tasks submitted with submit() wrap the exception in the Future and it only surfaces when get() is called. In either case, the exception can go unnoticed in production. Always set an UncaughtExceptionHandler on the factory's threads to capture unexpected failures.

**Calling shutdown() prevents new tasks but does not stop running tasks.** Threads that are actively executing continue to completion. Calling shutdownNow() sends Thread.interrupt() to running threads, but threads that do not check Thread.isInterrupted() or do not sleep/wait will run to completion anyway. There is no mechanism to forcibly terminate a running thread — design tasks to be interruptible if cancellation is needed.

**Monitoring pool state with getPoolSize(), getActiveCount(), and getQueue().size() provides point-in-time snapshots only.** Querying multiple methods in sequence does not give a consistent view — the pool state can change between each call. For consistent metrics, use a custom RejectedExecutionHandler to count rejections and a ThreadFactory to count thread creation rather than relying on the accessor methods alone.
