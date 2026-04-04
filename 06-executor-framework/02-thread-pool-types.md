# Thread Pool Types

## Overview

The Executors utility class provides factory methods that pre-configure ThreadPoolExecutor instances for the most common use cases. Each factory method sets specific values for core pool size, maximum pool size, keep-alive time, and the work queue. These defaults are chosen to fit particular workload patterns, but they also embed trade-offs that can become serious production problems if the workload does not match the assumption built into the factory method.

Understanding these defaults is critical because the wrong pool type can cause OutOfMemoryError, thread explosion, or silent task drops under load. newFixedThreadPool looks safe because it bounds the number of threads, but it uses an unbounded queue that can accumulate millions of tasks. newCachedThreadPool looks efficient because it reuses idle threads, but it will create millions of new threads under a burst of concurrent submissions. Neither pool imposes any admission control on the caller.

Java 21 added newVirtualThreadPerTaskExecutor(), which creates one virtual thread per submitted task with no pooling at all. This is suitable for IO-bound workloads where the cost of blocking is paid by the virtual thread scheduler rather than an OS thread. Each of the factory methods maps to a specific ThreadPoolExecutor configuration, and knowing that mapping allows developers to build a custom pool when the factory defaults are not appropriate.

## Key Concepts

### newFixedThreadPool(n)

Creates a pool with corePoolSize equal to maximumPoolSize equal to n. The work queue is an unbounded LinkedBlockingQueue. Threads are never terminated due to inactivity (keepAlive is 0). Because the queue is unbounded, the pool never rejects tasks — but under sustained overload, tasks pile up indefinitely, eventually exhausting heap memory with no visible error until an OutOfMemoryError is thrown.

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
// Internally equivalent to:
// new ThreadPoolExecutor(4, 4, 0L, MILLISECONDS, new LinkedBlockingQueue<>())
```

### newCachedThreadPool()

Creates a pool with corePoolSize of 0 and maximumPoolSize of Integer.MAX_VALUE. The work queue is a SynchronousQueue, which has zero capacity — every submitted task either goes directly to an idle thread or spawns a new one. Idle threads expire after 60 seconds. Under sudden burst load, this pool can create thousands of threads in seconds, degrading performance or exhausting OS thread limits.

```java
ExecutorService pool = Executors.newCachedThreadPool();
// Internally equivalent to:
// new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, SECONDS, new SynchronousQueue<>())
```

### newSingleThreadExecutor()

Creates a pool with corePoolSize and maximumPoolSize of 1, backed by an unbounded LinkedBlockingQueue. All submitted tasks execute sequentially in submission order, making it suitable for ordered processing of shared resources. The return value is wrapped in a delegating proxy that prevents the caller from downcasting to ThreadPoolExecutor and reconfiguring it.

```java
ExecutorService executor = Executors.newSingleThreadExecutor();
// Cannot be cast to ThreadPoolExecutor — use new ThreadPoolExecutor(1,1,...) if reconfiguration needed
```

### newWorkStealingPool(parallelism)

Creates a ForkJoinPool with the given parallelism level (defaults to Runtime.getRuntime().availableProcessors()). Worker threads are daemon threads by default. The pool uses work-stealing: idle threads steal tasks from the queues of busier threads. Task ordering is not guaranteed. It is best suited for CPU-bound, recursive, divide-and-conquer workloads, not for IO-bound tasks or tasks that require ordered execution.

```java
ExecutorService pool = Executors.newWorkStealingPool();
// Internally: new ForkJoinPool(availableProcessors, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true)
```

### newScheduledThreadPool(n)

Creates a ScheduledThreadPoolExecutor with corePoolSize n. It supports one-time delayed tasks, fixed-rate periodic tasks, and fixed-delay periodic tasks. If a scheduled task throws an unchecked exception, all future executions of that task are silently cancelled. The maximumPoolSize is Integer.MAX_VALUE, but additional threads are rarely needed because the queue holds tasks until their scheduled time.

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
scheduler.scheduleAtFixedRate(() -> System.out.println("tick"), 0, 1, TimeUnit.SECONDS);
```

### newVirtualThreadPerTaskExecutor() (Java 21+)

Creates one virtual thread per submitted task with no pooling and no thread reuse. Virtual threads are cheap to create and block without tying up OS threads, making this suitable for IO-bound workloads with high concurrency. Virtual threads that block inside synchronized blocks are pinned to their carrier OS thread and lose the scalability advantage.

