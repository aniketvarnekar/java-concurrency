# Thread Pool Pattern

## Overview

A thread pool maintains a set of pre-created worker threads that wait for tasks to execute. When a task is submitted, an idle thread picks it up and runs it. When the task completes, the thread returns to the pool and waits for the next task. This amortizes the cost of thread creation across many tasks, which matters because creating an OS thread is expensive — it involves a system call, memory allocation for the thread stack, and scheduling overhead.

The pool pattern separates two concerns: submitting work and executing work. The submitter calls `execute()` or `submit()` and moves on. The pool decides which thread runs the task and when. This decoupling lets the application control parallelism as a pool-level configuration rather than hard-coding thread management into each subsystem.

The most important configuration decision for a thread pool is its size. An undersized pool leaves CPU cores idle when there is work to do. An oversized pool creates so many threads that context switching overhead dominates, and the aggregate memory footprint of thread stacks becomes significant. The optimal size depends on the nature of the work: whether it is CPU-bound (computation-intensive) or IO-bound (waiting-intensive), and what other thread pools are competing for the same cores.

Java's `ThreadPoolExecutor` is the standard implementation. The higher-level factory methods in `Executors` — `newFixedThreadPool`, `newCachedThreadPool`, `newSingleThreadExecutor` — are convenience wrappers around `ThreadPoolExecutor` with pre-chosen configurations. For production systems, constructing `ThreadPoolExecutor` directly with explicit parameters gives full control over queue type, pool bounds, and rejection policy.

## Key Concepts

### Why Thread Pools

Thread creation has measurable cost. On a typical JVM, creating a thread involves allocating a stack (512 KB by default on many JVMs, configurable with `-Xss`), initializing thread-local state, and making a system call to create the underlying OS thread. This can take on the order of a millisecond. For a workload that processes thousands of short tasks per second, creating one thread per task would spend more time on thread lifecycle than on actual work.

Thread pools avoid this by reusing threads. A thread completes a task, then polls the work queue for the next one. Thread creation cost is paid once at pool startup (or lazily on first use, depending on pool configuration).

```java
// Direct construction for full control
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    4,                              // corePoolSize
    8,                              // maximumPoolSize
    60L, TimeUnit.SECONDS,          // keepAliveTime for idle threads above core
    new LinkedBlockingQueue<>(100), // bounded work queue
    new ThreadPoolExecutor.CallerRunsPolicy() // rejection policy
);
```

### CPU-Bound Work

CPU-bound tasks keep the processor busy for the duration of their execution — matrix multiplication, image encoding, cryptographic operations, sorting. For these tasks, having more threads than CPU cores is counterproductive. Each additional thread adds context-switching overhead without doing additional computation, because the hardware can only execute one thread per core at a time.

The rule of thumb for CPU-bound pools is:

```
pool size = Runtime.getRuntime().availableProcessors()
```

Sometimes one extra thread is added to compensate for occasional minor blocking:

```
pool size = availableProcessors() + 1
```

```java
int cores = Runtime.getRuntime().availableProcessors();
ExecutorService cpuPool = Executors.newFixedThreadPool(cores);
```

### IO-Bound Work

IO-bound tasks spend most of their time waiting — for a network response, a database query, a file read. While a thread waits, it consumes no CPU. This means the CPU can be used by other threads, and having more threads than cores is beneficial.

The standard formula is derived from a model of thread utilization:

```
threads = cores / (1 - blocking_coefficient)
```

The blocking coefficient is the fraction of time a thread spends blocked on IO (0.0 = never blocks, 1.0 = always blocked). For a task that spends 90% of its time waiting on a database:

```
threads = 4 / (1 - 0.9) = 40
```

This is an approximation. In practice, the right value is found by load testing and measuring CPU utilization: if CPUs are idle under peak load, the pool is undersized; if CPUs are saturated and latency is high, the pool may be oversized or the workload has changed character.

| Configuration | CPU-Bound | IO-Bound |
|---|---|---|
| Pool size | `availableProcessors()` | `cores / (1 - blocking_coefficient)` |
| Typical size (4 cores) | 4–5 threads | 20–100 threads |
| Bottleneck | CPU cycles | Network / disk latency |
| Risk of too many threads | Excessive context switching | Memory (stacks), scheduler overhead |
| Queue depth | Moderate, bounded | Larger, bounded |

### Little's Law

Little's Law from queuing theory states: the average number of items in a system equals the throughput multiplied by the average time each item spends in the system.

```
L = λ × W
```

where L is the number of in-flight tasks, λ is the throughput (tasks/second), and W is the average latency per task. To handle 100 tasks/second where each task takes 0.5 seconds, you need 50 concurrent threads. This provides a sanity check on pool sizing estimates.

Increasing pool size increases concurrency (L), but it also increases contention for shared resources (locks, connections), which increases W. There is a point of diminishing returns, and then a point where adding threads makes things worse.

### Queue Strategy

The work queue is as important as the pool size. Three strategies exist:

