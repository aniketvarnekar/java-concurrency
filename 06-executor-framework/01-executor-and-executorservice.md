# 01 — Executor and ExecutorService

## Overview

The `Executor` interface, defined in `java.util.concurrent`, has exactly one method: `execute(Runnable command)`. Its purpose is to decouple the submission of a task from the decision of how that task runs — whether on a new thread, a pooled thread, the calling thread, or some other mechanism entirely. This narrow interface is the foundation of the entire concurrency framework.

`ExecutorService` extends `Executor` with two categories of additions. The first is result-bearing task submission: `submit(Callable<T>)` and `submit(Runnable)` return a `Future` that the caller can use to retrieve the result or wait for completion. The second is lifecycle management: methods to shut down the executor, wait for tasks to complete, and query the current state. Without lifecycle management, background threads prevent JVM shutdown, and resources leak.

The lifecycle of an `ExecutorService` follows a one-way state machine: it starts in `RUNNING`, transitions to `SHUTDOWN` when `shutdown()` is called (no new tasks accepted, existing tasks continue), and eventually reaches `TERMINATED` when all tasks and threads have finished. `shutdownNow()` provides a more aggressive transition by attempting to interrupt running tasks and draining the task queue. The method `awaitTermination` is the bridge between initiating shutdown and knowing it has completed.

The Executor framework was designed with the principle that most programs should submit work to a service rather than directly manage threads. Directly creating threads mixes business logic with thread lifecycle code, makes unit testing harder, and forecloses the ability to change the threading strategy — from a fixed pool to a virtual-thread executor, for example — without touching the code that defines the work.

## Key Concepts

### Executor Interface

`Executor` is the root interface with a single method:

```java
public interface Executor {
    void execute(Runnable command);
}
```

It makes no promise about how or when the command runs. Common implementations include:

```java
// Direct execution (synchronous, no thread involved)
Executor directExecutor = Runnable::run;

// New thread per task (no pooling)
Executor threadPerTask = command -> new Thread(command).start();

// Thread pool (most common in practice)
Executor pool = Executors.newFixedThreadPool(4);
```

`execute` does not return a value and does not propagate checked exceptions. If the task throws an unchecked exception, it is routed to the thread's uncaught exception handler.

### ExecutorService

`ExecutorService` adds the following methods beyond `execute`:

```java
// Submit a Callable; returns a Future holding the eventual result
<T> Future<T> submit(Callable<T> task);

// Submit a Runnable with a result to return on success
<T> Future<T> submit(Runnable task, T result);

// Submit a Runnable; Future.get() returns null on success
Future<?> submit(Runnable task);

// Submit all tasks; blocks until all complete or thread is interrupted.
// Returns a List of Futures, all in done state.
<T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException;

// Submit all tasks; returns the result of the first to complete (successfully).
// Cancels all others.
<T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException;
```

`submit` versus `execute` is an important distinction for exception handling (covered in the Gotchas section).

### Lifecycle

The state transitions are:

```
RUNNING
   |
   | shutdown()
   v
SHUTDOWN  <-- no new tasks accepted; queued and running tasks continue
   |
   | all tasks complete + all threads terminated
   v
TERMINATED

From RUNNING or SHUTDOWN:
   |
   | shutdownNow()
   v
STOP  <-- running tasks receive interrupt; queued tasks returned as list
   |
   | all tasks complete + all threads terminated
   v
TERMINATED
```

Query methods: `isShutdown()` returns true in SHUTDOWN or STOP; `isTerminated()` returns true only in TERMINATED.

### shutdown() vs shutdownNow()

`shutdown()` is the orderly path: it stops accepting new tasks but allows all tasks currently in the queue and all tasks currently executing to run to completion. The calling thread is not blocked; it returns immediately after initiating the shutdown.

`shutdownNow()` attempts to stop active tasks by interrupting their threads. It drains the task queue and returns the list of queued tasks that never started as a `List<Runnable>`. Tasks that ignore interruption will continue running until they finish naturally. There is no guarantee that all active tasks stop promptly.

```java
ExecutorService executor = Executors.newFixedThreadPool(4);
// ... submit tasks ...

// Orderly shutdown
executor.shutdown();

// Wait up to 10 seconds for running tasks to finish
if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
    // Tasks are still running; escalate to forceful shutdown
    List<Runnable> unstarted = executor.shutdownNow();
    System.out.println("Did not start: " + unstarted.size() + " tasks");
}
```

### awaitTermination

`awaitTermination(long timeout, TimeUnit unit)` blocks the calling thread until one of three conditions is met: all tasks have completed and all threads have terminated, the specified timeout elapses, or the waiting thread is interrupted. It returns `true` if the executor reached `TERMINATED` before the timeout, and `false` if the timeout elapsed while tasks were still running.

The standard shutdown pattern is:

```java
executor.shutdown();
try {
    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            System.err.println("Executor did not terminate");
        }
    }
} catch (InterruptedException e) {
    executor.shutdownNow();
    Thread.currentThread().interrupt(); // restore interrupt status
}
```

## Gotchas

### Forgetting to call shutdown() prevents JVM exit

Non-daemon pool threads keep the JVM alive. If `shutdown()` is never called, the program will not exit even after `main()` returns. This is particularly insidious in short-lived utilities and tests. Always call `shutdown()` in a `finally` block or use a try-with-resources wrapper when available.

### shutdownNow() does not guarantee that running tasks stop

`shutdownNow()` calls `Thread.interrupt()` on each pool thread. A task that ignores `InterruptedException` or never checks the interrupt flag will continue running. `shutdownNow()` is not a forceful kill; it is a polite request that threads stop. Tasks must cooperate by checking `Thread.currentThread().isInterrupted()` or calling methods that throw `InterruptedException`.

### submit() swallows exceptions; execute() does not

When you call `executor.execute(runnable)` and the `Runnable` throws an unchecked exception, the exception propagates to the thread's `UncaughtExceptionHandler`. When you call `executor.submit(runnable)` and the `Runnable` throws, the exception is captured inside the returned `Future` and is only visible when you call `future.get()`. If you never call `get()`, the exception is silently lost. For fire-and-forget tasks where you want exceptions to be logged, prefer `execute()` with an `UncaughtExceptionHandler`, or always retrieve the `Future`.

### awaitTermination timeout does not stop tasks

If `awaitTermination` returns `false` (timeout elapsed), the executor is still running. The tasks are still executing. The only effect is that the calling thread has stopped waiting. You must decide what to do next — typically call `shutdownNow()` and wait again, or log the situation and proceed.

### invokeAll does not short-circuit on failure

`invokeAll` submits all tasks and waits for all of them to complete, whether they succeed or fail. It does not cancel remaining tasks if one fails. All futures in the returned list will be in the `done` state (either succeeded or failed with an exception). If you want to stop on first failure, you need to poll futures in a loop and cancel others manually.

### Submitting to a shutdown executor throws RejectedExecutionException

Any call to `submit()` or `execute()` after `shutdown()` has been called throws `RejectedExecutionException`. This is true even for `shutdownNow()`. Code that submits tasks asynchronously (from other threads) must guard against this, either by catching the exception or by using a flag to stop submission before calling shutdown.
