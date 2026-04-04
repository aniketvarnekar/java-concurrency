# Future and Callable

## Overview

Callable<V> is a functional interface like Runnable but its call() method returns a value of type V and is declared to throw Exception. This makes it the natural choice for tasks that produce a result or can fail with a checked exception. Submitting a Callable to an ExecutorService returns a Future<V>, a handle to a computation that may not yet be complete, decoupling the act of submitting work from the act of retrieving the result.

The Future acts as a placeholder for the result of an asynchronous computation. The caller can continue doing other work after submission and block on the result only when it is actually needed by calling get(). If the computation has already finished by the time get() is called, it returns immediately. If it is still running, get() blocks the calling thread until the result is available, the computation throws, the future is cancelled, or a timeout expires.

The ExecutorService also provides bulk operations: invokeAll() submits a collection of Callables and returns a list of completed Futures (blocking until all finish), and invokeAny() returns the result of the first Callable that completes successfully and cancels the rest. These methods simplify fan-out patterns where multiple independent computations are run in parallel and the results are combined.

## Key Concepts

### Callable<V>

Callable is a single-method interface whose call() method returns V and throws Exception. Because it declares a checked Exception (not just RuntimeException), checked exceptions from the computation are naturally propagated to the caller via the Future.

```java
Callable<String> task = () -> {
    Thread.sleep(200);
    return "result";
};
```

### Future<V>

Future provides five methods for inspecting and interacting with the asynchronous computation.

```
isDone()                 → true if completed (normally, via exception, or cancellation)
isCancelled()            → true if cancelled before completion
get()                    → blocks until done, returns result, or throws
get(timeout, unit)       → like get() but throws TimeoutException if deadline passed
cancel(mayInterrupt)     → attempts cancellation; returns false if already done
```

### Blocking get()

get() blocks the calling thread indefinitely until the computation finishes. This ties up the calling thread for an unbounded duration. In production code, always prefer get(timeout, unit) with a meaningful upper bound to prevent threads from hanging when a downstream dependency stops responding.

```java
Future<String> future = executor.submit(task);
// Do other work here while task runs...
String result = future.get(5, TimeUnit.SECONDS); // bounded wait
```

### ExecutionException

Any exception thrown by call() is wrapped in an ExecutionException and rethrown by get(). The original exception is the cause. Accessing getMessage() on the ExecutionException returns a generic wrapper message; always call getCause() to retrieve the original exception with its actual message and stack trace.

```java
try {
    String result = future.get();
} catch (ExecutionException e) {
    Throwable cause = e.getCause(); // the original exception from call()
    System.out.println("Task failed: " + cause.getMessage());
}
```

### TimeoutException

get(timeout, unit) throws TimeoutException if the computation does not finish within the specified duration. The task continues running after TimeoutException is thrown — it is not automatically cancelled. Call cancel(true) after catching TimeoutException if the task should stop.

```java
try {
    result = future.get(500, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    future.cancel(true); // request cancellation after timeout
}
```

### Cancellation

cancel(true) sends Thread.interrupt() to the thread executing the task. The task must check Thread.isInterrupted() or enter an interruptible blocking operation (sleep, wait, IO) to respond to the interrupt. cancel(false) only prevents the task from starting if it has not yet begun; it does nothing to an already-running task. After cancellation, get() throws CancellationException.

```java
future.cancel(true);
try {
    future.get(); // throws CancellationException
} catch (CancellationException e) {
    System.out.println("Task was cancelled");
}
```

### invokeAll and invokeAny

invokeAll() submits all tasks, blocks until every task completes (or the calling thread is interrupted), and returns a list of Futures in the same order as the input collection. Every Future in the returned list is guaranteed to be done.

invokeAny() submits all tasks and returns the result of the first one to complete successfully. The remaining tasks are cancelled. If all tasks fail, invokeAny() throws ExecutionException.

```java
List<Callable<String>> tasks = List.of(task1, task2, task3);

// invokeAll — all results
List<Future<String>> futures = executor.invokeAll(tasks);
for (Future<String> f : futures) {
    System.out.println(f.get()); // already done, returns immediately
}

// invokeAny — fastest result
String fastest = executor.invokeAny(tasks);
```

## Code Snippet