An unbounded queue (`new LinkedBlockingQueue<>()` with no capacity) accepts tasks indefinitely. If task submission outpaces execution, tasks accumulate and heap memory grows without bound, eventually causing `OutOfMemoryError`. This is the default in `Executors.newFixedThreadPool()` — a common source of production incidents.

A bounded queue applies backpressure. When the queue is full, the rejection policy fires. `CallerRunsPolicy` runs the task in the submitting thread, which slows submission rate naturally. `AbortPolicy` throws `RejectedExecutionException`. Choosing an appropriate policy is part of pool design.

A `SynchronousQueue` has no storage capacity: each submitted task must be picked up immediately by a thread. If no thread is available, the pool creates a new one (up to `maximumPoolSize`). This is the strategy in `Executors.newCachedThreadPool()`. Under a spike of work, the pool can grow to an unbounded number of threads, which can exhaust memory.

### Monitoring

`ThreadPoolExecutor` exposes runtime metrics:

```java
ThreadPoolExecutor tpe = (ThreadPoolExecutor) pool;
System.out.println("Pool size:       " + tpe.getPoolSize());
System.out.println("Active threads:  " + tpe.getActiveCount());
System.out.println("Queue depth:     " + tpe.getQueue().size());
System.out.println("Completed tasks: " + tpe.getCompletedTaskCount());
```

Queue depth is a leading indicator: a growing queue means the pool cannot keep up with submission rate. Active count near pool size indicates the pool is saturated.

## Code Snippet

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolMonitorExample {

    public static void main(String[] args) throws InterruptedException {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("Available processors: " + cores);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            cores,
            cores,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r);
                t.setName("cpu-worker-" + t.getId());
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        AtomicInteger completed = new AtomicInteger();

        // Submit CPU-bound tasks
        for (int i = 0; i < 40; i++) {
            final int taskId = i;
            pool.execute(() -> {
                // Simulate CPU-bound work
                long sum = 0;
                for (long j = 0; j < 5_000_000L; j++) sum += j;
                int c = completed.incrementAndGet();
                System.out.printf("[%s] task-%d done (sum=%d, completed=%d)%n",
                    Thread.currentThread().getName(), taskId, sum, c);
            });
        }

        // Monitor pool stats while tasks run
        Thread monitor = new Thread(() -> {
            try {
                while (!pool.isTerminated()) {
                    System.out.printf(
                        "[monitor] poolSize=%d active=%d queued=%d completed=%d%n",
                        pool.getPoolSize(),
                        pool.getActiveCount(),
                        pool.getQueue().size(),
                        pool.getCompletedTaskCount()
                    );
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pool-monitor");
        monitor.setDaemon(true);
        monitor.start();

        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        System.out.println("Pool terminated. Total completed: " + pool.getCompletedTaskCount());
    }
}
```

## Gotchas

### One Size Does Not Fit All

A single thread pool configuration is rarely correct for all workloads. A pool sized for CPU-bound work will perform poorly if the tasks occasionally call external services. If a codebase mixes CPU-bound and IO-bound work, use separate pools for each, sized appropriately. `Executors.newCachedThreadPool()` and `Executors.newFixedThreadPool(n)` with a fixed n are both wrong for one of the two workload types.

### Ignoring the Blocking Coefficient

Using `availableProcessors()` as the pool size for IO-bound work is a common mistake. For tasks with a blocking coefficient of 0.8 on an 8-core machine, the correct size is 40 threads, not 8. With 8 threads, 7 of them will typically be blocked on IO and only one will be running, leaving 7 cores idle and reducing throughput to a fraction of what is possible.

### Queue Depth as a Backpressure Signal

The queue depth reported by `getQueue().size()` is not just a diagnostic — it is a signal. A consistently non-zero queue depth means the pool is at capacity. A growing queue depth means the system is overloaded. A monitoring alert on queue depth that exceeds a threshold gives early warning before latency or error rates degrade.

### Thread-Local State Leaking Across Tasks

Threads in a pool are reused. If a task sets a `ThreadLocal` variable and does not call `remove()`, the value persists for the thread's lifetime. The next task that runs on the same thread will see the stale value. This is a common source of subtle bugs in web frameworks where per-request state is stored in `ThreadLocal` and the framework forgets to clean up.

### Unbounded Queue in Executors.newFixedThreadPool

`Executors.newFixedThreadPool(n)` uses an unbounded `LinkedBlockingQueue` internally. Under sustained overload, this queue grows without bound. For production use, construct `ThreadPoolExecutor` directly with a bounded queue and an explicit rejection policy rather than using the `Executors` factory.

### Not Shutting Down the Pool

An `ExecutorService` holds threads that prevent JVM exit. If a pool is not shut down explicitly (via `shutdown()` or `shutdownNow()`), the JVM will not exit even after `main()` returns, unless all pool threads are daemon threads. Always shut down pools in a `finally` block or hook them into the application lifecycle.
