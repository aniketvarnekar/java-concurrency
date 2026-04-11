# Fork/Join Framework

## Overview

The Fork/Join framework (Java 7+) is designed for divide-and-conquer parallel algorithms. A large task is recursively split — forked — into smaller subtasks until each subtask is small enough to compute directly. This smallest unit of work is the base case. Results are then combined bottom-up as each level of the recursion joins its subtasks. The `ForkJoinPool` executes these tasks using a work-stealing algorithm designed to keep all worker threads busy even when the work is unevenly distributed across the recursion tree.

The framework's distinguishing feature is the work-stealing scheduler. Each worker thread maintains a private deque (double-ended queue) of tasks. The worker pushes newly forked tasks onto the front of its deque and pops from the front when it needs work. When a worker exhausts its own deque, it steals tasks from the back of another worker's deque. Because the owner operates on the front and thieves operate on the back, contention between them is minimized. Larger, not-yet-split tasks tend to accumulate at the back (they were pushed earlier) and are the best candidates for stealing, since they represent the most remaining work.

The framework is well suited to CPU-bound recursive decompositions: merge sort, quicksort, parallel prefix sum, tree traversals, and matrix operations. It is not well suited to I/O-bound work, tasks that block on locks, or tasks with interdependencies that are not expressible as a DAG. Blocking inside `compute()` wastes a worker thread that could otherwise be executing real tasks. For blocking work inside a `ForkJoinPool`, the `ManagedBlocker` interface signals the pool to compensate by spinning up an additional thread.

## Key Concepts

### Work-Stealing

Each `ForkJoinPool` worker thread owns a `WorkQueue` implemented as a circular array deque. When a task calls `fork()`, the new subtask is pushed onto the front of the current thread's deque. The current thread then calls `compute()` on itself (or pops from the front), always working on the most recently created task — a LIFO order that preserves cache locality. Idle threads steal from the back of other threads' deques — a FIFO order from the thief's perspective, which takes the largest, oldest tasks and provides the most useful work per steal.

```
Worker 1 deque (front → back):
  [small-task-4] [small-task-3] [mid-task-2] [large-task-1]
       ↑ owner pops from front            ↑ thief steals from back

Worker 2 (idle) steals large-task-1 → subdivides it further
```

### Work-Stealing Diagram (Fork/Join Tree)

```
         compute(0..N)
        /             \
compute(0..N/2)   compute(N/2..N)
   /       \         /       \
c(0..N/4) c(N/4..N/2) ...   ...
    |          |
  base       base
  case       case
```

### `RecursiveTask<V>`

`RecursiveTask<V>` extends `ForkJoinTask<V>` and represents a task that returns a value. Override `compute()`: if the task's input is small enough (below the threshold), compute the result directly and return it. Otherwise, split the input into two halves, fork one subtask (submitting it to the pool asynchronously), compute the other half directly (running it in the current thread), join the forked task (waiting for its result), and combine the two results.

```java
@Override
protected Long compute() {
    if (length <= THRESHOLD) {
        // base case: compute sequentially
        return sequentialSum(array, start, end);
    }
    int mid = start + (end - start) / 2;
    ParallelSum left  = new ParallelSum(array, start, mid);
    ParallelSum right = new ParallelSum(array, mid,   end);
    left.fork();                     // async: submit left to pool
    long rightResult = right.compute(); // sync: run right in current thread
    long leftResult  = left.join();     // wait for left to finish
    return leftResult + rightResult;
}
```

### `RecursiveAction`

`RecursiveAction` is the void variant of `RecursiveTask`. It is used for parallel side-effects: parallel array fill, in-place sort, or parallel tree modification. The `compute()` method returns nothing; it either performs work directly (base case) or forks subtasks and joins them.

### Fork vs Invoke

`fork()` submits a task to the pool asynchronously and returns immediately. `compute()` runs the current task's logic synchronously in the calling thread. `join()` blocks until the forked task's `compute()` completes and returns its result. The canonical pattern is: fork the left half, compute the right half directly, join the left half. This ensures the current thread always does useful work (the right half) while the pool works on the left, rather than blocking immediately after forking.