```java
import java.util.List;
import java.util.concurrent.*;

public class FutureAndCallableDemo {

    static ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("callable-worker-" + t.getId());
        return t;
    });

    // A checked exception for task failures
    static class DataFetchException extends Exception {
        DataFetchException(String message) { super(message); }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Demo 1: Basic Callable + Future.get(timeout) ===");
        basicFutureDemo();

        System.out.println("\n=== Demo 2: ExecutionException unwrapping ===");
        executionExceptionDemo();

        System.out.println("\n=== Demo 3: TimeoutException + cancel ===");
        timeoutAndCancelDemo();

        System.out.println("\n=== Demo 4: CancellationException ===");
        cancellationDemo();

        System.out.println("\n=== Demo 5: invokeAll ===");
        invokeAllDemo();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("\nAll demos complete.");
    }

    static void basicFutureDemo() throws InterruptedException {
        Callable<String> task = () -> {
            System.out.println("[" + Thread.currentThread().getName() + "] task-1 running");
            Thread.sleep(200);
            return "result-from-task-1";
        };
        Future<String> future = executor.submit(task);
        System.out.println("[main] task submitted, doing other work...");
        try {
            String result = future.get(1, TimeUnit.SECONDS);
            System.out.println("[main] task-1 result: " + result);
        } catch (ExecutionException e) {
            System.out.println("[main] task-1 failed: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            System.out.println("[main] task-1 timed out");
        }
    }

    static void executionExceptionDemo() throws InterruptedException {
        Callable<String> failingTask = () -> {
            System.out.println("[" + Thread.currentThread().getName() + "] task-2 will throw");
            Thread.sleep(100);
            throw new DataFetchException("upstream service unavailable");
        };
        Future<String> future = executor.submit(failingTask);
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // getCause() gives the original DataFetchException
            System.out.println("[main] caught ExecutionException");
            System.out.println("[main] actual cause type:    " + e.getCause().getClass().getSimpleName());
            System.out.println("[main] actual cause message: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            System.out.println("[main] timed out");
        }
    }

    static void timeoutAndCancelDemo() throws InterruptedException {
        Callable<String> slowTask = () -> {
            System.out.println("[" + Thread.currentThread().getName() + "] task-3 sleeping 2s");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                System.out.println("[" + Thread.currentThread().getName() + "] task-3 interrupted");
                Thread.currentThread().interrupt();
                return "interrupted";
            }
            return "task-3-result";
        };
        Future<String> future = executor.submit(slowTask);
        try {
            future.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.out.println("[main] task-3 timed out after 500ms — cancelling");
            boolean cancelled = future.cancel(true); // send interrupt
            System.out.println("[main] cancel(true) returned: " + cancelled);
            System.out.println("[main] future.isDone(): " + future.isDone());
            System.out.println("[main] future.isCancelled(): " + future.isCancelled());
        } catch (ExecutionException e) {
            System.out.println("[main] execution exception: " + e.getCause().getMessage());
        }
        Thread.sleep(300); // let task-3 finish reacting to interrupt
    }

    static void cancellationDemo() throws InterruptedException {
        Callable<String> task = () -> {
            System.out.println("[" + Thread.currentThread().getName() + "] task-4 sleeping 3s");
            Thread.sleep(3000);
            return "task-4-result";
        };
        Future<String> future = executor.submit(task);
        Thread.sleep(200); // let task start
        System.out.println("[main] cancelling task-4");
        future.cancel(true);
        try {
            future.get();
        } catch (CancellationException e) {
            System.out.println("[main] task-4 threw CancellationException as expected");
        } catch (ExecutionException e) {
            System.out.println("[main] unexpected ExecutionException");
        }
        System.out.println("[main] isDone()=" + future.isDone() + " isCancelled()=" + future.isCancelled());
    }

    static void invokeAllDemo() throws InterruptedException {
        List<Callable<String>> tasks = List.of(
                () -> { Thread.sleep(100); return "invokeAll-result-A"; },
                () -> { Thread.sleep(200); return "invokeAll-result-B"; },
                () -> { Thread.sleep(50);  return "invokeAll-result-C"; }
        );
        List<Future<String>> futures = executor.invokeAll(tasks, 5, TimeUnit.SECONDS);
        System.out.println("[main] invokeAll completed, processing results:");
        for (int i = 0; i < futures.size(); i++) {
            Future<String> f = futures.get(i);
            try {
                System.out.println("[main] task-" + (i + 1) + " result: " + f.get());
            } catch (ExecutionException e) {
                System.out.println("[main] task-" + (i + 1) + " failed: " + e.getCause().getMessage());
            } catch (CancellationException e) {
                System.out.println("[main] task-" + (i + 1) + " was cancelled (invokeAll timeout)");
            }
        }
    }
}
```

## Gotchas

**Calling get() without a timeout on a task that never completes causes the calling thread to block forever with no recovery mechanism.** IO operations that hang, deadlocked tasks, or tasks waiting on a condition that is never signalled will hold the calling thread permanently. In production code, always use get(timeout, unit) and handle TimeoutException with an explicit decision — cancel, retry, or fail fast.

**ExecutionException wraps the actual cause — e.getMessage() returns a generic wrapper message, not the task's exception message.** The useful information is in e.getCause(). A handler that prints e.getMessage() logs "java.util.concurrent.ExecutionException" or similar, hiding the actual error. Always unwrap with e.getCause() and log that exception's message and type.

**cancel(true) sends an interrupt to the executing thread, but if the task is in a non-interruptible blocking operation, the interrupt has no effect.** Tasks blocked in a synchronized block waiting for a monitor, in non-interruptible IO (like most java.io streams), or in any operation that does not check the interrupt flag will continue running despite cancel(true) returning true. The Future is marked cancelled, but the underlying thread keeps running.

**After cancel() returns true, the Future is in the cancelled state and get() throws CancellationException, not a null result.** isDone() returns true for cancelled Futures, which can confuse code that uses isDone() as a proxy for success. Always check isCancelled() separately if the distinction between normal completion and cancellation matters to the caller.

**invokeAny() cancels the non-winning tasks, but those tasks may still be running if cancel(true) did not interrupt them.** Resources held by those tasks — database connections, file handles, network sockets — are not automatically released. Design tasks used with invokeAny() to check Thread.isInterrupted() at checkpoints and release resources in a finally block.

**submit(Runnable) returns a Future<?> whose get() returns null on success.** Exceptions thrown by the Runnable are still wrapped in ExecutionException just like Callable, but the null return value makes it impossible to distinguish between a task that returned normally and a task that returned early. Use submit(Callable) when the task can fail in distinguishable ways; reserve submit(Runnable) for fire-and-forget tasks where only the exception behavior matters.