```java
// Requires Java 21+
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

### Pool Type Comparison

| Factory Method | Core | Max | Queue | KeepAlive | Primary Risk |
|---|---|---|---|---|---|
| newFixedThreadPool(n) | n | n | Unbounded LinkedBQ | 0 | OOM from unbounded queue |
| newCachedThreadPool() | 0 | MAX_INT | SynchronousQueue | 60s | Thread explosion |
| newSingleThreadExecutor() | 1 | 1 | Unbounded LinkedBQ | 0 | Single bottleneck |
| newWorkStealingPool() | 0 | parallelism | FJP internal | adaptive | CPU tasks only |
| newScheduledThreadPool(n) | n | MAX_INT | DelayedWorkQueue | 0 | Unchecked exception suppresses future runs |
| newVirtualThreadPerTaskExecutor() | N/A | N/A | N/A | N/A | Pinning with synchronized |

## Code Snippet

```java
import java.util.concurrent.*;

public class ThreadPoolTypesDemo {

    static void submitAndAwait(ExecutorService pool, String poolName) throws InterruptedException {
        System.out.printf("%nPool: %s%nActual class: %s%n", poolName, pool.getClass().getName());
        for (int i = 1; i <= 5; i++) {
            final int taskId = i;
            pool.submit(() -> {
                System.out.printf("  [%s] task-%d running on %s%n",
                        poolName, taskId, Thread.currentThread().getName());
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws InterruptedException {
        submitAndAwait(Executors.newFixedThreadPool(2),      "FixedThreadPool(2)");
        submitAndAwait(Executors.newCachedThreadPool(),      "CachedThreadPool");
        submitAndAwait(Executors.newSingleThreadExecutor(),  "SingleThreadExecutor");
        submitAndAwait(Executors.newWorkStealingPool(2),     "WorkStealingPool(2)");

        // ScheduledThreadPool: run 3 immediate tasks via execute, then shut down
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        System.out.printf("%nPool: ScheduledThreadPool(2)%nActual class: %s%n",
                scheduler.getClass().getName());
        for (int i = 1; i <= 5; i++) {
            final int taskId = i;
            scheduler.schedule(() -> {
                System.out.printf("  [ScheduledPool] task-%d running on %s%n",
                        taskId, Thread.currentThread().getName());
            }, 0, TimeUnit.MILLISECONDS);
        }
        scheduler.shutdown();
        scheduler.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println("\nAll pools shut down.");
    }
}
```

## Gotchas

**newFixedThreadPool and newSingleThreadExecutor use an unbounded LinkedBlockingQueue — tasks pile up indefinitely under overload.** If the consumers (pool threads) are slower than the producers (task submitters), the queue grows without bound. There is no rejection policy, no backpressure, and no warning. The heap is exhausted silently and OutOfMemoryError appears in the thread submitting the task or in the garbage collector thread, often far from the root cause.

**newCachedThreadPool creates a new thread for every task that arrives when no idle thread is available.** Under sudden burst load — for example, a spike of 10,000 requests — the pool may create 10,000 threads simultaneously. Each thread consumes memory for its stack (typically 256KB to 1MB by default) and OS resources. Performance degrades due to context switching and memory pressure, and the OS may refuse to create further threads.

**newWorkStealingPool wraps a ForkJoinPool whose threads run as daemon threads by default.** If the main thread exits before all tasks complete, the JVM shuts down and discards incomplete work without any warning. Code that submits CPU-bound tasks to a work-stealing pool must explicitly wait for completion — for example, by calling pool.awaitQuiescence() or maintaining a CountDownLatch — before the main thread exits.

**Executors.newSingleThreadExecutor() returns a delegating proxy that prevents downcasting to ThreadPoolExecutor.** If the application needs to inspect the queue size, change the core pool size, or call prestartCoreThread(), the proxy blocks all of those calls. Use new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) directly when pool introspection or reconfiguration is needed.

**All factory-method executors except newWorkStealingPool use non-daemon threads and must be shut down explicitly.** A forgotten shutdown() call keeps the JVM alive indefinitely after the main method returns, because the pool threads are still running and waiting for new work. Always call shutdown() and awaitTermination() in a finally block or register a JVM shutdown hook.

**scheduleAtFixedRate and scheduleWithFixedDelay silently suppress all future executions if the task throws an unchecked exception.** The ScheduledFuture associated with that task transitions to the done state with the exception stored inside. No log entry appears, no error is printed, and the task simply stops running. Always wrap the entire scheduled task body in try/catch(Exception e) to preserve future executions.