### `ForkJoinPool.commonPool()`

`ForkJoinPool.commonPool()` is a static, JVM-wide shared pool used implicitly by `RecursiveTask.invoke()`, parallel streams, and `CompletableFuture.supplyAsync()` when no executor is specified. Its parallelism defaults to `Runtime.getRuntime().availableProcessors() - 1`. Because it is shared, submitting long-running or blocking tasks to `commonPool()` starves all other users of the pool within the same JVM, including parallel streams from unrelated code.

| Pool creation | Shared? | Shutdown required? | Default parallelism |
|---|---|---|---|
| `ForkJoinPool.commonPool()` | Yes (JVM-wide) | No | `CPUs - 1` |
| `new ForkJoinPool(n)` | No | Yes | `n` |

### Threshold Tuning

The threshold controls the granularity of task splitting. A threshold that is too small creates an excessive number of `ForkJoinTask` objects, overwhelming the work-stealing scheduler and generating significant GC pressure. A threshold that is too large produces too few tasks, leaving most worker threads idle. A practical starting point for arithmetic operations is 1,000 to 10,000 elements. The correct value depends on the cost of the base-case computation and should be determined by benchmarking with the actual workload.

### ManagedBlocker

When a task running inside a `ForkJoinPool` must block (waiting for a lock, I/O, or a condition), it should signal the pool by calling `ForkJoinPool.managedBlock(ManagedBlocker)`. This tells the pool that the current thread is about to block and that it should spin up a compensation thread to maintain the target parallelism level. Without this, a blocking task starves the pool of a worker.

## Gotchas

**Blocking inside `compute()` starves the worker pool.** If a task calls `Thread.sleep()`, performs synchronous I/O, or waits on a `CountDownLatch` inside `compute()`, the ForkJoinPool worker thread is occupied but doing no useful computation. Other tasks that could be executed by that thread must wait. Use `ForkJoinPool.managedBlock(ManagedBlocker)` to signal the pool to compensate with an additional thread, or move blocking operations entirely outside the Fork/Join context.

**Forking both subtasks and joining both is less efficient than forking one and computing the other inline.** The pattern `left.fork(); right.fork(); left.join() + right.join()` leaves the current thread idle while waiting for both results. The preferred pattern is `left.fork(); rightResult = right.compute(); leftResult = left.join()`, which ensures the current thread does productive work (computing the right half) while the pool handles the left half asynchronously.

**Submitting tasks to `ForkJoinPool.commonPool()` from server code that also uses `commonPool()` for parallel streams can cause starvation.** A long-running task that monopolizes a `commonPool` worker reduces effective parallelism for all other concurrent parallel stream operations in the same JVM. If the workload includes both long-running Fork/Join tasks and latency-sensitive parallel streams, use a dedicated `new ForkJoinPool(n)` for the long-running work.

**`new ForkJoinPool(n)` creates a pool that is not the `commonPool` and must be shut down explicitly.** Worker threads in a custom `ForkJoinPool` are daemon threads by default. If the pool is never shut down, the JVM can exit while tasks are still running (because daemon threads do not prevent JVM exit). More insidiously, if `shutdown()` is never called, the pool's threads are leaked for the lifetime of the JVM.

**`ForkJoinTask.get()` throws checked exceptions; `join()` wraps them in unchecked exceptions.** In Fork/Join code, prefer `join()` over `get()` to avoid the try/catch boilerplate for `InterruptedException` and `ExecutionException`. Use `get()` only when interfacing with APIs that consume `Future<V>`.

**A threshold that is too fine-grained creates millions of tiny `ForkJoinTask` objects.** For operations on arrays of 10 million elements with a threshold of 1, the framework creates approximately 10 million `ForkJoinTask` objects. Object allocation and GC pressure from these short-lived tasks can dominate the computation time for simple arithmetic operations. Always benchmark with realistic thresholds before relying on Fork/Join for performance.
